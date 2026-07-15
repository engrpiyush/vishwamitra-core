package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.persistence.Stage3EntityJournalEntry
import ai.vishwakarma.labelling.persistence.Stage3EntityJournalRepository
import ai.vishwakarma.labelling.stage3.EmbeddingService
import ai.vishwakarma.labelling.stage3.EmbeddingTaskType
import ai.vishwakarma.labelling.stage3.EntityAdminRow
import ai.vishwakarma.labelling.stage3.EntityMintRow
import ai.vishwakarma.labelling.stage3.EntityRef
import ai.vishwakarma.labelling.stage3.EntityResolver
import ai.vishwakarma.labelling.stage3.MentionLinkRow
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import ai.vishwakarma.labelling.stage3.normalizeSurface
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Merge response body: what moved, and whose blocking keys went stale (LLD §11.3, VA-12). */
data class EntityMergeOutcome(
    val fromEntityId: String,
    val intoEntityId: String,
    val mentionsRewired: Int,
    val aliasesAdded: List<String>,
    /** Subjects whose MATCH blocking keys are now stale — suggest a re-run, never auto-run. */
    val affectedSubjectIds: List<String>,
    val journalId: String,
)

/** Split response body: how the entity's mentions redistributed. */
data class EntitySplitOutcome(
    val entityId: String,
    val mentionsKept: Int,
    val mentionsMoved: Int,
    val entitiesMinted: Int,
    val affectedSubjectIds: List<String>,
    /** Redistribution notes, "surface → TYPE|canonicalKey". */
    val moves: List<String>,
    val journalId: String,
)

/**
 * The §11.3 "Human repair" operations over the self-grown canon (VA-12): `merge(a → b)` rewires a's
 * MENTIONS onto b (batched, §21 A.2), unions aliases, and leaves `a.mergedInto = b` as the
 * tombstone redirect resolution follows; `split(a)` re-runs resolution for a's mentions with a
 * excluded — each surface exact-matches another entity, kNN-merges elsewhere, or mints its own node
 * (a mention whose key IS a's canonical key re-resolves to a and stays). Every action journals to
 * `stage3_entity_journal` with the affected subject ids (their MATCH blocking keys went stale — the
 * UI suggests a re-run; nothing re-runs automatically).
 */
@Service
class EntityAdminService(
    private val graph: Stage3GraphRepository,
    private val embeddings: EmbeddingService,
    private val journal: Stage3EntityJournalRepository,
    private val config: StageConfigService,
) {

    private val log = LoggerFactory.getLogger(EntityAdminService::class.java)

    // ---- merge (LLD §21 A.2) -------------------------------------------------------

    fun merge(
        fromEntityId: String,
        intoEntityId: String,
        actor: String?,
    ): Either<DomainError, EntityMergeOutcome> {
        if (fromEntityId == intoEntityId)
            return DomainError.Invalid("An entity cannot merge into itself").left()
        val from =
            graph.findEntityAdmin(fromEntityId)
                ?: return DomainError.NotFound("Entity $fromEntityId not found").left()
        val into =
            graph.findEntityAdmin(intoEntityId)
                ?: return DomainError.NotFound("Entity $intoEntityId not found").left()
        if (from.mergedInto != null)
            return DomainError.Conflict(
                    "Entity '${from.canonicalName}' is already merged into ${from.mergedInto} — " +
                        "operate on the live target"
                )
                .left()
        if (into.mergedInto != null)
            return DomainError.Conflict(
                    "Merge target '${into.canonicalName}' is itself a tombstone (merged into " +
                        "${into.mergedInto}) — merge into the live target instead"
                )
                .left()
        if (from.entityType != into.entityType)
            return DomainError.Conflict(
                    "Cannot merge across entity types (${from.entityType} → ${into.entityType}) " +
                        "— the canon is type-scoped (LLD §11.3)"
                )
                .left()

        val affectedSubjects =
            graph.entityMentionRows(fromEntityId).map { it.subjectId }.distinct().sorted()
        val rewired = graph.rewireMentions(fromEntityId, intoEntityId)

        // Union the alias sets: b keeps its own, gains a's canonical name + aliases — deduped by
        // normalized key, and never echoing b's own canonical key.
        val existingKeys = (into.aliasKeys + into.canonicalKey).toMutableSet()
        val aliases = into.aliases.toMutableList()
        val aliasKeys = into.aliasKeys.toMutableList()
        val added = mutableListOf<String>()
        (listOf(from.canonicalName) + from.aliases).forEach { surface ->
            val key = normalizeSurface(surface)
            if (key.isNotEmpty() && existingKeys.add(key)) {
                aliases += surface
                aliasKeys += key
                added += surface
            }
        }
        graph.finalizeEntityMerge(fromEntityId, intoEntityId, aliases, aliasKeys)

        val journalId =
            journal.record(
                Stage3EntityJournalEntry(
                    id = null,
                    action = "MERGE",
                    fromEntityId = fromEntityId,
                    fromName = from.canonicalName,
                    entityType = from.entityType,
                    intoEntityId = intoEntityId,
                    intoName = into.canonicalName,
                    actor = actor,
                    at = Instant.now(),
                    mentionsRewired = rewired.toInt(),
                    affectedSubjectIds = affectedSubjects,
                )
            )
        log.info(
            "Entity merge: '{}' ({}) → '{}' ({}), {} mention(s) rewired, {} subject(s) affected",
            from.canonicalName,
            fromEntityId,
            into.canonicalName,
            intoEntityId,
            rewired,
            affectedSubjects.size,
        )
        return EntityMergeOutcome(
                fromEntityId = fromEntityId,
                intoEntityId = intoEntityId,
                mentionsRewired = rewired.toInt(),
                aliasesAdded = added,
                affectedSubjectIds = affectedSubjects,
                journalId = journalId,
            )
            .right()
    }

    // ---- split (LLD §11.3) -----------------------------------------------------------

    fun split(entityId: String, actor: String?): Either<DomainError, EntitySplitOutcome> {
        val entity =
            graph.findEntityAdmin(entityId)
                ?: return DomainError.NotFound("Entity $entityId not found").left()
        if (entity.mergedInto != null)
            return DomainError.Conflict(
                    "Entity '${entity.canonicalName}' is a tombstone (merged into " +
                        "${entity.mergedInto}) — split the live target instead"
                )
                .left()

        val mentions = graph.entityMentionRows(entityId)
        val mints = linkedMapOf<String, EntityMintRow>()
        val moves = mutableListOf<MentionLinkRow>()
        val keptSurfaceKeys = mutableSetOf<String>()

        // One resolution decision per distinct surface key; every mention edge of that surface
        // follows it (re-embedding once per surface, not per edge).
        mentions
            .groupBy { normalizeSurface(it.surface) }
            .toSortedMap()
            .forEach { (key, rows) ->
                if (key.isEmpty() || key == entity.canonicalKey) {
                    keptSurfaceKeys += key
                    return@forEach
                }
                val target = resolveExcluding(entity, key, rows.first().surface, mints)
                if (target == null) {
                    keptSurfaceKeys += key
                    return@forEach
                }
                rows.forEach { row ->
                    moves +=
                        MentionLinkRow(
                            claimId = row.claimId,
                            entityType = target.entityType,
                            canonicalKey = target.canonicalKey,
                            surface = row.surface,
                            confidence = target.confidence,
                            provisional = target.provisional,
                            method = target.method,
                        )
                }
            }

        // The split entity keeps only the surfaces that re-resolved to it.
        val keptAliases =
            entity.aliases.filter { normalizeSurface(it) in keptSurfaceKeys }.distinct()
        val keptAliasKeys = entity.aliasKeys.filter { it in keptSurfaceKeys }.distinct()
        graph.applyEntitySplit(entityId, keptAliases, keptAliasKeys, mints.values.toList(), moves)

        val movedNotes =
            moves.map { "${it.surface} → ${it.entityType}|${it.canonicalKey}" }.distinct().sorted()
        val affectedSubjects =
            mentions
                .filter { row -> moves.any { it.claimId == row.claimId } }
                .map { it.subjectId }
                .distinct()
                .sorted()
        val journalId =
            journal.record(
                Stage3EntityJournalEntry(
                    id = null,
                    action = "SPLIT",
                    fromEntityId = entityId,
                    fromName = entity.canonicalName,
                    entityType = entity.entityType,
                    intoEntityId = null,
                    intoName = null,
                    actor = actor,
                    at = Instant.now(),
                    mentionsRewired = moves.size,
                    affectedSubjectIds = affectedSubjects,
                    details = movedNotes,
                )
            )
        log.info(
            "Entity split: '{}' ({}) — {} mention(s) redistributed ({} minted), {} kept, {} " +
                "subject(s) affected",
            entity.canonicalName,
            entityId,
            moves.size,
            mints.size,
            mentions.size - moves.size,
            affectedSubjects.size,
        )
        return EntitySplitOutcome(
                entityId = entityId,
                mentionsKept = mentions.size - moves.size,
                mentionsMoved = moves.size,
                entitiesMinted = mints.size,
                affectedSubjectIds = affectedSubjects,
                moves = movedNotes,
                journalId = journalId,
            )
            .right()
    }

    /**
     * The §11.3 pipeline for one split surface, with the split entity excluded from candidates:
     * exact key/alias hit (redirects followed) → kNN over the remaining same-type canon
     * (threshold/near-miss semantics) → mint. Returns null for "stays on the split entity" (only
     * possible when the mint MERGE would land back on it, which the canonical-key guard already
     * short-circuits upstream).
     */
    private fun resolveExcluding(
        entity: EntityAdminRow,
        key: String,
        surface: String,
        mints: LinkedHashMap<String, EntityMintRow>,
    ): SplitTarget? {
        val type = entity.entityType
        mints["$type|$key"]?.let {
            return SplitTarget(type, key, 1.0, provisional = false, method = "EXACT")
        }
        graph.findEntityByKeyExcluding(type, key, entity.entityId)?.let { hit ->
            val live = followRedirects(hit, exclude = entity.entityId)
            return SplitTarget(
                live.entityType,
                live.canonicalKey,
                1.0,
                provisional = false,
                method = "EXACT",
            )
        }
        val vector = embeddings.embed(surface, EmbeddingTaskType.CLASSIFICATION)
        val top =
            graph.entityKnn(type, vector, EntityResolver.ENTITY_KNN_K + 1).firstOrNull {
                it.entityId != entity.entityId
            }
        val threshold = config.stage3().entityMergeThreshold
        return when {
            top != null && top.score >= threshold ->
                SplitTarget(top.entityType, top.canonicalKey, top.score, false, "EMBED")
            top != null && top.score >= threshold - EntityResolver.REVIEW_BAND_WIDTH ->
                SplitTarget(top.entityType, top.canonicalKey, top.score, true, "EMBED")
            else -> {
                mints["$type|$key"] =
                    EntityMintRow(
                        entityType = type,
                        canonicalKey = key,
                        entityId = UUID.randomUUID().toString(),
                        canonicalName = surface,
                        embedding = vector,
                        embeddingStamp = embeddings.versionStamp,
                        createdFrom = "split:${entity.entityId}",
                    )
                SplitTarget(type, key, 1.0, provisional = false, method = "MINT")
            }
        }
    }

    /** Bounded redirect walk for the split's exact leg (compression rides the resolver's path). */
    private fun followRedirects(start: EntityRef, exclude: String): EntityRef {
        var current = start
        var hops = 0
        while (current.mergedInto != null && hops < EntityResolver.MAX_REDIRECT_HOPS) {
            val next =
                graph.findEntityById(current.mergedInto!!)?.takeIf { it.entityId != exclude }
                    ?: return current
            current = next
            hops++
        }
        return current
    }

    private data class SplitTarget(
        val entityType: String,
        val canonicalKey: String,
        val confidence: Double,
        val provisional: Boolean,
        val method: String,
    )
}

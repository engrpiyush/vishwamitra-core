package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import java.text.Normalizer
import java.util.UUID
import org.springframework.stereotype.Component

/** New `:Entity` row — MERGEd by `(entityType, canonicalKey)`, so replays and races are no-ops. */
data class EntityMintRow(
    val entityType: String,
    val canonicalKey: String,
    val entityId: String,
    val canonicalName: String,
    val embedding: List<Double>,
    val embeddingStamp: String,
    /** The claim whose mention minted this entity (LLD §9.2 `createdFrom`). */
    val createdFrom: String,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "entityType" to entityType,
            "canonicalKey" to canonicalKey,
            "entityId" to entityId,
            "canonicalName" to canonicalName,
            "embedding" to embedding,
            "embeddingStamp" to embeddingStamp,
            "createdFrom" to createdFrom,
        )
}

/**
 * One `MENTIONS` edge to write. Targets are addressed by `(entityType, canonicalKey)` — stable for
 * both existing entities and same-batch mints, and immune to the mint-MERGE losing a race (the
 * planned entityId may not survive; the key always does).
 */
data class MentionLinkRow(
    val claimId: String,
    val entityType: String,
    val canonicalKey: String,
    val surface: String,
    val confidence: Double,
    /** True = the §11.3 near-miss band — linked, but flagged for the entity-review list (VA-25). */
    val provisional: Boolean,
    /** EXACT (canonicalKey/alias hit) · EMBED (kNN cosine) · MINT (new entity). */
    val method: String,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "claimId" to claimId,
            "entityType" to entityType,
            "canonicalKey" to canonicalKey,
            "surface" to surface,
            "confidence" to confidence,
            "provisional" to provisional,
            "method" to method,
        )
}

/** Surface adopted as an alias of an existing entity (a confident ≥-threshold kNN merge). */
data class AliasAppendRow(
    val entityType: String,
    val canonicalKey: String,
    val surface: String,
    val aliasKey: String,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "entityType" to entityType,
            "canonicalKey" to canonicalKey,
            "surface" to surface,
            "aliasKey" to aliasKey,
        )
}

/** `Source.issuerTypeAndKey` stamp — the §18.2 Q2 upgrade hook (keyed, not id'd: race-stable). */
data class SourceIssuerRow(val assetId: String, val issuerTypeAndKey: String) {
    fun toMap(): Map<String, Any?> =
        mapOf("assetId" to assetId, "issuerTypeAndKey" to issuerTypeAndKey)
}

/** Everything one RESOLVE_ENTITIES tick writes — applied as a single transaction. */
data class EntityResolutionWrite(
    val stamp: String,
    /** Every claim in the batch — all get `entityResolutionStamp`, mentions or not. */
    val claimIds: List<String>,
    val mints: List<EntityMintRow>,
    val links: List<MentionLinkRow>,
    val aliasAppends: List<AliasAppendRow>,
    val sourceIssuers: List<SourceIssuerRow>,
)

/** Per-tick counters (LLD §9.6: entities resolved — linked / minted / review-listed). */
data class ResolutionOutcome(
    val claimsResolved: Int,
    val linked: Int,
    val minted: Int,
    val reviewListed: Int,
)

/**
 * The §11.3 resolution pipeline, per extracted mention:
 * 1. `normalize(surface)` → `canonicalKey`;
 * 2. exact key/alias match within the entity type (tombstone redirects followed) → **link**;
 * 3. else embed the surface (`CLASSIFICATION` task) and kNN over same-type canon: ≥
 *    `entity-merge-threshold` → **link + adopt alias** · within [REVIEW_BAND_WIDTH] below →
 *    **provisional link** on the entity-review list · below the band → **mint**.
 *
 * Resolution is deliberately global — not subject-scoped: subject B's "Neo4j" links to the node
 * subject A minted (the Q1 payoff; entities carry no claim text, so §9.1 holds). All decisions
 * happen here in app code; [Stage3GraphRepository.applyEntityResolution] persists the plan in one
 * transaction, so a failed tick persists nothing and the stamp cursor simply retries it.
 */
@Component
class EntityResolver(
    private val graph: Stage3GraphRepository,
    private val embeddings: EmbeddingService,
    private val props: AppProperties,
) {

    fun resolve(
        subjectId: String,
        batch: List<ClaimToResolve>,
        extracted: Map<String, ExtractedMentions>,
        stamp: String,
    ): ResolutionOutcome {
        val mints = linkedMapOf<String, EntityMintRow>() // typeAndKey → mint (in-batch dedupe)
        val links = mutableListOf<MentionLinkRow>()
        val aliasAppends = linkedMapOf<String, AliasAppendRow>()
        val sourceIssuers = linkedMapOf<String, SourceIssuerRow>()
        var linked = 0
        var reviewListed = 0

        for (claim in batch) {
            val extraction = extracted[claim.claimId] ?: ExtractedMentions(emptyList())
            // normalized key → resolved target, for the issuer pick below.
            val targets = mutableListOf<ResolvedTarget>()
            for (mention in dedupeMentions(extraction.mentions)) {
                val key = normalizeSurface(mention.surface)
                if (key.isEmpty()) continue
                val type = mention.entityType.name
                val target =
                    when {
                        // Minted earlier in this batch — the batch-local canon hit.
                        mints.containsKey("$type|$key") -> {
                            linked++
                            ResolvedTarget(type, key, 1.0, false, "EXACT")
                        }
                        else ->
                            lookupExact(type, key)?.let { existing ->
                                linked++
                                ResolvedTarget(
                                    existing.entityType,
                                    existing.canonicalKey,
                                    1.0,
                                    false,
                                    "EXACT",
                                )
                            }
                                ?: resolveByEmbedding(mention, type, key, claim.claimId, mints)
                                    ?.also {
                                        when {
                                            it.provisional -> reviewListed++
                                            it.method == "EMBED" -> {
                                                linked++
                                                aliasAppends.putIfAbsent(
                                                    "${it.entityType}|${it.canonicalKey}|$key",
                                                    AliasAppendRow(
                                                        entityType = it.entityType,
                                                        canonicalKey = it.canonicalKey,
                                                        surface = mention.surface,
                                                        aliasKey = key,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                    } ?: continue
                links +=
                    MentionLinkRow(
                        claimId = claim.claimId,
                        entityType = target.entityType,
                        canonicalKey = target.canonicalKey,
                        surface = mention.surface,
                        confidence = target.confidence,
                        provisional = target.provisional,
                        method = target.method,
                    )
                targets += target.copy(surfaceKey = key)
            }
            deriveIssuer(claim, extraction, targets)?.let { sourceIssuers[it.assetId] = it }
        }

        graph.applyEntityResolution(
            subjectId,
            EntityResolutionWrite(
                stamp = stamp,
                claimIds = batch.map { it.claimId },
                mints = mints.values.toList(),
                links = links,
                aliasAppends = aliasAppends.values.toList(),
                sourceIssuers = sourceIssuers.values.toList(),
            ),
        )
        return ResolutionOutcome(
            claimsResolved = batch.size,
            linked = linked,
            minted = mints.size,
            reviewListed = reviewListed,
        )
    }

    /** One mention per (type, normalized key) per claim — first surface wins. */
    private fun dedupeMentions(mentions: List<ExtractedMention>): List<ExtractedMention> =
        mentions.distinctBy { "${it.entityType.name}|${normalizeSurface(it.surface)}" }

    /** Exact canonicalKey/alias match, tombstone redirects followed (bounded — see §11.3). */
    private fun lookupExact(type: String, key: String): EntityRef? {
        var current = graph.findEntityByKey(type, key) ?: return null
        var hops = 0
        while (current.mergedInto != null && hops < MAX_REDIRECT_HOPS) {
            current = graph.findEntityById(current.mergedInto!!) ?: return current
            hops++
        }
        return current
    }

    /**
     * The kNN leg: embed the surface (same model as claims, `CLASSIFICATION` task — §11.4) and take
     * the nearest same-type live entity. Threshold semantics per §11.3; a mint stores the surface
     * embedding on the new entity so future mentions can find it.
     */
    private fun resolveByEmbedding(
        mention: ExtractedMention,
        type: String,
        key: String,
        claimId: String,
        mints: LinkedHashMap<String, EntityMintRow>,
    ): ResolvedTarget? {
        val vector = embeddings.embed(mention.surface, EmbeddingTaskType.CLASSIFICATION)
        val top = graph.entityKnn(type, vector, ENTITY_KNN_K).firstOrNull()
        val threshold = props.stage3.entityMergeThreshold
        return when {
            top != null && top.score >= threshold ->
                ResolvedTarget(top.entityType, top.canonicalKey, top.score, false, "EMBED")
            top != null && top.score >= threshold - REVIEW_BAND_WIDTH ->
                ResolvedTarget(top.entityType, top.canonicalKey, top.score, true, "EMBED")
            else -> {
                mints["$type|$key"] =
                    EntityMintRow(
                        entityType = type,
                        canonicalKey = key,
                        entityId = UUID.randomUUID().toString(),
                        canonicalName = mention.surface,
                        embedding = vector,
                        embeddingStamp = embeddings.versionStamp,
                        createdFrom = claimId,
                    )
                ResolvedTarget(type, key, 1.0, false, "MINT")
            }
        }
    }

    /**
     * The §18.2 Q2 hook: a DOCUMENTARY claim whose extraction names an issuer that resolved as an
     * ORG/INSTITUTION mention stamps `issuerTypeAndKey` onto its `:Source` — the idempotent
     * attestor-upgrade sweep ([Stage3GraphRepository.upgradeIssuerAttestors]) picks it up when the
     * phase completes. An issuer surface that never appeared in the mentions is dropped (the
     * prompt's contract), never resolved on faith.
     */
    private fun deriveIssuer(
        claim: ClaimToResolve,
        extraction: ExtractedMentions,
        targets: List<ResolvedTarget>,
    ): SourceIssuerRow? {
        if (claim.sourceClass != "DOCUMENTARY") return null
        if (claim.assetId == null) return null
        val issuerKey =
            extraction.issuerSurface?.let(::normalizeSurface)?.takeIf { it.isNotEmpty() }
                ?: return null
        val issuer =
            targets.firstOrNull {
                it.surfaceKey == issuerKey && it.entityType in ISSUER_ENTITY_TYPES
            } ?: return null
        return SourceIssuerRow(
            assetId = claim.assetId,
            issuerTypeAndKey = "${issuer.entityType}|${issuer.canonicalKey}",
        )
    }

    private data class ResolvedTarget(
        val entityType: String,
        val canonicalKey: String,
        val confidence: Double,
        val provisional: Boolean,
        val method: String,
        /** The mention's own normalized key (≠ canonicalKey after an alias/kNN merge). */
        val surfaceKey: String = "",
    )

    companion object {
        /**
         * The §11.3 near-miss review band sits this far below `entity-merge-threshold` (defaults:
         * 0.75–0.85). Derived, not configured — moving the threshold moves the band with it.
         */
        const val REVIEW_BAND_WIDTH = 0.10
        /** Candidates fetched per surface (LLD §21 A.2); only the top one decides. */
        const val ENTITY_KNN_K = 5
        const val MAX_REDIRECT_HOPS = 5

        private val ISSUER_ENTITY_TYPES = setOf(EntityType.ORG.name, EntityType.INSTITUTION.name)
    }
}

/**
 * `surface → canonicalKey` (LLD §11.3): NFKD-fold diacritics, lowercase, drop word-internal
 * punctuation (`.` `'`), space-fold separator punctuation, collapse whitespace. Deliberately
 * **keeps** identity-bearing symbols (`+ # & / @ _ $`) — the LLD's blunt "strip punctuation" would
 * collapse "C++" and "C#" into "c" (the §15 #8 over-merge, self-inflicted). So: "R. Mehta" ≡ "r
 * mehta", "B.E." ≡ "BE", "O'Brien" ≡ "obrien", "full-stack" ≡ "full stack", while "C++" / "C#" /
 * "CI/CD" stay themselves.
 */
internal fun normalizeSurface(surface: String): String =
    Normalizer.normalize(surface, Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace(JOINED_PUNCT, "")
        .replace(SEPARATOR_PUNCT, " ")
        .replace(WHITESPACE, " ")
        .trim()

private val COMBINING_MARKS = Regex("\\p{M}+")
private val JOINED_PUNCT = Regex("[.'’`]")
private val SEPARATOR_PUNCT = Regex("[,;:!?\"()\\[\\]{}<>|\\\\*~^—–-]")
private val WHITESPACE = Regex("\\s+")

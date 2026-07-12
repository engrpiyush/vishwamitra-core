package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.PublishedAttestor
import ai.vishwakarma.labelling.domain.PublishedEntityMention
import ai.vishwakarma.labelling.domain.PublishedFactStamp
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SpeakerRole
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class ClaimRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Claim? =
        col.document(id).get().await().takeIf { it.exists() }?.toClaim()

    /** All claims for a subject, newest first. */
    fun findBySubject(subjectId: String): List<Claim> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toClaim() }
            .sortedByDescending { it.createdAt }

    /** All claims extracted from one asset, newest first. */
    fun findByAsset(assetId: String): List<Claim> =
        col.whereEqualTo("assetId", assetId)
            .get()
            .await()
            .documents
            .map { it.toClaim() }
            .sortedByDescending { it.createdAt }

    fun save(claim: Claim) {
        col.document(claim.id).set(claim.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    /**
     * The §11.11 publish write-back: each claim's authenticity vector lands in one atomic per-doc
     * update (LLD §15 #10), batched under Firestore's limit. Idempotent — a resumed PUBLISHING tick
     * re-writes identical values, so a crash mid-batch can never half-write or duplicate.
     */
    fun publishAuthenticity(rows: List<ClaimAuthenticityRow>) {
        if (rows.isEmpty()) return
        rows.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { row ->
                batch.update(
                    col.document(row.claimId),
                    mapOf(
                        "authenticityScore" to row.score,
                        "authenticitySignals" to row.signals,
                        "authenticityTier" to row.tier,
                        "scoreRunId" to row.scoreRunId,
                        "scoredAt" to row.scoredAt.toTimestamp(),
                        // Contract v2 (§11.11): the claim's fact/entity context rides along.
                        "publishContractVersion" to row.contractVersion,
                        "authenticityScoreBare" to row.scoreBare,
                        "factStamp" to row.factStamp?.toMap(),
                        "entityMentions" to row.entityMentions.map { it.toMap() },
                        "edgeCounts" to row.edgeCounts,
                        "attestor" to row.attestor?.toMap(),
                    ),
                )
            }
            batch.commit().await()
        }
    }

    private fun Claim.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "assetId" to assetId,
            "claimType" to claimType.name,
            "text" to text,
            "speaker" to speaker,
            "speakerRole" to speakerRole?.name,
            "mediaStart" to mediaStart,
            "mediaEnd" to mediaEnd,
            "sourceExcerpt" to sourceExcerpt,
            "claimedDate" to claimedDate.toIsoDate(),
            "authenticityTier" to authenticityTier?.name,
            "sourceClass" to sourceClass?.name,
            "relationship" to relationship?.name,
            "authenticityScore" to authenticityScore,
            "authenticitySignals" to authenticitySignals,
            "scoreRunId" to scoreRunId,
            "scoredAt" to scoredAt.toTimestamp(),
            "extractionConfidence" to extractionConfidence,
            "claimBasis" to claimBasis?.name,
            "sensitive" to sensitive,
            "favorability" to favorability,
            "extractionPromptId" to extractionPromptId,
            "extractionPromptVersion" to extractionPromptVersion,
            "extractionPromptHash" to extractionPromptHash,
            "publishContractVersion" to publishContractVersion,
            "authenticityScoreBare" to authenticityScoreBare,
            "factStamp" to factStamp?.toMap(),
            "entityMentions" to entityMentions?.map { it.toMap() },
            "edgeCounts" to edgeCounts,
            "attestor" to attestor?.toMap(),
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "stage2ProcessedAt" to stage2ProcessedAt.toTimestamp(),
        )

    private fun DocumentSnapshot.toClaim(): Claim =
        Claim(
            id = id,
            subjectId = getString("subjectId") ?: "",
            assetId = getString("assetId") ?: "",
            claimType = ClaimType.fromOrNull(getString("claimType")) ?: ClaimType.EPISODE,
            text = getString("text") ?: "",
            speaker = getString("speaker"),
            speakerRole = SpeakerRole.fromOrNull(getString("speakerRole")),
            mediaStart = getDouble("mediaStart"),
            mediaEnd = getDouble("mediaEnd"),
            sourceExcerpt = getString("sourceExcerpt"),
            claimedDate = localDate("claimedDate"),
            authenticityTier = AuthenticityTier.fromOrNull(getString("authenticityTier")),
            sourceClass = SourceClass.fromOrNull(getString("sourceClass")),
            relationship = Relationship.fromOrNull(getString("relationship")),
            authenticityScore = getDouble("authenticityScore"),
            authenticitySignals = signalMap("authenticitySignals"),
            scoreRunId = getString("scoreRunId"),
            scoredAt = instant("scoredAt"),
            extractionConfidence = getDouble("extractionConfidence"),
            claimBasis = ClaimBasis.fromOrNull(getString("claimBasis")),
            sensitive = getBoolean("sensitive") ?: false,
            favorability = getDouble("favorability"),
            extractionPromptId = getString("extractionPromptId"),
            extractionPromptVersion = getLong("extractionPromptVersion")?.toInt(),
            extractionPromptHash = getString("extractionPromptHash"),
            publishContractVersion = getLong("publishContractVersion")?.toInt(),
            authenticityScoreBare = getDouble("authenticityScoreBare"),
            factStamp = rawMap(get("factStamp"))?.toFactStamp(),
            entityMentions =
                (get("entityMentions") as? List<*>)?.mapNotNull { rawMap(it)?.toEntityMention() },
            edgeCounts =
                rawMap(get("edgeCounts"))
                    ?.mapNotNull { (k, v) -> (v as? Number)?.let { n -> k to n.toInt() } }
                    ?.toMap(),
            attestor = rawMap(get("attestor"))?.toAttestor(),
            createdAt = instant("createdAt"),
            stage2ProcessedAt = instant("stage2ProcessedAt"),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.signalMap(field: String): Map<String, Double>? {
        val raw = get(field) as? Map<String, Any?> ?: return null
        return raw.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }.toMap()
    }

    // ---- contract-v2 block mappers (tolerant: missing/misshapen fields → null) ------------

    @Suppress("UNCHECKED_CAST")
    private fun rawMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    private fun PublishedFactStamp.toMap(): Map<String, Any?> =
        mapOf(
            "factId" to factId,
            "label" to label,
            "exemplarClaimId" to exemplarClaimId,
            "kind" to kind,
            "slot" to slot,
            "validFrom" to validFrom,
            "validTo" to validTo,
            "datePrecision" to datePrecision,
            "anchored" to anchored,
            "belief" to belief,
            "beliefBare" to beliefBare,
            "memberCount" to memberCount,
        )

    private fun Map<String, Any?>.toFactStamp(): PublishedFactStamp? =
        (this["factId"] as? String)?.let { factId ->
            PublishedFactStamp(
                factId = factId,
                label = this["label"] as? String ?: "",
                exemplarClaimId = this["exemplarClaimId"] as? String,
                kind = this["kind"] as? String,
                slot = this["slot"] as? String,
                validFrom = this["validFrom"] as? String,
                validTo = this["validTo"] as? String,
                datePrecision = this["datePrecision"] as? String,
                anchored = this["anchored"] as? Boolean ?: false,
                belief = (this["belief"] as? Number)?.toDouble(),
                beliefBare = (this["beliefBare"] as? Number)?.toDouble(),
                memberCount = (this["memberCount"] as? Number)?.toInt() ?: 1,
            )
        }

    private fun PublishedEntityMention.toMap(): Map<String, Any?> =
        mapOf(
            "surface" to surface,
            "canonicalName" to canonicalName,
            "entityType" to entityType,
            "provisional" to provisional,
        )

    private fun Map<String, Any?>.toEntityMention(): PublishedEntityMention? =
        (this["canonicalName"] as? String)?.let { name ->
            PublishedEntityMention(
                surface = this["surface"] as? String,
                canonicalName = name,
                entityType = this["entityType"] as? String,
                provisional = this["provisional"] as? Boolean ?: false,
            )
        }

    private fun PublishedAttestor.toMap(): Map<String, Any?> =
        mapOf("key" to key, "name" to name, "kind" to kind, "trust" to trust)

    private fun Map<String, Any?>.toAttestor(): PublishedAttestor =
        PublishedAttestor(
            key = this["key"] as? String,
            name = this["name"] as? String,
            kind = this["kind"] as? String,
            trust = (this["trust"] as? Number)?.toDouble(),
        )

    companion object {
        const val COLLECTION = "claims"
        /** Firestore write-batch hard limit. */
        private const val BATCH_LIMIT = 500
    }
}

/** One claim's published vector (§11.11) — what [ClaimRepository.publishAuthenticity] writes. */
data class ClaimAuthenticityRow(
    val claimId: String,
    val score: Double,
    val signals: Map<String, Double>,
    val tier: String,
    val scoreRunId: String,
    val scoredAt: java.time.Instant,
    /** Contract-v2 block ([PublishContract.VERSION]) — the claim's fact/entity context. */
    val contractVersion: Int = PublishContract.VERSION,
    val scoreBare: Double? = null,
    val factStamp: PublishedFactStamp? = null,
    val entityMentions: List<PublishedEntityMention> = emptyList(),
    val edgeCounts: Map<String, Int> = emptyMap(),
    val attestor: PublishedAttestor? = null,
)

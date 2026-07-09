package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimType
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
            createdAt = instant("createdAt"),
            stage2ProcessedAt = instant("stage2ProcessedAt"),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.signalMap(field: String): Map<String, Double>? {
        val raw = get(field) as? Map<String, Any?> ?: return null
        return raw.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }.toMap()
    }

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
)

package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimType
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

    private fun Claim.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "assetId" to assetId,
            "claimType" to claimType.name,
            "text" to text,
            "speaker" to speaker,
            "mediaStart" to mediaStart,
            "mediaEnd" to mediaEnd,
            "sourceExcerpt" to sourceExcerpt,
            "claimedDate" to claimedDate.toIsoDate(),
            "authenticityTier" to authenticityTier?.name,
            "authenticityScore" to authenticityScore,
            "extractionConfidence" to extractionConfidence,
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
            mediaStart = getDouble("mediaStart"),
            mediaEnd = getDouble("mediaEnd"),
            sourceExcerpt = getString("sourceExcerpt"),
            claimedDate = localDate("claimedDate"),
            authenticityTier = AuthenticityTier.fromOrNull(getString("authenticityTier")),
            authenticityScore = getDouble("authenticityScore"),
            extractionConfidence = getDouble("extractionConfidence"),
            createdAt = instant("createdAt"),
            stage2ProcessedAt = instant("stage2ProcessedAt"),
        )

    companion object {
        const val COLLECTION = "claims"
    }
}

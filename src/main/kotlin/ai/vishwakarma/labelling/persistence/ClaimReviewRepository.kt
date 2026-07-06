package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.ReviewDecision
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository

/**
 * The §12.6 review sidecar store. One document per reviewed claim, **keyed by the claim id** — the
 * review flow locks claims before review starts, so ids are stable and no content fingerprint is
 * needed. Absence of a document means the claim is undecided (the service applies the default).
 */
@Repository
class ClaimReviewRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun findByClaim(claimId: String): ClaimReview? =
        col.document(claimId).get().await().takeIf { it.exists() }?.toReview()

    /** Every review recorded for a subject (sparse — only claims a human has acted on). */
    fun findBySubject(subjectId: String): List<ClaimReview> =
        col.whereEqualTo("subjectId", subjectId).get().await().documents.map { it.toReview() }

    fun save(review: ClaimReview) {
        col.document(review.claimId).set(review.toMap()).await()
    }

    fun delete(claimId: String) {
        col.document(claimId).delete().await()
    }

    private fun ClaimReview.toMap(): Map<String, Any?> =
        mapOf(
            "claimId" to claimId,
            "subjectId" to subjectId,
            "decision" to decision?.name,
            "justification" to justification,
            "corroboratingClaimIds" to corroboratingClaimIds,
            "piiChoice" to piiChoice?.name,
            "reviewedBy" to reviewedBy,
            "reviewedAt" to reviewedAt.toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toReview(): ClaimReview =
        ClaimReview(
            claimId = id,
            subjectId = getString("subjectId") ?: "",
            decision = ReviewDecision.fromOrNull(getString("decision")),
            justification = getString("justification"),
            corroboratingClaimIds =
                (get("corroboratingClaimIds") as? List<*>)?.mapNotNull { it as? String }
                    ?: emptyList(),
            piiChoice = PiiChoice.fromOrNull(getString("piiChoice")),
            reviewedBy = getString("reviewedBy"),
            reviewedAt = instant("reviewedAt"),
        )

    companion object {
        const val COLLECTION = "claim_reviews"
    }
}

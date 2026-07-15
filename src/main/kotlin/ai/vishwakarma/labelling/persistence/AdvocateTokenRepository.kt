package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AdvocateToken
import ai.vishwakarma.labelling.domain.TokenEmailStatus
import ai.vishwakarma.labelling.domain.TokenStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository

/** `advocate_tokens` collection — doc id = the token's HMAC hash (LLD §5.1/§6.2). */
@Repository
class AdvocateTokenRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    /** O(1) redemption lookup: the presented token is hashed and fetched by id. */
    fun find(tokenHash: String): AdvocateToken? =
        col.document(tokenHash).get().await().takeIf { it.exists() }?.toToken()

    fun listBySubject(subjectId: String): List<AdvocateToken> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toToken() }
            .sortedByDescending { it.createdAt }

    fun save(token: AdvocateToken) {
        col.document(token.tokenHash).set(token.toMap()).await()
    }

    private fun AdvocateToken.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "guestEmail" to guestEmail,
            "status" to status.name,
            "createdBy" to createdBy,
            "createdAt" to createdAt.toTimestamp(),
            "redeemedAt" to redeemedAt.toTimestamp(),
            "sessionId" to sessionId,
            "emailStatus" to emailStatus?.name,
        )

    private fun DocumentSnapshot.toToken(): AdvocateToken =
        AdvocateToken(
            tokenHash = id,
            subjectId = getString("subjectId") ?: "",
            guestEmail = getString("guestEmail") ?: "",
            status = TokenStatus.fromOrNull(getString("status")) ?: TokenStatus.UNREDEEMED,
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            redeemedAt = instant("redeemedAt"),
            sessionId = getString("sessionId"),
            emailStatus = TokenEmailStatus.fromOrNull(getString("emailStatus")),
        )

    companion object {
        const val COLLECTION = "advocate_tokens"
    }
}

package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AdvocateSession
import ai.vishwakarma.labelling.domain.AdvocateToken
import ai.vishwakarma.labelling.domain.SessionKind
import ai.vishwakarma.labelling.domain.TokenEmailStatus
import ai.vishwakarma.labelling.domain.TokenStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Repository

/** `advocate_tokens` collection — doc id = the token's HMAC hash (LLD §5.1/§6.2). */
@Repository
class AdvocateTokenRepository(
    private val db: Firestore,
    private val sessions: AdvocateSessionRepository,
) {

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

    /** §11.2 mark: the send failed after the row was written — never roll the token back. */
    fun markEmailFailed(tokenHash: String) {
        col.document(tokenHash).update("emailStatus", TokenEmailStatus.FAILED.name).await()
    }

    /**
     * The §6.3 redemption transaction: an UNREDEEMED token belonging to [subjectId] flips to
     * REDEEMED and mints the GUEST session row atomically (one token → one session, ever). Null =
     * no such token / foreign subject / already used — deliberately indistinguishable (no oracle).
     */
    fun redeem(
        tokenHash: String,
        subjectId: String,
        sessionId: String,
        ttl: Duration
    ): AdvocateSession? =
        db.runTransaction { tx ->
                val ref = col.document(tokenHash)
                val token = tx.get(ref).await().takeIf { it.exists() }?.toToken()
                if (
                    token == null ||
                        token.subjectId != subjectId ||
                        token.status != TokenStatus.UNREDEEMED
                ) {
                    null
                } else {
                    val now = Instant.now()
                    val session =
                        AdvocateSession(
                            sessionId = sessionId,
                            subjectId = subjectId,
                            tokenId = tokenHash,
                            guestEmail = token.guestEmail,
                            kind = SessionKind.GUEST,
                            createdAt = now,
                            expiresAt = now.plus(ttl),
                            lastActivityAt = now,
                        )
                    tx.update(
                        ref,
                        mapOf(
                            "status" to TokenStatus.REDEEMED.name,
                            "redeemedAt" to now.toTimestamp(),
                            "sessionId" to sessionId,
                        ),
                    )
                    sessions.createIn(tx, session)
                    session
                }
            }
            .await()

    /**
     * Transactional revoke — UNREDEEMED only (an in-flight redemption wins the race; a REDEEMED
     * token is never rewritten). True = the row is now REVOKED.
     */
    fun revokeIfUnredeemed(tokenHash: String): Boolean =
        db.runTransaction { tx ->
                val ref = col.document(tokenHash)
                val token = tx.get(ref).await().takeIf { it.exists() }?.toToken()
                if (token?.status != TokenStatus.UNREDEEMED) {
                    false
                } else {
                    tx.update(ref, "status", TokenStatus.REVOKED.name)
                    true
                }
            }
            .await()

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

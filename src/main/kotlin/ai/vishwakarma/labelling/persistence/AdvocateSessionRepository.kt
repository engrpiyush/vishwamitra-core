package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AdvocateSession
import ai.vishwakarma.labelling.domain.SessionKind
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Transaction
import org.springframework.stereotype.Repository

/** `advocate_sessions` collection — doc id = sessionId (the cookie value, LLD §5.1). */
@Repository
class AdvocateSessionRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun find(sessionId: String): AdvocateSession? =
        col.document(sessionId).get().await().takeIf { it.exists() }?.toSession()

    fun listBySubject(subjectId: String): List<AdvocateSession> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toSession() }
            .sortedByDescending { it.createdAt }

    fun save(session: AdvocateSession) {
        col.document(session.sessionId).set(session.toMap()).await()
    }

    /** Write inside a caller-owned transaction (§6.3: token flip + session mint are atomic). */
    fun createIn(tx: Transaction, session: AdvocateSession) {
        tx.set(col.document(session.sessionId), session.toMap())
    }

    private fun AdvocateSession.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "tokenId" to tokenId,
            "guestEmail" to guestEmail,
            "kind" to kind.name,
            "createdAt" to createdAt.toTimestamp(),
            "expiresAt" to expiresAt.toTimestamp(),
            "messageCount" to messageCount,
            "lastActivityAt" to lastActivityAt.toTimestamp(),
        )

    private fun DocumentSnapshot.toSession(): AdvocateSession =
        AdvocateSession(
            sessionId = id,
            subjectId = getString("subjectId") ?: "",
            tokenId = getString("tokenId"),
            guestEmail = getString("guestEmail"),
            kind = SessionKind.fromOrNull(getString("kind")) ?: SessionKind.GUEST,
            createdAt = instant("createdAt"),
            expiresAt = instant("expiresAt"),
            messageCount = getLong("messageCount")?.toInt() ?: 0,
            lastActivityAt = instant("lastActivityAt"),
        )

    companion object {
        const val COLLECTION = "advocate_sessions"
    }
}

package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AdvocateMessage
import ai.vishwakarma.labelling.domain.MessageSender
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import org.springframework.stereotype.Repository

/**
 * `advocate_messages` collection — append-only transcript rows (LLD §5.1). The two list queries
 * need the composite indexes `(sessionId, createdAt)` and `(subjectId, createdAt)` — see
 * `firestore.indexes.json` at the repo root.
 */
@Repository
class AdvocateMessageRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun save(message: AdvocateMessage) {
        col.document(message.id).set(message.toMap()).await()
    }

    fun listBySession(sessionId: String): List<AdvocateMessage> =
        col.whereEqualTo("sessionId", sessionId)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .get()
            .await()
            .documents
            .map { it.toMessage() }

    fun listBySubject(subjectId: String, limit: Int = 200): List<AdvocateMessage> =
        col.whereEqualTo("subjectId", subjectId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .await()
            .documents
            .map { it.toMessage() }

    private fun AdvocateMessage.toMap(): Map<String, Any?> =
        mapOf(
            "sessionId" to sessionId,
            "subjectId" to subjectId,
            "sender" to sender.name,
            "text" to text,
            "createdAt" to createdAt.toTimestamp(),
            "latencyMs" to latencyMs,
            "truncated" to truncated,
        )

    private fun DocumentSnapshot.toMessage(): AdvocateMessage =
        AdvocateMessage(
            id = id,
            sessionId = getString("sessionId") ?: "",
            subjectId = getString("subjectId") ?: "",
            sender = MessageSender.fromOrNull(getString("sender")) ?: MessageSender.GUEST,
            text = getString("text") ?: "",
            createdAt = instant("createdAt"),
            latencyMs = getLong("latencyMs"),
            truncated = getBoolean("truncated") ?: false,
        )

    companion object {
        const val COLLECTION = "advocate_messages"
    }
}

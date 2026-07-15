package ai.vishwakarma.labelling.domain

import java.time.Instant

/** Message author within an advocate chat transcript. */
enum class MessageSender {
    GUEST,
    SUBJECT,
    OPERATOR,
    ADVOCATE;

    companion object {
        fun fromOrNull(raw: String?): MessageSender? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * `advocate_messages/{id}` (LLD §5.1) — append-only transcript rows, indexed `(sessionId,
 * createdAt)` and `(subjectId, createdAt)`.
 */
data class AdvocateMessage(
    val id: String,
    val sessionId: String,
    val subjectId: String,
    val sender: MessageSender,
    val text: String,
    val createdAt: Instant? = null,
    val latencyMs: Long? = null,
    val truncated: Boolean = false,
)

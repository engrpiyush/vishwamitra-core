package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * Who a chat session belongs to — subject/operator chats are sessions too (uniform transcripts).
 */
enum class SessionKind {
    GUEST,
    SUBJECT,
    OPERATOR;

    companion object {
        fun fromOrNull(raw: String?): SessionKind? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * `advocate_sessions/{sessionId}` (LLD §5.1) — `sessionId` is a 128-bit URL-safe random (the cookie
 * value). Guest sessions expire on TTL; SUBJECT/OPERATOR sessions ride the login session (expiresAt
 * null). tokenId + guestEmail complete the F9 accountability tuple for guests.
 */
data class AdvocateSession(
    val sessionId: String,
    val subjectId: String,
    /** The redeemed token's hash/doc id (GUEST sessions only). */
    val tokenId: String? = null,
    val guestEmail: String? = null,
    val kind: SessionKind = SessionKind.GUEST,
    val createdAt: Instant? = null,
    val expiresAt: Instant? = null,
    /** Denormalized cap counter (§7.5). */
    val messageCount: Int = 0,
    val lastActivityAt: Instant? = null,
)

package ai.vishwakarma.labelling.domain

import java.time.Instant

/** Token lifecycle (LLD §6.1): "active/used" is derived from the session, not stored. */
enum class TokenStatus {
    UNREDEEMED,
    REDEEMED,
    REVOKED;

    companion object {
        fun fromOrNull(raw: String?): TokenStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Delivery outcome of the token email (§11.2): FAILED renders "revoke & regenerate". */
enum class TokenEmailStatus {
    SENT,
    FAILED;

    companion object {
        fun fromOrNull(raw: String?): TokenEmailStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * `advocate_tokens/{tokenHash}` (LLD §5.1) — the doc id IS the HMAC hash (§6.2): O(1) redemption
 * lookup, and the raw token never touches disk.
 */
data class AdvocateToken(
    /** The HMAC-SHA256 hash of the raw token — doc id. */
    val tokenHash: String,
    val subjectId: String,
    /** Required at generation (F7) — the delivery address. */
    val guestEmail: String,
    val status: TokenStatus = TokenStatus.UNREDEEMED,
    /** Subject (or operator) who generated it. */
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    /** Set on redemption — one token → one session, ever. */
    val redeemedAt: Instant? = null,
    val sessionId: String? = null,
    val emailStatus: TokenEmailStatus? = null,
)

package ai.vishwakarma.labelling.security

import jakarta.servlet.http.HttpServletRequest

/**
 * A validated guest capability session for the current request (VA-36, LLD §6.4), attached by
 * [GuestSessionFilter] when the `adv_session` cookie resolves to an unexpired GUEST session of this
 * host's subject. The guest is never a Spring Security principal — presence of this attribute IS
 * the capability, and only the subject chain's `/s/chat/` rule reads it.
 */
data class GuestCtx(
    val sessionId: String,
    val guestEmail: String?,
) {
    companion object {
        /** Request attribute key carrying the validated guest session. */
        const val ATTR = "GUEST_CTX"

        /**
         * Marker attribute set when an `adv_session` cookie was presented for THIS subject but the
         * session has expired — the chat entry point turns it into the §6.4 401 `{reason:
         * "session_expired"}` instead of a login redirect.
         */
        const val EXPIRED_ATTR = "GUEST_SESSION_EXPIRED"

        fun of(request: HttpServletRequest): GuestCtx? = request.getAttribute(ATTR) as? GuestCtx
    }
}

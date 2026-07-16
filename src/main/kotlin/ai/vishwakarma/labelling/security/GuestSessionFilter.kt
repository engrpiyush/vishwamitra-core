package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.SessionKind
import ai.vishwakarma.labelling.persistence.AdvocateSessionRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Guest session resolution (VA-36, LLD §6.4), inside the subject chain ahead of authorization:
 * `adv_session` cookie → session row → unexpired GUEST session of THIS host's subject → [GuestCtx]
 * request attribute. Expired-for-this-subject marks [GuestCtx.EXPIRED_ATTR] so chat can answer 401
 * `{reason: "session_expired"}`; a foreign or unknown cookie attaches nothing — the authorization
 * rule then falls through to login-or-wall exactly as if no cookie existed.
 */
class GuestSessionFilter(private val sessions: AdvocateSessionRepository) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val ctx = SubjectCtx.of(request)
        val cookie = request.cookies?.firstOrNull { it.name == COOKIE_NAME }?.value
        if (ctx != null && !cookie.isNullOrBlank()) {
            val session = sessions.find(cookie)
            if (
                session != null &&
                    session.kind == SessionKind.GUEST &&
                    session.subjectId == ctx.subjectId
            ) {
                val expiresAt = session.expiresAt
                if (expiresAt != null && expiresAt.isAfter(Instant.now())) {
                    request.setAttribute(
                        GuestCtx.ATTR,
                        GuestCtx(session.sessionId, session.guestEmail),
                    )
                } else {
                    request.setAttribute(GuestCtx.EXPIRED_ATTR, true)
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        /** The §6.3 capability cookie (HttpOnly, Secure, SameSite=Lax, host-only). */
        const val COOKIE_NAME = "adv_session"
    }
}

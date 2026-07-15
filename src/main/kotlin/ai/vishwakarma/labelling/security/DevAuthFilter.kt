package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.Role
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Dev-profile only: populate the SecurityContext with a fixed user so role-gated features and the
 * principal are exercisable locally without a real Google login. Never registered outside `dev`.
 *
 * VA-29 (LLD §4.6): `?devRole=<ROLE>` switches the injected principal — `?devRole=SUBJECT` becomes
 * the seeded dev subject ([DEV_SUBJECT_EMAIL], bound to [DEV_SUBJECT_ID]); any operator role
 * becomes the configured dev user at that role. The choice is remembered in the HTTP session (the
 * filter re-authenticates every request), so one `?devRole=` sticks until the next one.
 */
class DevAuthFilter(
    private val email: String,
    private val role: Role,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        Role.fromOrNull(request.getParameter("devRole"))?.let {
            request.session.setAttribute(SESSION_ROLE_KEY, it.name)
        }
        val effective =
            Role.fromOrNull(request.getSession(false)?.getAttribute(SESSION_ROLE_KEY) as? String)
                ?: role
        val context = SecurityContextHolder.getContext()
        val existing = context.authentication
        if (existing == null || !existing.isAuthenticated) {
            context.authentication =
                if (effective == Role.SUBJECT) {
                    token(
                        DEV_SUBJECT_EMAIL,
                        listOf(
                            SimpleGrantedAuthority(Role.SUBJECT.authority),
                            SimpleGrantedAuthority(
                                CurrentUser.SUBJECT_ID_AUTHORITY_PREFIX + DEV_SUBJECT_ID
                            ),
                        ),
                    )
                } else {
                    token(email, listOf(SimpleGrantedAuthority(effective.authority)))
                }
        }
        filterChain.doFilter(request, response)
    }

    private fun token(principal: String, authorities: List<GrantedAuthority>) =
        UsernamePasswordAuthenticationToken(principal, "N/A", authorities)

    companion object {
        private const val SESSION_ROLE_KEY = "devRole"

        /** The seeded dev subject identity (DataSeeder creates both rows in dev). */
        const val DEV_SUBJECT_EMAIL = "dev-subject@example.com"
        const val DEV_SUBJECT_ID = "dev-subject"
        const val DEV_SUBJECT_HANDLE = "dev"
    }
}

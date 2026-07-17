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
 *
 * `?devRole=NONE` is the signed-out escape (the VA-43 landing walk): the filter injects nothing,
 * Spring's anonymous filter takes over downstream, and the subject root falls through the §8.3
 * decision tree to the split landing — without it, every dev identity resolves to "self" on the dev
 * host and the landing (plus the guest code walk that starts there) is unreachable. Sticky like any
 * other choice; pass a real `?devRole=` to sign back in.
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
        request.getParameter("devRole")?.let { raw ->
            when {
                raw.equals(NONE, ignoreCase = true) ->
                    request.session.setAttribute(SESSION_ROLE_KEY, NONE)
                Role.fromOrNull(raw) != null ->
                    request.session.setAttribute(SESSION_ROLE_KEY, Role.fromOrNull(raw)!!.name)
                else -> Unit // Unknown values keep the current choice, as before.
            }
        }
        val stored = request.getSession(false)?.getAttribute(SESSION_ROLE_KEY) as? String
        if (stored == NONE) {
            // Signed-out visitor: leave the context untouched (anonymous downstream).
            filterChain.doFilter(request, response)
            return
        }
        val effective = Role.fromOrNull(stored) ?: role
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

        /** The `?devRole=NONE` sentinel — not a [Role]; stored verbatim in the session. */
        private const val NONE = "NONE"

        /** The seeded dev subject identity (DataSeeder creates both rows in dev). */
        const val DEV_SUBJECT_EMAIL = "dev-subject@example.com"
        const val DEV_SUBJECT_ID = "dev-subject"
        const val DEV_SUBJECT_HANDLE = "dev"
    }
}

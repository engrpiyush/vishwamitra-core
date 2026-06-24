package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.Role
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Dev-profile only: populate the SecurityContext with a fixed user so role-gated features and the
 * principal are exercisable locally without a real Google login. Never registered outside `dev`.
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
        val context = SecurityContextHolder.getContext()
        val existing = context.authentication
        if (existing == null || !existing.isAuthenticated) {
            context.authentication =
                UsernamePasswordAuthenticationToken(
                    email,
                    "N/A",
                    listOf(SimpleGrantedAuthority(role.authority)),
                )
        }
        filterChain.doFilter(request, response)
    }
}

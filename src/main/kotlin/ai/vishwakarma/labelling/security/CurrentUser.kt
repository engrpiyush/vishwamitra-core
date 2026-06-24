package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.Role
import org.springframework.security.core.context.SecurityContextHolder

/** Identity helpers backed by the active SecurityContext (works for both OAuth and dev-bypass). */
object CurrentUser {

    fun email(): String? =
        SecurityContextHolder.getContext()
            .authentication
            ?.takeIf { it.isAuthenticated }
            ?.let { auth ->
                when (val p = auth.principal) {
                    is org.springframework.security.oauth2.core.oidc.user.OidcUser -> p.email
                    else -> auth.name
                }
            }

    fun role(): Role? =
        SecurityContextHolder.getContext()
            .authentication
            ?.authorities
            ?.mapNotNull { Role.fromOrNull(it.authority?.removePrefix("ROLE_")) }
            ?.maxByOrNull { it.ordinal }
}

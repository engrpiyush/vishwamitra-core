package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.Role
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/** Identity helpers backed by the active SecurityContext (works for both OAuth and dev-bypass). */
object CurrentUser {

    /**
     * Authority prefix carrying the SUBJECT binding inside the security context (VA-29, LLD §4.2) —
     * set at login, so [subjectId] is a pure context lookup with zero Firestore reads.
     */
    const val SUBJECT_ID_AUTHORITY_PREFIX = "SUBJECT_ID_"

    fun email(): String? =
        SecurityContextHolder.getContext()
            .authentication
            // Anonymous tokens report isAuthenticated == true with name "anonymousUser"; treat a
            // logged-out visitor as having no identity (otherwise "anonymousUser" leaks as an
            // email).
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
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

    /** The bound subject id for a SUBJECT principal (from the login authority); null otherwise. */
    fun subjectId(): String? =
        SecurityContextHolder.getContext()
            .authentication
            ?.authorities
            ?.firstOrNull { it.authority?.startsWith(SUBJECT_ID_AUTHORITY_PREFIX) == true }
            ?.authority
            ?.removePrefix(SUBJECT_ID_AUTHORITY_PREFIX)

    /** True for the operator hierarchy (AUTHOR/REVIEWER/ADMIN); false for SUBJECT and guests. */
    fun isOperator(): Boolean = role()?.let { it != Role.SUBJECT } ?: false
}

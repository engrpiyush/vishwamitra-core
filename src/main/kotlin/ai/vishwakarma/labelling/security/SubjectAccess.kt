package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.Role
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.web.access.intercept.RequestAuthorizationContext

/**
 * The LLD §4.4 SUBJECT-of-this-host check, as a request [AuthorizationManager] so subject-face
 * controllers stay annotation-free: grants any operator (AUTHOR/REVIEWER/ADMIN — raw authorities,
 * the hierarchy needs no expansion since all three are listed) and the SUBJECT whose login-attached
 * `SUBJECT_ID_<id>` authority matches the host's resolved subject. Anonymous → not granted (the
 * entry point sends them to login); an authenticated SUBJECT on someone else's host → 403 ("This
 * isn't your advocate"). Services still re-assert subject scope on every read/write (§12.1).
 */
object SubjectAccess {

    private val OPERATOR_AUTHORITIES =
        setOf(Role.AUTHOR.authority, Role.REVIEWER.authority, Role.ADMIN.authority)

    fun subjectOfHost(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, context ->
            val auth = authentication.get()
            val ctx = SubjectCtx.of(context.request)
            val granted =
                auth != null &&
                    auth.isAuthenticated &&
                    auth !is AnonymousAuthenticationToken &&
                    ctx != null &&
                    auth.authorities.any {
                        it.authority in OPERATOR_AUTHORITIES ||
                            it.authority == CurrentUser.SUBJECT_ID_AUTHORITY_PREFIX + ctx.subjectId
                    }
            AuthorizationDecision(granted)
        }

    /**
     * The `/s/chat/` rule (VA-36, LLD §6.4): everyone [subjectOfHost] grants, plus an anonymous
     * guest whose validated capability session ([GuestCtx], attached by [GuestSessionFilter]) is on
     * the request — the guest stays outside the security context entirely.
     */
    fun subjectOfHostOrGuest(): AuthorizationManager<RequestAuthorizationContext> {
        val base = subjectOfHost()
        return AuthorizationManager { authentication, context ->
            if (GuestCtx.of(context.request) != null) {
                AuthorizationDecision(true)
            } else {
                base.authorize(authentication, context)
            }
        }
    }
}

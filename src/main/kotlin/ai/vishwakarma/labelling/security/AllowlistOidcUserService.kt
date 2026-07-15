package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.service.UserService
import org.springframework.context.annotation.Profile
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component

/**
 * Gate Google sign-in (VA-29, LLD §4.2): allowlist lookup FIRST, then the hosted-domain rule
 * applies only to operator roles — subjects sign in with arbitrary Google accounts that admin
 * pre-created (F1: no self-registration). A SUBJECT login carries its binding as a
 * `SUBJECT_ID_<subjectId>` authority so per-request authorization never touches Firestore; an
 * unbound SUBJECT row is misprovisioned and fails closed. Active only outside the `dev` profile
 * (dev uses the bypass filter instead).
 */
@Component
@Profile("!dev")
class AllowlistOidcUserService(
    private val props: AppProperties,
    private val users: UserService,
) : OidcUserService() {

    override fun loadUser(userRequest: OidcUserRequest): OidcUser {
        val oidcUser = super.loadUser(userRequest)
        val email =
            oidcUser.email?.lowercase()
                ?: deny("no_email", "Google account did not provide an email")

        val user =
            users.activeUser(email)
                ?: deny("not_allowlisted", "$email is not on the access allowlist")

        if (user.role != Role.SUBJECT && !email.endsWith("@${props.hostedDomain}")) {
            deny("domain_not_allowed", "Only @${props.hostedDomain} accounts may sign in")
        }
        if (user.role == Role.SUBJECT && user.subjectId.isNullOrBlank()) {
            deny("subject_unbound", "This account is not linked to a subject")
        }

        val authorities = buildList {
            add(SimpleGrantedAuthority(user.role.authority))
            if (user.role == Role.SUBJECT) {
                add(
                    SimpleGrantedAuthority(CurrentUser.SUBJECT_ID_AUTHORITY_PREFIX + user.subjectId)
                )
            }
        }
        return DefaultOidcUser(authorities, oidcUser.idToken, oidcUser.userInfo, "email")
    }

    private fun deny(code: String, message: String): Nothing =
        throw OAuth2AuthenticationException(OAuth2Error(code, message, null), message)
}

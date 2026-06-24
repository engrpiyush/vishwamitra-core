package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.config.AppProperties
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
 * Gate Google sign-in: require the configured hosted domain AND an active entry in the `users`
 * allowlist, then attach the allowlist role as a granted authority. Anyone else is denied. Active
 * only outside the `dev` profile (dev uses the bypass filter instead).
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

        if (!email.endsWith("@${props.hostedDomain}")) {
            deny("domain_not_allowed", "Only @${props.hostedDomain} accounts may sign in")
        }

        val role =
            users.roleFor(email) ?: deny("not_allowlisted", "$email is not on the access allowlist")

        val authorities = listOf(SimpleGrantedAuthority(role.authority))
        return DefaultOidcUser(authorities, oidcUser.idToken, oidcUser.userInfo, "email")
    }

    private fun deny(code: String, message: String): Nothing =
        throw OAuth2AuthenticationException(OAuth2Error(code, message, null), message)
}

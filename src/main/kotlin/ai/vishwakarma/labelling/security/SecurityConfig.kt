package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Role
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

/**
 * Two mutually-exclusive filter chains by profile:
 * - `dev` : OAuth bypassed; [DevAuthFilter] injects a fixed user so roles are exercisable locally.
 * - others : Google OAuth2 login gated by [AllowlistOidcUserService]; admin area requires ADMIN.
 *
 * Role hierarchy ADMIN ⊃ REVIEWER ⊃ AUTHOR applies to both web and method security.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun roleHierarchy(): RoleHierarchy =
        RoleHierarchyImpl.withDefaultRolePrefix()
            .role("ADMIN")
            .implies("REVIEWER")
            .role("REVIEWER")
            .implies("AUTHOR")
            .build()

    @Bean
    fun methodSecurityExpressionHandler(
        roleHierarchy: RoleHierarchy
    ): DefaultMethodSecurityExpressionHandler =
        DefaultMethodSecurityExpressionHandler().apply { setRoleHierarchy(roleHierarchy) }

    @Bean
    @Profile("dev")
    fun devSecurityFilterChain(http: HttpSecurity, props: AppProperties): SecurityFilterChain {
        val devUser =
            DevAuthFilter(
                email = props.auth.devUser.email,
                role = Role.fromOrNull(props.auth.devUser.role) ?: Role.ADMIN,
            )
        http {
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            csrf { disable() }
            formLogin { disable() }
            httpBasic { disable() }
        }
        // Must run before the anonymous filter, otherwise an authenticated anonymous token wins.
        http.addFilterBefore(devUser, AnonymousAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    @Profile("!dev")
    fun securityFilterChain(
        http: HttpSecurity,
        allowlistOidcUserService: OidcUserService
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/css/**", permitAll)
                authorize("/js/**", permitAll)
                authorize("/webjars/**", permitAll)
                authorize("/login/**", permitAll)
                authorize("/error", permitAll)
                authorize("/admin/**", hasRole("ADMIN"))
                authorize(anyRequest, authenticated)
            }
            oauth2Login { userInfoEndpoint { oidcUserService = allowlistOidcUserService } }
            logout { logoutSuccessUrl = "/" }
        }
        return http.build()
    }
}

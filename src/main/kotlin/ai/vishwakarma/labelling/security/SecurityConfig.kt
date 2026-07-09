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
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

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

    /**
     * Restricts cross-site calls to the subscribe API to vishwakarma.ai origins. The page is served
     * same-origin so it is unaffected; this blocks other sites' scripts from posting.
     */
    @Bean
    fun corsConfigurationSource(props: AppProperties): CorsConfigurationSource {
        val config =
            CorsConfiguration().apply {
                allowedOriginPatterns = props.comingSoon.corsOrigins
                allowedMethods = listOf("GET", "POST", "OPTIONS")
                allowedHeaders = listOf("Content-Type", "Accept")
                maxAge = 3600
            }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/coming-soon/**", config)
        }
    }

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
            // Without this, logout falls back to Spring's default "/login?logout", which 404s
            // (there is no login page in dev). DevAuthFilter re-authenticates on the next request,
            // so "/" immediately bounces back to /home.
            logout { logoutSuccessUrl = "/" }
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
                authorize("/favicon.svg", permitAll)
                authorize("/webjars/**", permitAll)
                authorize("/login/**", permitAll)
                authorize("/", permitAll)
                authorize("/error", permitAll)
                // Public coming-soon page + its subscribe API.
                authorize("/coming-soon.html", permitAll)
                authorize("/coming-soon/**", permitAll)
                authorize("/admin/**", hasRole("ADMIN"))
                // Stage 1 intake: the server-rendered UI and its JSON API are both REVIEWER+ (also
                // enforced at the method level by @PreAuthorize). CSRF stays on — forms carry the
                // hidden token, and the uploader JS sends it from the <meta> as X-CSRF-TOKEN.
                authorize("/intake/**", hasRole("REVIEWER"))
                authorize("/api/intake/**", hasRole("REVIEWER"))
                // Stage 2 (A/V → Claims) JSON API: REVIEWER+, CSRF on, same conventions as intake.
                authorize("/api/stage2/**", hasRole("REVIEWER"))
                // Stage 3 (Claims → Authenticity scores) JSON API: same conventions.
                authorize("/api/stage3/**", hasRole("REVIEWER"))
                authorize(anyRequest, authenticated)
            }
            // Subscribe API is locked to vishwakarma.ai origins (see corsConfigurationSource).
            cors {}
            // The subscribe endpoint is unauthenticated JSON (no session to protect) and is already
            // origin-restricted by CORS, so exempt it from CSRF — the page sends no CSRF token.
            csrf { ignoringRequestMatchers("/coming-soon/**") }
            // The root "/" landing page is the OAuth entry point: unauthenticated requests redirect
            // here (instead of straight to Google); its CTA initiates /oauth2/authorization/google.
            // On success, land on /home — unless a deep-linked protected page was saved first.
            oauth2Login {
                loginPage = "/"
                defaultSuccessUrl("/home", false)
                userInfoEndpoint { oidcUserService = allowlistOidcUserService }
            }
            logout { logoutSuccessUrl = "/" }
        }
        return http.build()
    }
}

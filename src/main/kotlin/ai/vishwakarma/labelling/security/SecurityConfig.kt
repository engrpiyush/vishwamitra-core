package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.persistence.AdvocateSessionRepository
import ai.vishwakarma.labelling.service.SubjectDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Host-split security (VA-30, LLD §4.4): [SubjectHostFilter] runs ahead of every chain and stamps
 * subject-host requests with [SubjectCtx] (and auth-host requests with [AuthHost.ATTR]); the chains
 * then split:
 * - auth-host chain (order 1, VA-70) — the central OAuth callback host `auth.{base-domain}`.
 * - subject chain (order 2) — claims any request carrying the ctx attribute, on BOTH profiles, so
 *   dev exercises the real subject authorization table (`<handle>.localhost:8080`).
 * - operator chains (order 3) — exactly the pre-VA-30 posture: `dev` = OAuth bypassed via
 *   [DevAuthFilter]; others = Google OAuth2 login gated by [AllowlistOidcUserService].
 *
 * Role hierarchy ADMIN ⊃ REVIEWER ⊃ AUTHOR applies to both web and method security; SUBJECT sits
 * outside it (VA-29).
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

    /**
     * Host-first routing must run before the Spring Security proxy so the chains' securityMatcher
     * can read the [SubjectCtx] attribute it attaches.
     */
    @Bean
    fun subjectHostFilter(
        props: AppProperties,
        directory: SubjectDirectory,
    ): FilterRegistrationBean<SubjectHostFilter> =
        FilterRegistrationBean(SubjectHostFilter(props, directory)).apply {
            order = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10
        }

    /**
     * The `/internal` endpoints (VA-41, LLD §3.4): Cloud Scheduler's OIDC identity only — verified
     * by [InternalOidcFilter], not by session auth, so the chain itself permits and the filter
     * gates. CSRF off (machine-to-machine POSTs carry no session or token). NB: never put a glob
     * pattern inside a block comment — Kotlin block comments NEST, so a literal slash-star inside
     * one unbalances the file; globs belong in code strings or line comments.
     */
    @Bean
    @Order(0)
    fun internalSecurityFilterChain(http: HttpSecurity, props: AppProperties): SecurityFilterChain {
        http.securityMatcher("/internal/**")
        http {
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            csrf { disable() }
        }
        http.addFilterBefore(InternalOidcFilter(props), AnonymousAuthenticationFilter::class.java)
        return http.build()
    }

    /**
     * The central OAuth callback host (VA-70, LLD §4.2 v1.1): `auth.{base-domain}` runs the whole
     * Google dance on the one registered redirect URI. [SubjectHostFilter] stamps the marker
     * attribute and already 404'd everything but the login machinery; login success redirects back
     * to the open-redirect-guarded target `/auth/start` parked in the session ([AuthHost]). The
     * session cookie is parent-domain in prod (application.yml), so the session minted here is
     * honored on every subject host — authorization stays per-host (§4.4).
     */
    @Bean
    @Order(1)
    fun authHostSecurityFilterChain(
        http: HttpSecurity,
        props: AppProperties,
        clientRegistrations: ObjectProvider<ClientRegistrationRepository>,
        allowlistOidcUserService: ObjectProvider<OidcUserService>,
    ): SecurityFilterChain {
        http.securityMatcher(RequestMatcher { it.getAttribute(AuthHost.ATTR) != null })
        http {
            authorizeHttpRequests {
                authorize("/css/**", permitAll)
                authorize("/js/**", permitAll)
                authorize("/favicon.svg", permitAll)
                authorize("/error", permitAll)
                authorize("/auth/start", permitAll)
                authorize("/login/**", permitAll)
                authorize("/oauth2/**", permitAll)
                authorize(anyRequest, denyAll)
            }
            if (clientRegistrations.ifAvailable != null) {
                oauth2Login {
                    authenticationSuccessHandler = AuthHost.successHandler(props.product.baseDomain)
                    allowlistOidcUserService.ifAvailable?.let { svc ->
                        userInfoEndpoint { oidcUserService = svc }
                    }
                }
            }
        }
        return http.build()
    }

    /**
     * The subject world (LLD §4.4 authorization table). Paths arrive rewritten under `/s` (see
     * [SubjectHostFilter]); routes outside the table were already 404'd by the filter, so the
     * denyAll tail is belt-and-braces. Active on both profiles — in dev, [DevAuthFilter] supplies
     * the principal (`?devRole=SUBJECT` etc.) and the same rules apply.
     */
    @Bean
    @Order(2)
    fun subjectSecurityFilterChain(
        http: HttpSecurity,
        props: AppProperties,
        advocateSessions: AdvocateSessionRepository,
        clientRegistrations: ObjectProvider<ClientRegistrationRepository>,
        allowlistOidcUserService: ObjectProvider<OidcUserService>,
    ): SecurityFilterChain {
        http.securityMatcher(RequestMatcher { it.getAttribute(SubjectCtx.ATTR) != null })
        val subjectOfHost = SubjectAccess.subjectOfHost()
        // LLD §4.4: authenticated-but-wrong-subject → 403 "This isn't your advocate."
        // (templates/error/403.html carries the friendly copy); anonymous → login entry point.
        val denied = AccessDeniedHandler { _, response, _ ->
            if (!response.isCommitted) response.sendError(403, "This isn't your advocate.")
        }
        // §6.4: an expired guest cookie on chat answers 401 {reason: session_expired} — the chat
        // JS renders "ask for a new access code" instead of bouncing a guest to operator login.
        val sessionExpired = AuthenticationEntryPoint { _, response, _ ->
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json"
            response.writer.write("""{"reason":"session_expired"}""")
        }
        val expiredChatMatcher = RequestMatcher { request ->
            request.getAttribute(GuestCtx.EXPIRED_ATTR) != null &&
                (request.requestURI == "/s/chat" || request.requestURI.startsWith("/s/chat/"))
        }
        val oauthAvailable = clientRegistrations.ifAvailable != null
        http {
            authorizeHttpRequests {
                authorize("/css/**", permitAll)
                authorize("/js/**", permitAll)
                authorize("/webjars/**", permitAll)
                authorize("/favicon.svg", permitAll)
                authorize("/error", permitAll)
                authorize("/logout", permitAll)
                authorize("/login/**", permitAll)
                authorize("/oauth2/**", permitAll)
                // Root renders chat / split landing by state (§8.3); the wall is its own
                // rate-limited gate (§6.3).
                authorize("/s", permitAll)
                authorize("/s/wall/**", permitAll)
                authorize("/s/terms/**", authenticated)
                // VA-36 (§6.4): operator ∨ subject-of-host ∨ validated guest capability session.
                authorize("/s/chat/**", SubjectAccess.subjectOfHostOrGuest())
                authorize("/s/training/**", subjectOfHost)
                authorize("/s/tokens/**", subjectOfHost)
                authorize("/s/provisioning/**", subjectOfHost)
                authorize("/s/questions/**", subjectOfHost)
                authorize(anyRequest, denyAll)
            }
            exceptionHandling {
                accessDeniedHandler = denied
                defaultAuthenticationEntryPointFor(sessionExpired, expiredChatMatcher)
                if (oauthAvailable) {
                    // VA-70 (§4.2 v1.1): per-subject-host redirect URIs can't be registered on the
                    // Google client, so login bounces through the central auth host, carrying the
                    // originating URL. Registered as a default-for(AnyRequest) so the chat 401
                    // mapping above keeps precedence.
                    defaultAuthenticationEntryPointFor(
                        AuthHost.entryPoint(props.product.baseDomain),
                        AnyRequestMatcher.INSTANCE,
                    )
                } else {
                    defaultAuthenticationEntryPointFor(
                        HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        AnyRequestMatcher.INSTANCE,
                    )
                }
            }
            if (oauthAvailable) {
                oauth2Login {
                    defaultSuccessUrl("/", false)
                    allowlistOidcUserService.ifAvailable?.let { svc ->
                        userInfoEndpoint { oidcUserService = svc }
                    }
                }
            }
            logout { logoutSuccessUrl = "/" }
        }
        if (props.auth.devBypass) {
            http.addFilterBefore(devAuthFilter(props), AnonymousAuthenticationFilter::class.java)
        }
        // Guest resolution rides inside the chain, just ahead of authorization (§6.4).
        http.addFilterBefore(GuestSessionFilter(advocateSessions), AuthorizationFilter::class.java)
        return http.build()
    }

    @Bean
    @Order(3)
    @Profile("dev")
    fun devSecurityFilterChain(http: HttpSecurity, props: AppProperties): SecurityFilterChain {
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
        http.addFilterBefore(devAuthFilter(props), AnonymousAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    @Order(3)
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
                authorize("/vishwakarma-ai-landing.html", permitAll)
                authorize("/coming-soon/**", permitAll)
                // Public data-collection guide (static pages, read before gathering data).
                authorize("/user-guide/**", permitAll)
                authorize("/admin/**", hasRole("ADMIN"))
                // Operator diagnostics JSON (server posture / dry-run flags) — ADMIN like the
                // pages above; also enforced at the method level by @PreAuthorize.
                authorize("/api/admin/**", hasRole("ADMIN"))
                // Stage 1 intake: the server-rendered UI and its JSON API are both REVIEWER+ (also
                // enforced at the method level by @PreAuthorize). CSRF stays on — forms carry the
                // hidden token, and the uploader JS sends it from the <meta> as X-CSRF-TOKEN.
                authorize("/intake/**", hasRole("REVIEWER"))
                authorize("/api/intake/**", hasRole("REVIEWER"))
                // Stage 2 (A/V → Claims) JSON API: REVIEWER+, CSRF on, same conventions as intake.
                authorize("/api/stage2/**", hasRole("REVIEWER"))
                // Stage 3 (Claims → Authenticity scores) JSON API: same conventions.
                authorize("/api/stage3/**", hasRole("REVIEWER"))
                // VA-69: the models registry (and the future Advocates panel, VA-40) is
                // REVIEWER+ — URL rule pairing the class-level @PreAuthorize on ModelsController.
                authorize("/models/**", hasRole("REVIEWER"))
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

    private fun devAuthFilter(props: AppProperties): DevAuthFilter =
        DevAuthFilter(
            email = props.auth.devUser.email,
            role = Role.fromOrNull(props.auth.devUser.role) ?: Role.ADMIN,
        )
}

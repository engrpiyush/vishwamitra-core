package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.config.AppProperties
import com.google.auth.oauth2.TokenVerifier
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Guards the `/internal` endpoints (VA-41, LLD §3.4): Cloud Scheduler jobs call with an OIDC bearer
 * token minted for their dedicated service account; Cloud Run IAM does the heavy lifting, and this
 * filter re-verifies in-app — Google-signed token, the configured audience, and (when pinned) the
 * invoker's service-account email. Dev profile bypasses (no scheduler locally); outside dev a blank
 * audience fails closed.
 */
class InternalOidcFilter(private val props: AppProperties) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** One verifier per configured audience — TokenVerifier caches Google's JWKs internally. */
    private val verifier: TokenVerifier? by lazy {
        props.product.internalAudience
            .takeIf { it.isNotBlank() }
            ?.let {
                TokenVerifier.newBuilder()
                    .setAudience(it)
                    .setIssuer("https://accounts.google.com")
                    .build()
            }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (props.auth.devBypass) {
            filterChain.doFilter(request, response)
            return
        }
        val tokenVerifier = verifier
        if (tokenVerifier == null) {
            log.warn("/internal call rejected: app.product.internal-audience is not configured")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }
        val token =
            request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf {
                it.isNotBlank()
            }
        if (token == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }
        val email =
            try {
                tokenVerifier.verify(token).payload["email"] as? String
            } catch (e: Exception) {
                log.warn("/internal OIDC verification failed: {}", e.message)
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                return
            }
        val invoker = props.product.internalInvoker
        if (invoker.isNotBlank() && !invoker.equals(email, ignoreCase = true)) {
            log.warn("/internal call from unexpected identity {}", email)
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        filterChain.doFilter(request, response)
    }
}

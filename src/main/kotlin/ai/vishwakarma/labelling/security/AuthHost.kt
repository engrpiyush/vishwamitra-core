package ai.vishwakarma.labelling.security

import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.AuthenticationSuccessHandler

/**
 * The central OAuth callback host (VA-70, product LLD §4.2 v1.1). Google OAuth clients require
 * exact pre-registered redirect URIs, so per-subject-host logins can't go live — instead the whole
 * dance runs on the single registered host `auth.{base-domain}`:
 * - the subject chain's [entryPoint] bounces an unauthenticated request there, carrying the
 *   originating URL as a `continue` param;
 * - `/auth/start` (web/AuthHostController) validates the target ([safeReturnTarget] — the
 *   open-redirect guard) into the session, then starts the standard authorization flow;
 * - the auth-host chain's [successHandler] redirects back to the validated target.
 *
 * The login session cookie is issued with `Domain=.{base-domain}` under the prod profile
 * (application.yml), so the session minted here is honored on every subject host; authorization
 * stays per-host — the §4.4 SUBJECT_ID-of-host check still 403s the wrong host.
 */
object AuthHost {

    /** Request attribute marking an auth-host request (stamped by SubjectHostFilter). */
    const val ATTR = "AUTH_HOST"

    /** Session attribute holding the validated post-login redirect target. */
    const val RETURN_TO = "authHostReturnTo"

    fun of(request: HttpServletRequest): Boolean = request.getAttribute(ATTR) != null

    /** The auth host for a base domain — a reserved handle (§17.2), so no subject can claim it. */
    fun hostFor(baseDomain: String): String = "auth.${baseDomain.lowercase()}"

    /**
     * The open-redirect guard: a target survives only as an absolute http(s) URL whose host is the
     * base domain itself or one label under it. Anything else — other schemes, other hosts,
     * suffix-alike hosts ("evilvishwakarma.ai"), userinfo tricks — returns null.
     */
    fun safeReturnTarget(raw: String?, baseDomain: String): String? {
        val candidate = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val base = baseDomain.lowercase()
        if (host != base && !host.endsWith(".$base")) return null
        return candidate
    }

    /**
     * Subject-chain authentication entry point: 302 to the auth host's `/auth/start` with the
     * originating external URL (SubjectHostFilter keeps getRequestURL pretty) as `continue`.
     */
    fun entryPoint(baseDomain: String): AuthenticationEntryPoint =
        AuthenticationEntryPoint { request, response, _ ->
            val original =
                request.requestURL.toString() + (request.queryString?.let { "?$it" } ?: "")
            val encoded = URLEncoder.encode(original, StandardCharsets.UTF_8)
            response.sendRedirect("https://${hostFor(baseDomain)}/auth/start?continue=$encoded")
        }

    /**
     * Auth-host login success: redirect back to the validated target saved by `/auth/start`
     * (re-validated — the session value is ours, but stay paranoid about redirect targets), or to
     * the apex landing when none was carried.
     */
    fun successHandler(baseDomain: String): AuthenticationSuccessHandler =
        AuthenticationSuccessHandler { request, response, _ ->
            val saved = request.session.getAttribute(RETURN_TO) as? String
            request.session.removeAttribute(RETURN_TO)
            val target = safeReturnTarget(saved, baseDomain) ?: "https://${baseDomain.lowercase()}"
            response.sendRedirect(target)
        }
}

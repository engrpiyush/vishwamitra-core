package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.service.SubjectDirectory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Host-first routing (VA-30, LLD §2.2 / §4.3), registered ahead of both security chains:
 * - the operator host (or any host outside `*.{base-domain}`) passes through untouched;
 * - `<handle>.{base-domain}` resolves the handle via [SubjectDirectory] (60s cache); unknown or
 *   non-ACTIVE handles 404 — deliberately indistinguishable from a nonexistent site. Reserved
 *   handles never resolve because creation-time validation refuses them (the dev-profile seeded
 *   handle `dev` is the sanctioned exception — see DataSeeder);
 * - `auth.{base-domain}` (VA-70, LLD §4.2 v1.1) is neither a subject host nor the operator domain:
 *   it gets [AuthHost.ATTR] stamped (its own security chain matches on it) and serves ONLY the
 *   OAuth login/callback machinery plus its `/auth/start` front door — everything else 404s;
 * - a resolved host gets [SubjectCtx] attached, and its app path is REWRITTEN under the internal
 *   `/s` prefix (`/training` → `/s/training`) before security + MVC. The subject world therefore
 *   owns disjoint controller mappings: operator URLs have no mapping on subject hosts (404 here),
 *   and paths that exist in both worlds (`/training`) never share a controller. Shared infra
 *   (static assets, `/error`, login machinery, `/logout`) is not rewritten;
 * - anything outside the subject-world route set 404s here — "no mapping exists in the subject
 *   world" (§4.4) made literal.
 */
class SubjectHostFilter(
    private val props: AppProperties,
    private val directory: SubjectDirectory,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val host = request.serverName.lowercase()
        val suffix = "." + props.product.baseDomain.lowercase()
        if (host == props.product.operatorDomain.lowercase() || !host.endsWith(suffix)) {
            filterChain.doFilter(request, response)
            return
        }
        val handle = host.removeSuffix(suffix)
        if (handle.isBlank() || handle.contains('.')) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        // §8.4: www is not a subject host — permanent redirect to the canonical apex (VA-71
        // design).
        if (handle == "www") {
            response.status = HttpServletResponse.SC_MOVED_PERMANENTLY
            response.setHeader(
                "Location",
                "https://" +
                    props.product.baseDomain.lowercase() +
                    request.requestURI +
                    (request.queryString?.let { "?$it" } ?: ""),
            )
            return
        }
        // VA-70: the central OAuth callback host — login machinery only, never a subject site.
        if (handle == AUTH_HANDLE) {
            request.setAttribute(AuthHost.ATTR, true)
            val path = request.requestURI
            if (path in AUTH_EXACT || AUTH_PREFIXES.any { path.startsWith(it) }) {
                filterChain.doFilter(request, response)
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND)
            }
            return
        }
        val subject = directory.activeByHandle(handle)
        if (subject == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        request.setAttribute(SubjectCtx.ATTR, SubjectCtx(subject.id, handle, subject.displayName))

        val path = request.requestURI
        when {
            path in SHARED_EXACT || SHARED_PREFIXES.any { path.startsWith(it) } ->
                filterChain.doFilter(request, response)
            // Already-internal paths (e.g. a saved-request echo) pass only for real subject
            // segments — probes to /s/* must 404 exactly like any other unknown route.
            path == "/s" -> filterChain.doFilter(request, response)
            path.startsWith("/s/") ->
                if (firstSegment(path.removePrefix("/s")) in SUBJECT_SEGMENTS) {
                    filterChain.doFilter(request, response)
                } else {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND)
                }
            path == "/" -> filterChain.doFilter(rewritten(request, "/s"), response)
            firstSegment(path) in SUBJECT_SEGMENTS ->
                filterChain.doFilter(rewritten(request, "/s$path"), response)
            else -> response.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }

    private fun firstSegment(path: String): String = path.removePrefix("/").substringBefore('/')

    /**
     * Downstream (security chain + MVC) sees the internal path; getRequestURL stays the pretty
     * external URL, so saved-request redirects land back on `https://<handle>.…/training`.
     */
    private fun rewritten(request: HttpServletRequest, newPath: String): HttpServletRequest =
        object : HttpServletRequestWrapper(request) {
            override fun getRequestURI(): String = newPath

            override fun getServletPath(): String = newPath
        }

    companion object {
        /** First path segments that exist in the subject world (LLD §4.4 + §8.1). */
        val SUBJECT_SEGMENTS =
            setOf("wall", "chat", "training", "tokens", "provisioning", "questions", "terms")

        private val SHARED_EXACT = setOf("/favicon.svg", "/error", "/logout")
        private val SHARED_PREFIXES = listOf("/css/", "/js/", "/webjars/", "/login", "/oauth2/")

        /** VA-70: the reserved first label of the central OAuth callback host (§17.2). */
        private const val AUTH_HANDLE = "auth"

        private val AUTH_EXACT = setOf("/auth/start", "/favicon.svg", "/error")
        private val AUTH_PREFIXES = listOf("/css/", "/js/", "/login", "/oauth2/")
    }
}

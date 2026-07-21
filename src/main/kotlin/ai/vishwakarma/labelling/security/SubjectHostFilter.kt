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
 * - the apex `{base-domain}` (VA-71, LLD §8.4) is the public front door: [PublicHost.ATTR] gets
 *   stamped and the path rewritten under the internal `/p` prefix — the world is the landing plus
 *   the §13.3 policy pages, everything else 404s. `www.{base-domain}` 301s to the apex. Dev
 *   collapse: base == operator (`localhost`), so the operator app keeps the bare host and `www`
 *   SERVES the public world instead of redirecting (`www.localhost:8080` is the dev door);
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
        val base = props.product.baseDomain.lowercase()
        val operator = props.product.operatorDomain.lowercase()
        val suffix = ".$base"
        // §8.4 (VA-71): the apex is the public front door. In the dev collapse (base == operator,
        // `localhost`) the operator app keeps the bare host and the public world moves to www.
        if (host == base && base != operator) {
            servePublic(request, response, filterChain)
            return
        }
        if (host == "www.$base") {
            if (base == operator) {
                // Dev collapse: a www → apex redirect would land on the operator app, so
                // `www.localhost:8080` serves the public world directly.
                servePublic(request, response, filterChain)
            } else {
                // §8.4: www is not a subject host — permanent redirect to the canonical apex.
                response.status = HttpServletResponse.SC_MOVED_PERMANENTLY
                response.setHeader(
                    "Location",
                    "https://" +
                        base +
                        request.requestURI +
                        (request.queryString?.let { "?$it" } ?: ""),
                )
            }
            return
        }
        if (host == operator || !host.endsWith(suffix)) {
            filterChain.doFilter(request, response)
            return
        }
        val handle = host.removeSuffix(suffix)
        if (handle.isBlank() || handle.contains('.')) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
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

    /**
     * §8.4: the public-apex world — stamp the marker, rewrite onto the internal `/p` prefix, 404
     * anything that isn't the landing (the brand page), `/construct` (the product front door), the
     * VA-173 two-door catalog (`/individuals`, `/business` and its deeper product pages such as
     * `/business/sales-rep`), a policy page, the data-collection guide or a shared static asset. No
     * login machinery, no logout, no subject or operator route exists here.
     */
    private fun servePublic(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        request.setAttribute(PublicHost.ATTR, true)
        val path = request.requestURI
        when {
            path in PUBLIC_EXACT || PUBLIC_PREFIXES.any { path.startsWith(it) } ->
                filterChain.doFilter(request, response)
            path == "/" -> filterChain.doFilter(rewritten(request, "/p"), response)
            path == "/construct" ->
                filterChain.doFilter(rewritten(request, "/p/construct"), response)
            // VA-173: the two-door catalog world — the individuals door and the businesses door
            // (plus the businesses catalog's deeper product pages, e.g. /business/sales-rep).
            path == "/individuals" ->
                filterChain.doFilter(rewritten(request, "/p/individuals"), response)
            path == "/business" || path.startsWith("/business/") ->
                filterChain.doFilter(rewritten(request, "/p$path"), response)
            path.startsWith("/policies/") ->
                filterChain.doFilter(rewritten(request, "/p$path"), response)
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
            setOf(
                "wall",
                "chat",
                "training",
                "tokens",
                "provisioning",
                "questions",
                "terms",
                "signin",
                "policies"
            )

        private val SHARED_EXACT = setOf("/favicon.svg", "/error", "/logout")
        private val SHARED_PREFIXES = listOf("/css/", "/js/", "/webjars/", "/login", "/oauth2/")

        /** VA-70: the reserved first label of the central OAuth callback host (§17.2). */
        private const val AUTH_HANDLE = "auth"

        private val AUTH_EXACT = setOf("/auth/start", "/favicon.svg", "/error")
        private val AUTH_PREFIXES = listOf("/css/", "/js/", "/login", "/oauth2/")

        /**
         * VA-71: what exists on the apex besides the rewritten landing + policy pages. The
         * data-collection guide joined at the matrix-landing round — the apex footer links it.
         */
        private val PUBLIC_EXACT = setOf("/favicon.svg", "/error")
        private val PUBLIC_PREFIXES = listOf("/css/", "/js/", "/webjars/", "/user-guide/")
    }
}

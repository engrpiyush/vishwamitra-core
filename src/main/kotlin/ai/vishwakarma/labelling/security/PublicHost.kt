package ai.vishwakarma.labelling.security

import jakarta.servlet.http.HttpServletRequest

/**
 * The public-apex world (VA-71, product LLD §8.4): `{base-domain}` itself (plus `www.`, which 301s
 * to it) serves the product front door — the landing page and the §13.3 policy pages, nothing else.
 * [SubjectHostFilter] stamps the marker and rewrites apex paths under the internal `/p` prefix; the
 * public security chain matches on the attribute. No operator or subject route exists on the apex
 * host, and the world is cookieless (stateless chain, no CSRF, no login).
 */
object PublicHost {

    /** Request attribute marking a public-apex request (stamped by SubjectHostFilter). */
    const val ATTR = "PUBLIC_HOST"

    fun of(request: HttpServletRequest): Boolean = request.getAttribute(ATTR) != null
}

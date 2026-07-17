package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.service.SubjectDirectory
import com.google.cloud.firestore.Firestore
import jakarta.servlet.http.HttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

private class FakeHostSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, Subject>()

    override fun findByHandle(handle: String): Subject? =
        store.values.firstOrNull { it.handle == handle }
}

class SubjectHostFilterTest {

    private val repo =
        FakeHostSubjectRepo().apply {
            store["subj-1"] = Subject(id = "subj-1", displayName = "Dev Subject", handle = "dev")
            store["subj-2"] =
                Subject(
                    id = "subj-2",
                    displayName = "Gone",
                    handle = "archived",
                    status = SubjectStatus.ARCHIVED,
                )
        }
    // Dev-profile posture: base-domain localhost, operator host localhost.
    private val props =
        AppProperties(
            product = AppProperties.Product(baseDomain = "localhost", operatorDomain = "localhost")
        )
    private val filter = SubjectHostFilter(props, SubjectDirectory(repo))

    private fun run(host: String, path: String): Triple<Int, HttpServletRequest?, SubjectCtx?> {
        val request = MockHttpServletRequest("GET", path).apply { serverName = host }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        val forwarded = chain.request as? HttpServletRequest
        return Triple(response.status, forwarded, SubjectCtx.of(request))
    }

    @Test
    fun `operator host passes through untouched`() {
        val (status, forwarded, ctx) = run("localhost", "/admin/users")
        assertEquals(200, status)
        assertEquals("/admin/users", forwarded!!.requestURI)
        assertNull(ctx)
    }

    @Test
    fun `subject host rewrites app paths under the internal prefix`() {
        val (status, forwarded, ctx) = run("dev.localhost", "/training")
        assertEquals(200, status)
        assertEquals("/s/training", forwarded!!.requestURI)
        assertEquals("subj-1", ctx!!.subjectId)
        assertEquals("dev", ctx.handle)
    }

    @Test
    fun `subject host root rewrites to the internal root`() {
        val (_, forwarded, _) = run("dev.localhost", "/")
        assertEquals("/s", forwarded!!.requestURI)
    }

    @Test
    fun `shared assets are not rewritten`() {
        val (_, forwarded, ctx) = run("dev.localhost", "/css/app.css")
        assertEquals("/css/app.css", forwarded!!.requestURI)
        assertNotNull(ctx)
    }

    @Test
    fun `operator routes 404 on subject hosts`() {
        assertEquals(404, run("dev.localhost", "/admin").first)
        assertEquals(404, run("dev.localhost", "/home").first)
        assertEquals(404, run("dev.localhost", "/api/intake/subjects").first)
    }

    @Test
    fun `unknown and archived handles 404 identically`() {
        assertEquals(404, run("nobody.localhost", "/training").first)
        assertEquals(404, run("archived.localhost", "/training").first)
    }

    @Test
    fun `internal prefix probes 404 unless the segment is real`() {
        assertEquals(404, run("dev.localhost", "/s/secret").first)
        val (status, forwarded, _) = run("dev.localhost", "/s/training")
        assertEquals(200, status)
        assertEquals("/s/training", forwarded!!.requestURI)
    }

    @Test
    fun `multi-label subdomains 404`() {
        assertEquals(404, run("a.b.localhost", "/").first)
    }

    // ---- VA-70: the central OAuth callback host ------------------------------

    @Test
    fun `auth host serves only the login machinery`() {
        listOf(
                "/auth/start",
                "/oauth2/authorization/google",
                "/login/oauth2/code/google",
                "/error",
                "/css/app.css",
            )
            .forEach { path ->
                val (status, forwarded, ctx) = run("auth.localhost", path)
                assertEquals(200, status, "expected $path to pass on the auth host")
                assertEquals(path, forwarded!!.requestURI)
                assertNull(ctx, "the auth host is not a subject host")
            }
    }

    @Test
    fun `everything else 404s on the auth host`() {
        listOf("/", "/training", "/s/training", "/admin", "/wall").forEach { path ->
            assertEquals(404, run("auth.localhost", path).first, "expected $path to 404")
        }
    }

    @Test
    fun `auth host requests carry the marker attribute for the chain matcher`() {
        val request =
            MockHttpServletRequest("GET", "/auth/start").apply { serverName = "auth.localhost" }
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
        assertNotNull(request.getAttribute(AuthHost.ATTR))
    }

    // ---- VA-71: the public-apex world (LLD §8.4) -----------------------------

    /** Prod shape: apex ≠ operator, so the apex serves the public world and www redirects. */
    private val prodFilter =
        SubjectHostFilter(
            AppProperties(
                product =
                    AppProperties.Product(
                        baseDomain = "vishwakarma.ai",
                        operatorDomain = "labelling.vishwakarma.ai",
                    )
            ),
            SubjectDirectory(repo),
        )

    private fun runProd(host: String, path: String): Triple<Int, HttpServletRequest?, Boolean> {
        val request = MockHttpServletRequest("GET", path).apply { serverName = host }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        prodFilter.doFilter(request, response, chain)
        return Triple(response.status, chain.request as? HttpServletRequest, PublicHost.of(request))
    }

    @Test
    fun `apex serves the public world rewritten under the internal prefix`() {
        val (status, forwarded, public) = runProd("vishwakarma.ai", "/")
        assertEquals(200, status)
        assertEquals("/p", forwarded!!.requestURI)
        assertEquals(true, public)

        val (_, policyForwarded, _) = runProd("vishwakarma.ai", "/policies/privacy")
        assertEquals("/p/policies/privacy", policyForwarded!!.requestURI)
    }

    @Test
    fun `apex shares static assets unrewritten and 404s everything else`() {
        val (status, forwarded, _) = runProd("vishwakarma.ai", "/css/app.css")
        assertEquals(200, status)
        assertEquals("/css/app.css", forwarded!!.requestURI)
        // No subject, operator, chat or internal-prefix route exists on the apex host.
        listOf("/training", "/admin", "/chat", "/wall", "/s/training", "/p", "/login").forEach {
            assertEquals(404, runProd("vishwakarma.ai", it).first, "expected $it to 404 on apex")
        }
    }

    @Test
    fun `www permanently redirects to the apex in prod shape`() {
        val request =
            MockHttpServletRequest("GET", "/policies/terms").apply {
                serverName = "www.vishwakarma.ai"
                queryString = "q=1"
            }
        val response = MockHttpServletResponse()
        prodFilter.doFilter(request, response, MockFilterChain())
        assertEquals(301, response.status)
        assertEquals("https://vishwakarma.ai/policies/terms?q=1", response.getHeader("Location"))
    }

    @Test
    fun `prod operator host still passes through untouched`() {
        val (status, forwarded, public) = runProd("labelling.vishwakarma.ai", "/admin/users")
        assertEquals(200, status)
        assertEquals("/admin/users", forwarded!!.requestURI)
        assertEquals(false, public)
    }

    @Test
    fun `dev collapse keeps the operator on the bare host and serves the public world on www`() {
        // base == operator (localhost): the apex branch must NOT claim the operator app…
        val opRequest = MockHttpServletRequest("GET", "/home").apply { serverName = "localhost" }
        val opResponse = MockHttpServletResponse()
        val opChain = MockFilterChain()
        filter.doFilter(opRequest, opResponse, opChain)
        assertEquals(200, opResponse.status)
        assertEquals("/home", (opChain.request as HttpServletRequest).requestURI)
        assertNull(opRequest.getAttribute(PublicHost.ATTR))
        // …and www serves the public world directly instead of redirecting into it.
        val request = MockHttpServletRequest("GET", "/").apply { serverName = "www.localhost" }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        assertEquals(200, response.status)
        assertEquals("/p", (chain.request as HttpServletRequest).requestURI)
        assertNotNull(request.getAttribute(PublicHost.ATTR))
        assertNull(SubjectCtx.of(request))
    }
}

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
}

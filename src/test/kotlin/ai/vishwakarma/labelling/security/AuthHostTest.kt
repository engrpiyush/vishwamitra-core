package ai.vishwakarma.labelling.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

/**
 * VA-70 (product LLD §4.2 v1.1): the open-redirect guard and the two ends of the central-callback
 * hop — the subject-chain entry point (out) and the auth-host success handler (back).
 */
class AuthHostTest {

    private val base = "vishwakarma.ai"

    /** What ExceptionTranslationFilter hands the entry point in production. */
    private val unauthenticated = InsufficientAuthenticationException("unauthenticated")

    /** The success handler never reads the principal — any Authentication satisfies the type. */
    private val principal = UsernamePasswordAuthenticationToken("dev-subject@example.com", "N/A")

    // ---- open-redirect guard -------------------------------------------------

    @Test
    fun `subject hosts and the base domain are valid return targets`() {
        assertEquals(
            "https://harsha.vishwakarma.ai/training",
            AuthHost.safeReturnTarget("https://harsha.vishwakarma.ai/training", base),
        )
        assertEquals(
            "https://vishwakarma.ai/",
            AuthHost.safeReturnTarget("https://vishwakarma.ai/", base),
        )
    }

    @Test
    fun `foreign hosts are refused`() {
        assertNull(AuthHost.safeReturnTarget("https://evil.com/", base))
        assertNull(AuthHost.safeReturnTarget("https://evil.com/.vishwakarma.ai", base))
    }

    @Test
    fun `suffix-alike hosts are refused`() {
        assertNull(AuthHost.safeReturnTarget("https://evilvishwakarma.ai/", base))
        assertNull(AuthHost.safeReturnTarget("https://vishwakarma.ai.evil.com/", base))
    }

    @Test
    fun `non-http schemes, relative paths and garbage are refused`() {
        assertNull(AuthHost.safeReturnTarget("javascript:alert(1)", base))
        assertNull(AuthHost.safeReturnTarget("//evil.com/x", base))
        assertNull(AuthHost.safeReturnTarget("/training", base))
        assertNull(AuthHost.safeReturnTarget("ht!tp://x", base))
        assertNull(AuthHost.safeReturnTarget("", base))
        assertNull(AuthHost.safeReturnTarget(null, base))
    }

    @Test
    fun `userinfo tricks don't smuggle a foreign host`() {
        // java.net.URI parses the part after @ as the real host — which fails the domain rule.
        assertNull(AuthHost.safeReturnTarget("https://harsha.vishwakarma.ai@evil.com/", base))
    }

    // ---- entry point (subject chain → auth host) --------------------------------

    @Test
    fun `entry point carries the originating URL as the continue param`() {
        val request =
            MockHttpServletRequest("GET", "/s/training").apply {
                serverName = "harsha.vishwakarma.ai"
                scheme = "https"
                serverPort = 443
                queryString = "tab=uploads"
            }
        val response = MockHttpServletResponse()

        AuthHost.entryPoint(base).commence(request, response, unauthenticated)

        assertEquals(
            "https://auth.vishwakarma.ai/auth/start?continue=" +
                "https%3A%2F%2Fharsha.vishwakarma.ai%2Fs%2Ftraining%3Ftab%3Duploads",
            response.redirectedUrl,
        )
    }

    // ---- success handler (auth host → back) ---------------------------------------

    @Test
    fun `success handler redirects to the parked target and clears it`() {
        val request = MockHttpServletRequest()
        request.session!!.setAttribute(AuthHost.RETURN_TO, "https://harsha.vishwakarma.ai/training")
        val response = MockHttpServletResponse()

        AuthHost.successHandler(base).onAuthenticationSuccess(request, response, principal)

        assertEquals("https://harsha.vishwakarma.ai/training", response.redirectedUrl)
        assertNull(request.session!!.getAttribute(AuthHost.RETURN_TO))
    }

    @Test
    fun `success handler falls back to the apex when nothing was parked`() {
        val response = MockHttpServletResponse()

        AuthHost.successHandler(base)
            .onAuthenticationSuccess(MockHttpServletRequest(), response, principal)

        assertEquals("https://vishwakarma.ai", response.redirectedUrl)
    }

    @Test
    fun `a tampered parked target still can't leave the domain`() {
        val request = MockHttpServletRequest()
        request.session!!.setAttribute(AuthHost.RETURN_TO, "https://evil.com/")
        val response = MockHttpServletResponse()

        AuthHost.successHandler(base).onAuthenticationSuccess(request, response, principal)

        assertEquals("https://vishwakarma.ai", response.redirectedUrl)
    }
}

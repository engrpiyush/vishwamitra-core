package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.Role
import jakarta.servlet.http.HttpSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

/**
 * VA-29 dev principal injection + the `?devRole=NONE` signed-out escape (the VA-43 landing walk):
 * NONE leaves the context anonymous and sticks across the session like any other choice.
 */
class DevAuthFilterTest {

    private val filter = DevAuthFilter(email = "dev@example.com", role = Role.ADMIN)

    @AfterTest
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    /** Runs one request through the filter, reusing [session] to model a browser session. */
    private fun run(param: String? = null, session: HttpSession? = null): HttpSession? {
        SecurityContextHolder.clearContext()
        val request =
            MockHttpServletRequest("GET", "/").apply {
                param?.let { setParameter("devRole", it) }
                session?.let { setSession(it) }
            }
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
        return request.getSession(false)
    }

    @Test
    fun `no param injects the configured default role`() {
        run()
        assertEquals("dev@example.com", CurrentUser.email())
        assertEquals(Role.ADMIN, CurrentUser.role())
    }

    @Test
    fun `devRole SUBJECT becomes the seeded dev subject with its binding`() {
        run("SUBJECT")
        assertEquals(DevAuthFilter.DEV_SUBJECT_EMAIL, CurrentUser.email())
        assertEquals(DevAuthFilter.DEV_SUBJECT_ID, CurrentUser.subjectId())
        assertFalse(CurrentUser.isOperator())
    }

    @Test
    fun `devRole NONE leaves the context anonymous and sticks until the next choice`() {
        val session = run("none")
        assertNull(CurrentUser.email())
        assertNull(CurrentUser.role())
        // Sticky: a plain follow-up request in the same session stays signed out…
        run(session = session)
        assertNull(CurrentUser.email())
        // …and a real devRole signs back in.
        run("REVIEWER", session = session)
        assertEquals(Role.REVIEWER, CurrentUser.role())
    }

    @Test
    fun `unknown values keep the current choice`() {
        val session = run("NONE")
        run("bogus", session = session)
        assertNull(CurrentUser.email())
    }
}

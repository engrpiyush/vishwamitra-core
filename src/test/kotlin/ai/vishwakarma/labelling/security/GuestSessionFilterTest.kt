package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.AdvocateSession
import ai.vishwakarma.labelling.domain.SessionKind
import ai.vishwakarma.labelling.persistence.AdvocateSessionRepository
import com.google.cloud.firestore.Firestore
import jakarta.servlet.http.Cookie
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

private class FilterFakeSessionRepo : AdvocateSessionRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, AdvocateSession>()

    override fun find(sessionId: String): AdvocateSession? = store[sessionId]
}

class GuestSessionFilterTest {

    private val sessions = FilterFakeSessionRepo()
    private val filter = GuestSessionFilter(sessions)

    private fun session(
        subjectId: String = "subj-42",
        kind: SessionKind = SessionKind.GUEST,
        expiresAt: Instant? = Instant.now().plusSeconds(600),
    ) {
        sessions.store["sess-1"] =
            AdvocateSession(
                sessionId = "sess-1",
                subjectId = subjectId,
                guestEmail = "guest@x.com",
                kind = kind,
                expiresAt = expiresAt,
            )
    }

    private fun run(withCtx: Boolean = true, cookie: String? = "sess-1"): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", "/s/chat")
        if (withCtx) request.setAttribute(SubjectCtx.ATTR, SubjectCtx("subj-42", "dev", "Neo"))
        cookie?.let { request.setCookies(Cookie(GuestSessionFilter.COOKIE_NAME, it)) }
        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
        return request
    }

    @Test
    fun `a valid guest session of this host attaches GuestCtx`() {
        session()
        val request = run()
        assertEquals(GuestCtx("sess-1", "guest@x.com"), GuestCtx.of(request))
        assertNull(request.getAttribute(GuestCtx.EXPIRED_ATTR))
    }

    @Test
    fun `an expired session marks EXPIRED but attaches no capability`() {
        session(expiresAt = Instant.now().minusSeconds(1))
        val request = run()
        assertNull(GuestCtx.of(request))
        assertEquals(true, request.getAttribute(GuestCtx.EXPIRED_ATTR))
    }

    @Test
    fun `a foreign subject's session attaches nothing — as if no cookie existed`() {
        session(subjectId = "subj-99")
        val request = run()
        assertNull(GuestCtx.of(request))
        assertNull(request.getAttribute(GuestCtx.EXPIRED_ATTR))
    }

    @Test
    fun `non-GUEST sessions and unknown cookies attach nothing`() {
        session(kind = SessionKind.SUBJECT)
        assertNull(GuestCtx.of(run()))
        sessions.store.clear()
        assertNull(GuestCtx.of(run()))
    }

    @Test
    fun `outside the subject world the cookie is ignored`() {
        session()
        assertNull(GuestCtx.of(run(withCtx = false)))
    }
}

package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.Role
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.web.access.intercept.RequestAuthorizationContext

class SubjectAccessTest {

    private val manager = SubjectAccess.subjectOfHost()

    private fun context(subjectId: String? = "subj-42"): RequestAuthorizationContext {
        val request = MockHttpServletRequest()
        subjectId?.let { request.setAttribute(SubjectCtx.ATTR, SubjectCtx(it, "handle", "Name")) }
        return RequestAuthorizationContext(request)
    }

    private fun auth(vararg authorities: String): Authentication =
        UsernamePasswordAuthenticationToken(
            "user@x.com",
            "N/A",
            AuthorityUtils.createAuthorityList(*authorities),
        )

    private fun granted(auth: Authentication?, ctx: RequestAuthorizationContext): Boolean =
        manager.authorize(Supplier { auth }, ctx)!!.isGranted

    @Test
    fun `operators of any rung are granted`() {
        for (role in listOf(Role.AUTHOR, Role.REVIEWER, Role.ADMIN)) {
            assertTrue(granted(auth(role.authority), context()), "expected grant: $role")
        }
    }

    @Test
    fun `the subject bound to this host is granted`() {
        val subjectAuth =
            auth(Role.SUBJECT.authority, CurrentUser.SUBJECT_ID_AUTHORITY_PREFIX + "subj-42")
        assertTrue(granted(subjectAuth, context("subj-42")))
    }

    @Test
    fun `a subject on someone else's host is denied`() {
        val subjectAuth =
            auth(Role.SUBJECT.authority, CurrentUser.SUBJECT_ID_AUTHORITY_PREFIX + "subj-42")
        assertFalse(granted(subjectAuth, context("subj-99")))
    }

    @Test
    fun `anonymous is denied (entry point then sends to login)`() {
        val anonymous =
            AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"),
            )
        assertFalse(granted(anonymous, context()))
        assertFalse(granted(null, context()))
    }

    @Test
    fun `missing subject ctx is denied even for operators`() {
        assertFalse(granted(auth(Role.ADMIN.authority), context(subjectId = null)))
    }
}

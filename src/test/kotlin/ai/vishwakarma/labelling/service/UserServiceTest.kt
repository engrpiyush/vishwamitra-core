package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.domain.User
import ai.vishwakarma.labelling.persistence.UserRepository
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakeUserRepo : UserRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, User>()

    override fun findByEmail(email: String): User? = store[email]

    override fun findAll(): List<User> = store.values.sortedBy { it.email }

    override fun upsert(user: User) {
        store[user.email] = user
    }

    override fun delete(email: String) {
        store.remove(email)
    }
}

class UserServiceTest {

    private val repo = FakeUserRepo()
    private val service = UserService(repo)

    private fun subject(
        status: SubjectStatus = SubjectStatus.ACTIVE,
        handle: String? = "neo",
    ): Subject = Subject(id = "subj-1", displayName = "Neo", handle = handle, status = status)

    @Test
    fun `createSubjectLogin binds a SUBJECT row`() {
        val result = service.createSubjectLogin("  Person@Gmail.com ", subject(), "admin@x.com")
        assertTrue(result.isRight())
        val user = repo.store["person@gmail.com"]!!
        assertEquals(Role.SUBJECT, user.role)
        assertEquals("subj-1", user.subjectId)
    }

    @Test
    fun `refuses an email already on the allowlist`() {
        repo.store["op@x.com"] = User(email = "op@x.com", role = Role.REVIEWER)
        val result = service.createSubjectLogin("op@x.com", subject(), null)
        assertIs<DomainError.Invalid>(result.swap().getOrNull())
    }

    @Test
    fun `refuses binding to an archived subject`() {
        val result =
            service.createSubjectLogin("p@x.com", subject(status = SubjectStatus.ARCHIVED), null)
        assertIs<DomainError.Invalid>(result.swap().getOrNull())
    }

    @Test
    fun `refuses binding to an unhandled subject`() {
        val result = service.createSubjectLogin("p@x.com", subject(handle = null), null)
        assertIs<DomainError.Invalid>(result.swap().getOrNull())
    }

    @Test
    fun `refuses a malformed email`() {
        val result = service.createSubjectLogin("not-an-email", subject(), null)
        assertIs<DomainError.Invalid>(result.swap().getOrNull())
    }

    @Test
    fun `precheck passes a fresh email with a handle`() {
        assertNull(service.precheckSubjectLogin("new@x.com", "neo"))
    }

    @Test
    fun `precheck refuses a blank handle`() {
        assertIs<DomainError.Invalid>(service.precheckSubjectLogin("new@x.com", " "))
    }

    @Test
    fun `precheck refuses a malformed email`() {
        assertIs<DomainError.Invalid>(service.precheckSubjectLogin("not-an-email", "neo"))
    }

    @Test
    fun `precheck refuses an already-allowlisted email, case-insensitively`() {
        repo.store["op@x.com"] = User(email = "op@x.com", role = Role.REVIEWER)
        assertIs<DomainError.Invalid>(service.precheckSubjectLogin(" OP@X.com ", "neo"))
    }

    @Test
    fun `existing SUBJECT binding survives an operator upsert (immutability)`() {
        service.createSubjectLogin("p@x.com", subject(), null)
        service.upsert("p@x.com", Role.SUBJECT, addedBy = "x", subjectId = "subj-OTHER")
        assertEquals("subj-1", repo.store["p@x.com"]!!.subjectId)
    }
}

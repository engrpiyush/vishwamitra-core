package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

private class FakeHandleSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, Subject>()
    val sentinels = mutableMapOf<String, String>()
    private var seq = 0

    override fun newId(): String = "subj-${++seq}"

    override fun findById(id: String): Subject? = store[id]

    override fun findAll(): List<Subject> = store.values.toList()

    override fun findByHandle(handle: String): Subject? =
        store.values.firstOrNull { it.handle == handle }

    override fun save(subject: Subject) {
        store[subject.id] = subject
    }

    override fun createWithHandle(subject: Subject): Boolean {
        val handle = subject.handle!!
        if (sentinels[handle] != null && sentinels[handle] != subject.id) return false
        sentinels[handle] = subject.id
        store[subject.id] = subject
        return true
    }

    override fun claimHandle(id: String, handle: String): Boolean {
        if (sentinels[handle] != null && sentinels[handle] != id) return false
        sentinels[handle] = id
        store[id] = store[id]!!.copy(handle = handle)
        return true
    }

    override fun delete(id: String) {
        store.remove(id)?.handle?.let { sentinels.remove(it) }
    }
}

@Suppress("UNCHECKED_CAST")
private fun reservedHandles(): ReservedHandles =
    ReservedHandles(
        mock(ObjectProvider::class.java) as ObjectProvider<RequestMappingHandlerMapping>,
        AppProperties(),
    )

class SubjectServiceTest {

    private val repo = FakeHandleSubjectRepo()
    private val service = SubjectService(repo, reservedHandles())

    private fun created(handle: String?): Either<DomainError, Subject> =
        service.create("op@x.com", "Test Person", handle, "")

    @Test
    fun `create without handle works (pipeline-only subject)`() {
        val result = created(null)
        assertTrue(result.isRight())
        assertNull(repo.store.values.single().handle)
    }

    @Test
    fun `create normalizes and claims the handle sentinel`() {
        val result = created("  Harsha  ")
        assertTrue(result.isRight())
        assertEquals("harsha", repo.store.values.single().handle)
        assertEquals(repo.store.values.single().id, repo.sentinels["harsha"])
    }

    @Test
    fun `invalid handles are rejected — min 6, letters and digits only`() {
        for (bad in listOf("short", "has-hyphen", "has.dot", "UPPER!", "a".repeat(33))) {
            val result = created(bad)
            assertIs<DomainError.Invalid>(result.swap().getOrNull(), "expected reject: $bad")
        }
    }

    @Test
    fun `reserved handles are rejected — literals plus the operator domain label`() {
        for (reserved in listOf("staging", "internal", "support", "privacy", "labelling")) {
            val result = created(reserved)
            assertIs<DomainError.Invalid>(result.swap().getOrNull(), "expected reject: $reserved")
        }
    }

    @Test
    fun `VA-90 curated keywords are rejected — brand, infra, auth-bait, impersonation`() {
        val representatives =
            listOf(
                "vishwakarma", // brand
                "vishwamitra",
                "advocate",
                "webmail", // infra hostnames
                "autodiscover",
                "production",
                "billing", // auth/security bait
                "security",
                "password",
                "official", // impersonation
                "administrator",
                "noreply",
            )
        for (reserved in representatives) {
            val result = created(reserved)
            assertIs<DomainError.Invalid>(result.swap().getOrNull(), "expected reject: $reserved")
        }
    }

    @Test
    fun `duplicate handle fails atomically via the sentinel`() {
        assertTrue(created("neosub").isRight())
        val second = created("neosub")
        assertIs<DomainError.Invalid>(second.swap().getOrNull())
        assertEquals(1, repo.store.values.count { it.handle == "neosub" })
    }

    @Test
    fun `handle is immutable once set`() {
        val id = created("neosub").getOrNull()!!.id
        val result = service.update(id, null, "other", null, null)
        assertIs<DomainError.Invalid>(result.swap().getOrNull())
        assertEquals("neosub", repo.store[id]!!.handle)
    }

    @Test
    fun `update can claim a first handle and validates it`() {
        val id = created(null).getOrNull()!!.id
        assertIs<DomainError.Invalid>(
            service.update(id, null, "staging", null, null).swap().getOrNull()
        )
        val ok = service.update(id, null, "latehandle", null, null)
        assertTrue(ok.isRight())
        assertEquals(id, repo.sentinels["latehandle"])
        // Re-sending the same handle is a no-op, not an immutability violation.
        assertTrue(service.update(id, null, "latehandle", null, null).isRight())
    }

    @Test
    fun `delete frees the sentinel`() {
        val id = created("neosub").getOrNull()!!.id
        service.delete(id)
        assertNull(repo.sentinels["neosub"])
        assertTrue(created("neosub").isRight())
    }

    @Test
    fun `archived subject keeps its handle`() {
        val id = created("neosub").getOrNull()!!.id
        val result = service.update(id, null, null, null, SubjectStatus.ARCHIVED)
        assertTrue(result.isRight())
        assertEquals("neosub", repo.store[id]!!.handle)
    }
}

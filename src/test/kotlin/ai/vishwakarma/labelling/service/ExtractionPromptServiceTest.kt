package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakeExtractionPromptRepo : ExtractionPromptRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ExtractionPrompt>()

    override fun findById(id: String): ExtractionPrompt? = store[id]

    override fun findAll(): List<ExtractionPrompt> = store.values.toList()

    override fun save(prompt: ExtractionPrompt) {
        store[prompt.id] = prompt
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}

/** [ExtractionPromptService]: versioning, blank-revert, built-in fallback, provenance hash. */
class ExtractionPromptServiceTest {

    private val repo = FakeExtractionPromptRepo()
    private val service = ExtractionPromptService(repo)

    @Test
    fun `update creates v1 and increments the version on every save`() {
        val first = service.update(ContentType.RESUME_CV, "Focus on employment dates.", "admin")
        val second = service.update(ContentType.RESUME_CV, "Focus on titles.", "admin")

        assertEquals(1, first.version)
        assertEquals(2, second.version)
        assertEquals("admin", second.updatedBy)
        assertEquals("Focus on titles.", repo.store["RESUME_CV"]!!.instructions)
    }

    @Test
    fun `update refuses blank instructions — reverting is reset's job`() {
        service.update(ContentType.SKILL_DEMO, "Custom demo block.", "admin")

        assertFailsWith<IllegalArgumentException> {
            service.update(ContentType.SKILL_DEMO, "   ", "admin")
        }
        assertEquals("Custom demo block.", repo.store["SKILL_DEMO"]!!.instructions)
    }

    @Test
    fun `reset deletes the override so resolve falls back to the code default`() {
        service.update(ContentType.SKILL_DEMO, "Custom demo block.", "admin")

        service.reset(ContentType.SKILL_DEMO)

        assertTrue(repo.store.isEmpty())
        val resolved = service.resolve(ContentType.SKILL_DEMO)
        assertEquals(ExtractionPrompt.builtinFor(ContentType.SKILL_DEMO), resolved.instructions)
        assertEquals(0, resolved.version)
    }

    @Test
    fun `resolve prefers the stored row and stamps its version`() {
        service.update(ContentType.SKILL_DEMO, "Custom demo block.", "admin")

        val resolved = service.resolve(ContentType.SKILL_DEMO)

        assertEquals("Custom demo block.", resolved.instructions)
        assertEquals(1, resolved.version)
        assertEquals(12, resolved.hash.length)
    }

    @Test
    fun `resolve falls back to each content type's own code default`() {
        val teaching = service.resolve(ContentType.TEACHING_SESSION)
        val resume = service.resolve(ContentType.RESUME_CV)

        assertEquals(
            ExtractionPrompt.builtinFor(ContentType.TEACHING_SESSION),
            teaching.instructions,
        )
        assertEquals(ExtractionPrompt.builtinFor(ContentType.RESUME_CV), resume.instructions)
        assertTrue(teaching.instructions.isNotBlank())
        assertTrue(resume.instructions.isNotBlank())
        assertEquals(0, teaching.version)
        assertEquals(0, resume.version)
        assertNotEquals(teaching.hash, resume.hash)
    }

    @Test
    fun `the hash identifies the exact instruction text`() {
        service.update(ContentType.RESUME_CV, "Same text.", "admin")
        val a = service.resolve(ContentType.RESUME_CV)
        service.update(ContentType.RESUME_CV, "Same text.", "admin")
        val b = service.resolve(ContentType.RESUME_CV)
        service.update(ContentType.RESUME_CV, "Different text.", "admin")
        val c = service.resolve(ContentType.RESUME_CV)

        assertEquals(a.hash, b.hash)
        assertNotEquals(a.hash, c.hash)
    }

    @Test
    fun `list covers every content type grouped by source class`() {
        service.update(ContentType.SKILL_DEMO, "Custom demo block.", "admin")

        val groups = service.list()

        assertEquals(SourceClass.entries.toSet(), groups.keys)
        val rows = groups.values.flatten()
        assertEquals(ContentType.entries.size, rows.size)
        assertEquals(ContentType.entries.toSet(), rows.map { it.contentType }.toSet())
        val skillDemo = rows.single { it.contentType == ContentType.SKILL_DEMO }
        assertEquals("Custom demo block.", skillDemo.prompt!!.instructions)
        val resume = rows.single { it.contentType == ContentType.RESUME_CV }
        assertNull(resume.prompt)
        assertEquals(
            ExtractionPrompt.builtinFor(ContentType.TEACHING_SESSION),
            rows.single { it.contentType == ContentType.TEACHING_SESSION }.builtin,
        )
    }
}

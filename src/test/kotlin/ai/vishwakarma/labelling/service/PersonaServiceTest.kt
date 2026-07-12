package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.AdvocateName
import ai.vishwakarma.labelling.domain.AdvocateRegion
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.PersonaPosture
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectPersona
import ai.vishwakarma.labelling.persistence.AdvocateNameRepository
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import ai.vishwakarma.labelling.persistence.SubjectPersonaRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakePersonaSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]
}

private class FakePersonaRepo : SubjectPersonaRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, SubjectPersona>()

    override fun findBySubject(subjectId: String): SubjectPersona? = store[subjectId]

    override fun save(persona: SubjectPersona) {
        store[persona.subjectId] = persona
    }

    override fun delete(subjectId: String) {
        store.remove(subjectId)
    }
}

private class FakePersonaNameRepo : AdvocateNameRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<AdvocateName>()

    override fun findAll(): List<AdvocateName> = store.sortedWith(compareBy({ it.name }, { it.id }))

    override fun save(name: AdvocateName) {
        store += name
    }
}

private class FakePersonaPromptRepo : ExtractionPromptRepository(mock(Firestore::class.java)) {
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

/** [PersonaService]: defaults materialization, hash contract, deterministic A2 pick (VA-50). */
class PersonaServiceTest {

    private val subjects = FakePersonaSubjectRepo()
    private val personas = FakePersonaRepo()
    private val names = FakePersonaNameRepo()
    private val prompts = FakePersonaPromptRepo()

    private fun service(props: AppProperties = AppProperties()): PersonaService =
        PersonaService(props, personas, subjects, names, ExtractionPromptService(prompts))

    private fun seedSubject(id: String = "s1") {
        subjects.store[id] = Subject(id = id, displayName = "Asha")
    }

    private fun seedPool() {
        listOf("Ananya Iyer" to "F", "Arjun Mehta" to "M", "Priya Nair" to "F").forEach {
            (name, gender) ->
            names.save(
                AdvocateName(
                    id = name.lowercase().replace(" ", "-"),
                    name = name,
                    region = AdvocateRegion.IN,
                    gender = gender,
                )
            )
        }
    }

    private fun <T> Either<DomainError, T>.expectRight(): T =
        fold({ throw AssertionError("expected success, got $it") }, { it })

    private fun <T> Either<DomainError, T>.err(): DomainError? = fold({ it }, { null })

    @Test
    fun `put with every question skipped yields a fully-resolved persona with a stable hash`() {
        seedSubject()
        seedPool()
        val svc = service()

        val first = svc.put("s1", PersonaUpdateRequest(), actor = "op").expectRight()
        val second = svc.put("s1", PersonaUpdateRequest(), actor = "op").expectRight()

        assertNotNull(first.stored?.personaHash)
        assertEquals(first.personaHash, first.stored?.personaHash)
        assertEquals(first.personaHash, second.personaHash)
        // Fully materialized: name + preset resolved despite an all-null stored doc.
        assertTrue(first.resolved.advocateName.isNotBlank())
        assertEquals("warm-storyteller", first.resolved.presetId)
    }

    @Test
    fun `auto-assigned name is deterministic per subject while the pool is unchanged`() {
        seedSubject()
        seedPool()
        val svc = service()

        val picks = (1..5).map { svc.autoAssignedName("s1") }.distinct()

        assertEquals(1, picks.size)
        assertTrue(names.store.map { it.name }.contains(picks.single()))
        // A different pool (one row added) may shift the pick — that is the documented QA-6
        // archival trigger, not an invariant to pin. An empty pool falls back to the constant.
        names.store.clear()
        assertEquals(PersonaService.DEFAULT_ADVOCATE_NAME, svc.autoAssignedName("s1"))
    }

    @Test
    fun `personaHash changes iff a resolved setting changes — not on skipped-list reshuffle`() {
        seedSubject()
        seedPool()
        val svc = service()

        val base =
            svc.put("s1", PersonaUpdateRequest(skipped = listOf("A1", "B4")), "op").expectRight()
        val reshuffled =
            svc.put("s1", PersonaUpdateRequest(skipped = listOf("B4", "A1")), "op").expectRight()
        val changed =
            svc.put("s1", PersonaUpdateRequest(posture = "conservative"), "op").expectRight()

        assertEquals(base.personaHash, reshuffled.personaHash)
        assertNotEquals(base.personaHash, changed.personaHash)
        assertEquals(PersonaPosture.CONSERVATIVE, changed.resolved.posture)
    }

    @Test
    fun `invalid values are rejected, valid pool names and presets accepted`() {
        seedSubject()
        seedPool()
        val svc = service()

        val badEnum = svc.put("s1", PersonaUpdateRequest(verbosity = "chatty"), "op")
        val badPreset = svc.put("s1", PersonaUpdateRequest(presetId = "nope"), "op")
        val badName = svc.put("s1", PersonaUpdateRequest(advocateName = "Nobody"), "op")
        val good =
            svc.put(
                "s1",
                PersonaUpdateRequest(advocateName = "Priya Nair", presetId = "grounded-mentor"),
                "op",
            )

        assertIs<DomainError.Invalid>(badEnum.err())
        assertIs<DomainError.Invalid>(badPreset.err())
        assertIs<DomainError.Invalid>(badName.err())
        assertEquals("Priya Nair", good.expectRight().resolved.advocateName)
        assertEquals("grounded-mentor", good.expectRight().resolved.presetId)
    }

    @Test
    fun `missing subject is NotFound and a disabled stage 4 refuses writes`() {
        seedSubject()
        val disabled = AppProperties(stage4 = AppProperties.Stage4(enabled = false))

        assertIs<DomainError.NotFound>(service().view("ghost").err())
        assertIs<DomainError.Conflict>(
            service(disabled).put("s1", PersonaUpdateRequest(), "op").err()
        )
    }

    @Test
    fun `a subject with no stored persona still resolves — the SELECT guard's read`() {
        seedSubject()
        seedPool()

        val resolved = service().resolved("s1")

        assertTrue(resolved.advocateName.isNotBlank())
        assertEquals(64, resolved.hash().length)
    }
}

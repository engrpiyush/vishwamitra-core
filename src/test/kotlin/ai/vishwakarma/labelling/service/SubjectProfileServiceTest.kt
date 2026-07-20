package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectProfile
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.SubjectProfileRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakeProfileSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]
}

private class FakeProfileRepo : SubjectProfileRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, SubjectProfile>()

    override fun findBySubject(subjectId: String): SubjectProfile? = store[subjectId]

    override fun save(profile: SubjectProfile) {
        store[profile.subjectId] = profile
    }

    override fun delete(subjectId: String) {
        store.remove(subjectId)
    }
}

private class FakeProfileManifestRepo : IntakeManifestRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, IntakeManifest>()

    override fun findBySubject(subjectId: String): IntakeManifest? = store[subjectId]

    override fun save(manifest: IntakeManifest) {
        store[manifest.subjectId] = manifest
    }
}

/**
 * [SubjectProfileService]: the A/B round-trip, field validation, the feature flag, and the §2.3
 * divergence from the persona template — `put` refuses once the manifest is sealed.
 */
class SubjectProfileServiceTest {

    private val subjects = FakeProfileSubjectRepo()
    private val profiles = FakeProfileRepo()
    private val manifests = FakeProfileManifestRepo()

    private val enabledProps = AppProperties(stage4 = AppProperties.Stage4(profileEnabled = true))

    private fun service(props: AppProperties = enabledProps): SubjectProfileService =
        SubjectProfileService(liveConfig(props), profiles, subjects, manifests)

    private fun seedSubject(id: String = "s1") {
        subjects.store[id] = Subject(id = id, displayName = "Asha")
    }

    private fun seal(subjectId: String = "s1", sealed: Boolean = true) {
        manifests.save(IntakeManifest(id = subjectId, subjectId = subjectId, sealed = sealed))
    }

    private fun request() =
        SubjectProfileUpdateRequest(
            country = "in",
            marketRegion = "IN",
            currency = "inr",
            timezone = "Asia/Kolkata",
            primaryLanguage = "en-IN",
            knowledgeAsOf = "2026-07-15",
        )

    private fun <T> Either<DomainError, T>.expectRight(): T =
        fold({ throw AssertionError("expected success, got $it") }, { it })

    private fun <T> Either<DomainError, T>.err(): DomainError? = fold({ it }, { null })

    @Test
    fun `put stores the normalized A-B block and stamps the hash`() {
        seedSubject()

        val view = service().put("s1", request(), actor = "op").expectRight()

        // Codes normalize to their canonical case; the free market label is kept verbatim.
        assertEquals("IN", view.stored?.country)
        assertEquals("INR", view.stored?.currency)
        assertEquals(LocalDate.parse("2026-07-15"), view.stored?.knowledgeAsOf)
        assertEquals("op", view.stored?.updatedBy)
        assertEquals(view.profileHash, view.stored?.profileHash)
        assertNotNull(view.profileHash)
        assertEquals(
            "the India market (INR), primary language en-IN, timezone Asia/Kolkata",
            view.resolved.locale
        )
    }

    @Test
    fun `an all-blank put is legal and leaves the profile hash null`() {
        seedSubject()

        val view = service().put("s1", SubjectProfileUpdateRequest(), actor = "op").expectRight()

        assertTrue(view.resolved.blank)
        assertNull(view.profileHash)
    }

    @Test
    fun `invalid codes are reported together and nothing is written`() {
        seedSubject()

        val err =
            service()
                .put(
                    "s1",
                    SubjectProfileUpdateRequest(
                        country = "India",
                        currency = "rupees",
                        timezone = "Mars/Olympus",
                        knowledgeAsOf = "15-07-2026",
                    ),
                    actor = "op",
                )
                .err()

        val invalid = assertIs<DomainError.Invalid>(err)
        listOf("country", "currency", "timezone", "knowledgeAsOf").forEach {
            assertTrue(it in invalid.message, "expected $it in: ${invalid.message}")
        }
        assertTrue(profiles.store.isEmpty())
    }

    @Test
    fun `put refuses once the manifest is sealed — the profile is frozen with the corpus`() {
        seedSubject()
        val svc = service()
        svc.put("s1", request(), actor = "op").expectRight()
        seal()

        val err = svc.put("s1", request().copy(currency = "USD"), actor = "op").err()

        assertIs<DomainError.Conflict>(err)
        // The pre-seal value survives untouched, and the view reports the live lock.
        assertEquals("INR", profiles.store["s1"]?.currency)
        assertTrue(svc.view("s1").expectRight().sealed)
    }

    @Test
    fun `an unseal reopens the edit path`() {
        seedSubject()
        val svc = service()
        svc.put("s1", request(), actor = "op").expectRight()
        seal()
        seal(sealed = false)

        val view = svc.put("s1", request().copy(currency = "USD"), actor = "admin").expectRight()

        assertEquals("USD", view.stored?.currency)
    }

    @Test
    fun `with the flag off put refuses and resolved reports a blank profile`() {
        seedSubject()
        profiles.store["s1"] = SubjectProfile(subjectId = "s1", country = "IN", currency = "INR")
        val svc = service(AppProperties())

        assertIs<DomainError.Conflict>(svc.put("s1", request(), actor = "op").err())
        // The stored doc is untouched, but Stage 4 sees nothing — injection stays off.
        assertTrue(svc.resolved("s1").blank)
    }

    @Test
    fun `an unknown subject is a NotFound on both read and write`() {
        val svc = service()

        assertIs<DomainError.NotFound>(svc.view("nope").err())
        assertIs<DomainError.NotFound>(svc.put("nope", request(), actor = "op").err())
    }
}

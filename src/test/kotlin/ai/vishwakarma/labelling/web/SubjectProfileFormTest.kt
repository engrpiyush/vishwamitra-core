package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.EmploymentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectProfile
import ai.vishwakarma.labelling.domain.SubjectProfileDefaults
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.SubjectProfileRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.ProfileClaimMaterialiser
import ai.vishwakarma.labelling.service.SubjectProfileService
import ai.vishwakarma.labelling.service.SubjectProfileUpdateRequest
import com.google.cloud.firestore.Firestore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FormSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    override fun findById(id: String): Subject? = Subject(id = id, displayName = "Asha")
}

private class FormProfileRepo : SubjectProfileRepository(mock(Firestore::class.java)) {
    var saved: SubjectProfile? = null

    override fun findBySubject(subjectId: String): SubjectProfile? = saved

    override fun save(profile: SubjectProfile) {
        saved = profile
    }
}

private class FormManifestRepo : IntakeManifestRepository(mock(Firestore::class.java)) {
    var manifest: IntakeManifest? = null

    override fun findBySubject(subjectId: String): IntakeManifest? = manifest

    override fun save(manifest: IntakeManifest) {
        this.manifest = manifest
    }
}

/**
 * [SubjectProfileForm] — the VA-140/VA-141 form bridge. The load-bearing test is the first one: the
 * dropdowns exist precisely so a pick can never be refused, so it drives every catalog value
 * through the **real** `SubjectProfileService.put` validators rather than re-deriving them.
 */
class SubjectProfileFormTest {

    private val profiles = FormProfileRepo()
    private val manifests = FormManifestRepo()

    private val service =
        SubjectProfileService(
            liveConfig(AppProperties(stage4 = AppProperties.Stage4(profileEnabled = true))),
            profiles,
            FormSubjectRepo(),
            manifests,
            ProfileClaimMaterialiser(
                liveConfig(AppProperties(stage4 = AppProperties.Stage4(profileEnabled = true))),
                profiles,
                ClaimRepository(mock(Firestore::class.java)),
            ),
        )

    private fun put(request: SubjectProfileUpdateRequest): DomainError? =
        service.put("s1", request, "op@example.com").fold({ it }, { null })

    // ---- the catalogs are validator-clean ------------------------------------------

    @Test
    fun `every catalog value is accepted by the service that validates it`() {
        for (option in SubjectProfileForm.countries) {
            assertEquals(
                null,
                put(SubjectProfileUpdateRequest(country = option.value)),
                "country ${option.value} (${option.label}) was refused",
            )
        }
        for (option in SubjectProfileForm.currencies) {
            assertEquals(
                null,
                put(SubjectProfileUpdateRequest(currency = option.value)),
                "currency ${option.value} was refused",
            )
        }
        for (option in SubjectProfileForm.timezones) {
            assertEquals(
                null,
                put(SubjectProfileUpdateRequest(timezone = option.value)),
                "timezone ${option.value} was refused",
            )
        }
        for (option in SubjectProfileForm.languages) {
            assertEquals(
                null,
                put(SubjectProfileUpdateRequest(primaryLanguage = option.value)),
                "language ${option.value} was refused",
            )
        }
    }

    @Test
    fun `catalogs are non-empty, distinct and labelled`() {
        val catalogs =
            mapOf(
                "countries" to SubjectProfileForm.countries,
                "currencies" to SubjectProfileForm.currencies,
                "timezones" to SubjectProfileForm.timezones,
                "languages" to SubjectProfileForm.languages,
            )
        catalogs.forEach { (name, options) ->
            assertTrue(options.size > 50, "$name looks truncated: ${options.size}")
            assertEquals(
                options.size,
                options.map { it.value }.distinct().size,
                "$name has duplicate values",
            )
            assertTrue(
                options.none { it.value.isBlank() || it.label.isBlank() },
                "$name has a hole"
            )
        }
        // The blank "prefer not to say" choice is the template's, never a catalog row — a blank
        // value must mean *unanswered*, and the service reads it that way (§2.1).
        assertTrue(SubjectProfileForm.countries.none { it.value.isEmpty() })
    }

    @Test
    fun `the timezone catalog carries real places, not the legacy aliases`() {
        val values = SubjectProfileForm.timezones.map { it.value }
        assertTrue(values.contains("Asia/Kolkata"))
        assertTrue(values.contains("America/Los_Angeles"))
        assertTrue(values.none { it.startsWith("Etc/") || it.startsWith("SystemV/") })
        // Underscores are a zone-id spelling, not a label; the value keeps them, the label doesn't.
        val la = SubjectProfileForm.timezones.first { it.value == "America/Los_Angeles" }
        assertEquals("America/Los Angeles", la.label)
    }

    @Test
    fun `country labels match the label the prompt will render`() {
        // The dropdown label and ResolvedSubjectProfile.locale must agree, or an operator picks
        // "India" and the generation prompt says something else.
        val india = SubjectProfileForm.countries.first { it.value == "IN" }
        assertEquals("India", india.label)
        val resolved =
            SubjectProfileDefaults.resolve(SubjectProfile(subjectId = "s1", country = "IN"))
        assertTrue(
            resolved.locale.contains(india.label),
            "locale '${resolved.locale}' lost the label"
        )
    }

    // ---- the read-only summary ------------------------------------------------------

    @Test
    fun `summary omits unanswered fields and ends on the sentence the prompt gets`() {
        val resolved =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    country = "IN",
                    currency = "INR",
                    knowledgeAsOf = LocalDate.parse("2026-07-15"),
                )
            )
        val rows = SubjectProfileForm.summary(resolved)
        val labels = rows.map { it.label }
        assertTrue(labels.contains("Where you're based"))
        assertTrue(labels.contains("Current as of"))
        // Never asked, never shown — an unanswered field is unknown, not "none".
        assertFalse(labels.contains("Time zone"))
        assertFalse(labels.contains("Main language"))
        assertFalse(labels.contains("Market"))
        assertEquals("Your advocate reads this as", rows.last().label)
        assertEquals(resolved.locale, rows.last().value)
    }

    @Test
    fun `a blank profile summarises to nothing at all`() {
        assertTrue(SubjectProfileForm.summary(SubjectProfileDefaults.resolve(null)).isEmpty())
    }

    @Test
    fun `summary surfaces the C-D declared fields in subject-safe words, keys as labels`() {
        val resolved =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    targetRoles = listOf("Staff Engineer"),
                    targetSeniority = "Staff",
                    employmentType = EmploymentType.FTE,
                    openToRelocation = false,
                    aspirations = listOf("Optimising for staff-level IC work"),
                    doNotDiscussChecks = listOf("health"),
                )
            )
        val byLabel = SubjectProfileForm.summary(resolved).associate { it.label to it.value }

        assertEquals("Staff Engineer", byLabel["Roles you're aiming for"])
        assertEquals("Staff", byLabel["Level you're targeting"])
        assertEquals("A permanent role", byLabel["How you'd like to work"])
        assertEquals("No", byLabel["Open to relocating"])
        assertEquals("Optimising for staff-level IC work", byLabel["What you're optimising for"])
        // A do-not-discuss key renders as its curated label, never the raw storage key.
        assertEquals("Health and medical history", byLabel["Topics to keep private"])
    }

    // ---- §12.3: the subject never reads a service string ------------------------------

    @Test
    fun `every service refusal maps to subject-safe copy`() {
        manifests.save(IntakeManifest(id = "s1", subjectId = "s1", sealed = true))
        val sealedRefusal = put(SubjectProfileUpdateRequest(country = "IN"))
        manifests.manifest = null
        val badValue = put(SubjectProfileUpdateRequest(country = "Nowhere"))

        val refusals =
            listOfNotNull(sealedRefusal, badValue) +
                DomainError.NotFound("Subject s1 not found") +
                DomainError.Conflict("The subject profile is disabled (app.stage4.profile-enabled)")
        assertEquals(4, refusals.size, "expected both live refusals to fire")

        for (error in refusals) {
            val copy = SubjectProfileForm.friendly(error)
            assertTrue(copy.isNotBlank())
            for (banned in
                listOf(
                    "manifest",
                    "unseal",
                    "sealed",
                    "app.stage4",
                    "profile",
                    "subject s1",
                    "null",
                )) {
                assertFalse(
                    copy.lowercase().contains(banned),
                    "'$banned' leaked into subject copy: $copy",
                )
            }
            // …and the raw message never passes through untouched.
            assertFalse(copy.contains(error.message), "the service string reached the subject")
        }
    }
}

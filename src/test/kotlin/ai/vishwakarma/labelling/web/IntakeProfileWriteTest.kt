package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.ResolvedSubjectProfile
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.OpsCounterRepository
import ai.vishwakarma.labelling.persistence.SubjectPersonaRepository
import ai.vishwakarma.labelling.persistence.SubjectProfileRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.ProfileClaimMaterialiser
import ai.vishwakarma.labelling.service.ProvisioningService
import ai.vishwakarma.labelling.service.Stage2Service
import ai.vishwakarma.labelling.service.SubjectProfileService
import ai.vishwakarma.labelling.service.SubjectProfileUpdateRequest
import ai.vishwakarma.labelling.service.SubjectProfileView
import ai.vishwakarma.labelling.service.SubjectService
import ai.vishwakarma.labelling.service.TokenService
import ai.vishwakarma.labelling.service.UserService
import arrow.core.Either
import arrow.core.right
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * VA-150 write half for the operator profile panel (profile LLD §7.2): the C/D fields reach
 * [SubjectProfileService.put] and the bespoke-boundary verdict reaches
 * [SubjectProfileService.decideDoNotDiscussCustom]. The rendering half is
 * [ProfileTemplateRenderTest]; the browser pass is the owner's. Driven through the real controller
 * on standalone MockMvc — the [SubjectProfilePageTest] idiom — so no Spring context boots and
 * nothing is read from Firestore.
 */
class IntakeProfileWriteTest {

    private val profiles = FakeAdminProfileService()

    // Held as a field (not an inline mock) so the §7.2 invariant test can `verify(never())` on it:
    // editProfile must never stamp the subject's declared attestation on an operator write.
    private val intake = mock(IntakeService::class.java)

    private val mvc =
        MockMvcBuilders.standaloneSetup(
                IntakeController(
                    mock(SubjectService::class.java),
                    intake,
                    mock(Stage2Service::class.java),
                    mock(SubjectScoreRepository::class.java),
                    mock(SubjectPersonaRepository::class.java),
                    mock(ProvisioningService::class.java),
                    mock(TokenService::class.java),
                    mock(UserService::class.java),
                    mock(OpsCounterRepository::class.java),
                    profiles,
                )
            )
            .build()

    @Test
    fun `editProfile hands the C-D fields to the service, coalescing the checklist`() {
        profiles.putResult = view().right()

        mvc.perform(
                post("/intake/s1/profile")
                    .param("country", "IN")
                    .param("targetRoles", "Staff Engineer")
                    .param("targetSeniority", "Staff")
                    .param("employmentType", "FTE")
                    .param("openToRelocation", "false")
                    .param("aspirations", "Optimising for staff-level IC work, not management")
            )
            .andExpect(redirectedUrl("/intake/s1"))

        assertEquals(1, profiles.putCalls)
        val sent = profiles.lastPut!!
        assertEquals(listOf("Staff Engineer"), sent.targetRoles)
        assertEquals("Staff", sent.targetSeniority)
        assertEquals("FTE", sent.employmentType)
        // openToRelocation rides the wire as text ("true"/"false"/blank), forwarded verbatim so the
        // service owns the tri-state parse and the empty option can clear a stored stance.
        assertEquals("false", sent.openToRelocation)
        assertEquals(
            listOf("Optimising for staff-level IC work, not management"),
            sent.aspirations,
        )
        // No checkbox posted ⇒ the panel means "none", so the controller coalesces to an empty
        // list (the service reads a null list as "keep stored"; the operator can clear a boundary).
        assertEquals(emptyList(), sent.doNotDiscussChecks)
    }

    @Test
    fun `editProfile hands the E contact rows to the service, one per kind, with the share flag`() {
        profiles.putResult = view().right()

        mvc.perform(
                post("/intake/s1/profile")
                    .param("contactValue_EMAIL", "asha@example.com")
                    .param("contactShare_EMAIL", "INCLUDE")
                    // PHONE has a value but no share radio ⇒ private (the safe default).
                    .param("contactValue_PHONE", "+91 555 0100")
            )
            .andExpect(redirectedUrl("/intake/s1"))

        val sent = profiles.lastPut!!.contact!!
        // The panel renders every kind, so the whole set is sent (blank rows the service drops).
        assertEquals(5, sent.size)
        val email = sent.first { it.kind == "EMAIL" }
        assertEquals("asha@example.com", email.value)
        assertTrue(email.shareable)
        val phone = sent.first { it.kind == "PHONE" }
        assertEquals("+91 555 0100", phone.value)
        assertFalse(phone.shareable)
    }

    @Test
    fun `the operator panel carries no declared attestation — that is the subject's act`() {
        // §7.2: the admin write is operator authority, not the subject's affirmation, so
        // editProfile must never stamp the manifest's declaredAttested twin. The `verify(never())`
        // is what actually pins that — asserting only `putCalls == 1` (as this test once did) stays
        // green even if the stamp is wrongly added, because a Mockito mock returns null from
        // attestDeclared and the controller ignores the result, so nothing "surfaces".
        profiles.putResult = view().right()

        mvc.perform(post("/intake/s1/profile").param("aspirations", "Deep technical work"))
            .andExpect(redirectedUrl("/intake/s1"))

        assertEquals(1, profiles.putCalls)
        verify(intake, never()).attestDeclared(anyString(), any())
    }

    @Test
    fun `a blank relocation choice reaches the service as a clear, not a keep`() {
        // The panel's "—"/"Prefer not to say" option posts openToRelocation="" (present, empty).
        // The controller forwards it verbatim — the service reads a present blank as a clear — so
        // the operator can withdraw a stored stance. The old inline `when` coalesced "" to null,
        // which the service reads as "keep stored", making the empty option a silent no-op.
        profiles.putResult = view().right()

        mvc.perform(post("/intake/s1/profile").param("openToRelocation", ""))
            .andExpect(redirectedUrl("/intake/s1"))

        assertEquals(1, profiles.putCalls)
        assertEquals("", profiles.lastPut!!.openToRelocation)
    }

    @Test
    fun `an approve verdict reaches decideDoNotDiscussCustom with approve true`() {
        profiles.decideResult = view().right()

        mvc.perform(post("/intake/s1/profile/dnd-custom").param("approve", "true"))
            .andExpect(redirectedUrl("/intake/s1"))

        assertEquals(1, profiles.decideCalls)
        assertEquals(true, profiles.lastDecideApprove)
    }

    @Test
    fun `a reject verdict reaches decideDoNotDiscussCustom with approve false`() {
        profiles.decideResult = view().right()

        mvc.perform(post("/intake/s1/profile/dnd-custom").param("approve", "false"))
            .andExpect(redirectedUrl("/intake/s1"))

        assertEquals(1, profiles.decideCalls)
        assertEquals(false, profiles.lastDecideApprove)
    }

    private fun view() =
        SubjectProfileView(
            subjectId = "s1",
            stored = null,
            resolved = ResolvedSubjectProfile(),
            profileHash = null,
            sealed = false,
            enabled = true,
        )
}

/** Records what the operator panel asked of the service; the seal/flag rules are its own tests. */
private class FakeAdminProfileService :
    SubjectProfileService(
        liveConfig(),
        SubjectProfileRepository(mock(Firestore::class.java)),
        SubjectRepository(mock(Firestore::class.java)),
        IntakeManifestRepository(mock(Firestore::class.java)),
        ProfileClaimMaterialiser(
            liveConfig(),
            SubjectProfileRepository(mock(Firestore::class.java)),
            ClaimRepository(mock(Firestore::class.java)),
            ClaimReviewRepository(mock(Firestore::class.java)),
        ),
    ) {

    lateinit var putResult: Either<DomainError, SubjectProfileView>
    lateinit var decideResult: Either<DomainError, SubjectProfileView>
    var lastPut: SubjectProfileUpdateRequest? = null
    var putCalls = 0
    var lastDecideApprove: Boolean? = null
    var decideCalls = 0

    // No test here drives the detail GET, so `view` is never called; it delegates to putResult
    // only to satisfy the override.
    override fun view(subjectId: String): Either<DomainError, SubjectProfileView> = putResult

    override fun put(
        subjectId: String,
        request: SubjectProfileUpdateRequest,
        actor: String?,
    ): Either<DomainError, SubjectProfileView> {
        putCalls++
        lastPut = request
        return putResult
    }

    override fun decideDoNotDiscussCustom(
        subjectId: String,
        approve: Boolean,
        actor: String?,
    ): Either<DomainError, SubjectProfileView> {
        decideCalls++
        lastDecideApprove = approve
        return decideResult
    }
}

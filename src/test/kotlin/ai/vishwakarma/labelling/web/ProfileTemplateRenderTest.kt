package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.DoNotDiscussApproval
import ai.vishwakarma.labelling.domain.DoNotDiscussCustom
import ai.vishwakarma.labelling.domain.EmploymentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.ResolvedSubjectProfile
import ai.vishwakarma.labelling.domain.SealAction
import ai.vishwakarma.labelling.domain.SealEvent
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectProfile
import ai.vishwakarma.labelling.domain.SubjectProfileDefaults
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.OpsCounterRepository
import ai.vishwakarma.labelling.persistence.SubjectPersonaRepository
import ai.vishwakarma.labelling.persistence.SubjectProfileRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.ProfileClaimMaterialiser
import ai.vishwakarma.labelling.service.ProvisioningService
import ai.vishwakarma.labelling.service.QuestionService
import ai.vishwakarma.labelling.service.Stage2Service
import ai.vishwakarma.labelling.service.SubjectProfileService
import ai.vishwakarma.labelling.service.SubjectProfileView
import ai.vishwakarma.labelling.service.SubjectService
import ai.vishwakarma.labelling.service.TokenService
import ai.vishwakarma.labelling.service.UserService
import arrow.core.Either
import arrow.core.right
import com.google.cloud.firestore.Firestore
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/**
 * The half a view-name assertion cannot reach: every template VA-140/VA-141 touches is *rendered*
 * through the real Thymeleaf engine, so an expression typo (`?:` on a non-null String, a mis-scoped
 * `th:if`, a fragment that does not exist) fails here rather than in the owner's browser pass. It
 * is also the only place the two **flag-off** renderings are visible at all: the shared subject
 * footer and the operator panel both have to change shape when `app.stage4.profile-enabled` is
 * down, and dev — the one profile where it is up — cannot show it.
 *
 * Deliberately not a Spring Boot context test — a standalone engine over `classpath:/templates/`
 * plus the same standalone MockMvc the routing tests use, so nothing boots and nothing is read from
 * Firestore.
 */
class ProfileTemplateRenderTest {

    private fun renderingMvc(controller: Any, advice: Any? = null): MockMvc {
        val templates =
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                templateMode = TemplateMode.HTML
                characterEncoding = "UTF-8"
                isCacheable = false
            }
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(templates)
                addDialect(SpringSecurityDialect())
            }
        val views =
            ThymeleafViewResolver().apply {
                templateEngine = engine
                characterEncoding = "UTF-8"
            }
        val builder = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(views)
        // Standalone MockMvc registers no advice of its own, so anything a shared *fragment* reads
        // has to be handed in explicitly — which is the point here: the subject footer's gate comes
        // from [GlobalModelAdvice], not from the controller that happens to render the page.
        if (advice != null) builder.setControllerAdvice(advice)
        return builder.build()
    }

    /** A real [GlobalModelAdvice] with only `app.stage4.profile-enabled` varied. */
    private fun advice(surfaceOn: Boolean) =
        GlobalModelAdvice(
            SubjectProfileService(
                liveConfig(
                    AppProperties(stage4 = AppProperties.Stage4(profileEnabled = surfaceOn))
                ),
                SubjectProfileRepository(mock(Firestore::class.java)),
                SubjectRepository(mock(Firestore::class.java)),
                IntakeManifestRepository(mock(Firestore::class.java)),
                ProfileClaimMaterialiser(
                    liveConfig(),
                    SubjectProfileRepository(mock(Firestore::class.java)),
                    ClaimRepository(mock(Firestore::class.java)),
                ),
            )
        )

    // ---- VA-140: subject/training/profile.html ---------------------------------------

    private val ctx = SubjectCtx(subjectId = "s1", handle = "asha", displayName = "Asha")

    private fun subjectPage(uploadPhase: Boolean, stored: SubjectProfile?): String {
        val intake = mock(IntakeService::class.java)
        val stage2 = mock(Stage2Service::class.java)
        `when`(intake.manifest("s1"))
            .thenReturn(
                IntakeManifest(
                    id = "s1",
                    subjectId = "s1",
                    sealed = !uploadPhase,
                    stage2StartedAt = if (uploadPhase) null else Instant.now(),
                    reviewSubmittedAt = if (uploadPhase) null else Instant.now(),
                )
            )
        `when`(stage2.listJobs("s1")).thenReturn(emptyList())
        val profiles = FakeRenderProfileService()
        profiles.viewResult =
            SubjectProfileView(
                    subjectId = "s1",
                    stored = stored,
                    resolved =
                        ResolvedSubjectProfile(
                            country = stored?.country.orEmpty(),
                            currency = stored?.currency.orEmpty(),
                            knowledgeAsOf = stored?.knowledgeAsOf,
                        ),
                    profileHash = if (stored == null) null else "abc123def456",
                    sealed = !uploadPhase,
                    enabled = true,
                )
                .right()
        val controller =
            SubjectTrainingController(
                intake,
                stage2,
                mock(AdvocateRepository::class.java),
                mock(QuestionService::class.java),
                profiles,
            )
        return renderingMvc(controller)
            .perform(get("/s/training/profile").requestAttr(SubjectCtx.ATTR, ctx))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
    }

    @Test
    fun `the editable subject page renders its selects with the stored answers chosen`() {
        val html =
            subjectPage(
                uploadPhase = true,
                stored =
                    SubjectProfile(
                        subjectId = "s1",
                        country = "IN",
                        marketRegion = "EU",
                        currency = "INR",
                        knowledgeAsOf = LocalDate.parse("2026-07-15"),
                    ),
            )

        // Thymeleaf preserves the source's inter-attribute newlines, so structural assertions run
        // over whitespace-collapsed markup.
        val flat = html.replace(Regex("\\s+"), " ")

        assertTrue(flat.contains("<h1>Your details</h1>"))
        assertTrue(flat.contains("""action="/training/profile""""), "the form lost its action")
        // Stored answers come back selected — the round-trip a subject actually notices.
        assertTrue(flat.contains("""<option value="IN" selected="selected">India</option>"""))
        assertTrue(flat.contains("""name="knowledgeAsOf" value="2026-07-15""""))
        // The operator's market label is deliberately NOT posted back by this page: it is the one
        // field with no catalog behind it and it lands verbatim in {{locale}}, so a hidden input
        // would hand the subject a writable channel into every generation prompt. The controller
        // re-reads it from storage instead (SubjectTrainingController.saveProfile).
        assertFalse(
            flat.contains("""name="marketRegion""""),
            "the subject form re-opened the market label",
        )
        // Every catalog rendered.
        assertTrue(flat.contains("""<option value="Asia/Kolkata">Asia/Kolkata</option>"""))
        assertTrue(flat.contains("INR — Indian Rupee"))
    }

    @Test
    fun `the post-submit subject page is a summary with no form and no machinery words`() {
        val html =
            subjectPage(
                uploadPhase = false,
                stored = SubjectProfile(subjectId = "s1", country = "IN", currency = "INR"),
            )

        assertFalse(html.contains("""action="/training/profile""""), "a sealed page kept its form")
        assertFalse(html.contains("<select"), "a sealed page kept an editable control")

        val text = visibleText(html)
        assertTrue(text.contains("Where you're based"))
        assertTrue(text.contains("the India market (INR)"))
        assertTrue(text.contains("These are settled now that you've submitted"))
        // §12.3 sweep over everything the subject can actually read — markup stripped, because a
        // route like /training/profile is plumbing, not copy.
        for (banned in
            listOf("manifest", "seal", "locale", "profile", "stage4", "knowledge_as_of")) {
            assertFalse(
                text.lowercase().contains(banned),
                "'$banned' leaked into the subject page: ${text.take(400)}",
            )
        }
    }

    @Test
    fun `the editable subject page renders the C-D declared section, pre-filled, with the attestation`() {
        val html =
            subjectPage(
                uploadPhase = true,
                stored =
                    SubjectProfile(
                        subjectId = "s1",
                        aspirations = listOf("Optimising for staff-level IC work"),
                        employmentType = EmploymentType.FTE,
                        doNotDiscussChecks = listOf("health"),
                        doNotDiscussCustom =
                            DoNotDiscussCustom(
                                text = "my cap table",
                                state = DoNotDiscussApproval.PENDING,
                            ),
                    ),
            )
        val flat = html.replace(Regex("\\s+"), " ")

        // The declared inputs render, pre-filled from the stored doc.
        assertTrue(flat.contains("""name="aspirations""""))
        assertTrue(flat.contains("Optimising for staff-level IC work"))
        // The curated checklist renders its subject-safe labels, and a stored key comes back
        // checked — the round-trip a subject notices.
        assertTrue(flat.contains("Health and medical history"))
        assertTrue(flat.contains("""value="health" checked="checked""""))
        // The bespoke entry shows its PENDING state in subject words, plus the text.
        assertTrue(flat.contains("my cap table"))
        assertTrue(flat.contains("reviewing this one"), "the pending-state copy is missing")
        // The attestation checkbox is present and never pre-ticked (each declaring save
        // re-affirms).
        assertTrue(flat.contains("""name="declaredAttested" value="true""""))
        assertFalse(flat.contains("""name="declaredAttested" value="true" checked"""))

        // §12.3: the declared surface still carries no machinery vocabulary.
        val text = visibleText(html).lowercase()
        for (banned in listOf("materialise", "declaredtype", "subject_declared", "claim", "seal")) {
            assertFalse(text.contains(banned), "'$banned' leaked into the subject C/D page")
        }
    }

    // ---- VA-140: the shared subject footer (subject/layout.html :: foot) --------------

    /**
     * The S3 upload page — one of the twelve templates that render `subject/layout :: foot`, and
     * the one the dead-link scenario starts from. Rendered through the real advice so the footer
     * gate is exercised end to end.
     */
    private fun uploadPage(surfaceOn: Boolean): String {
        val intake = mock(IntakeService::class.java)
        val stage2 = mock(Stage2Service::class.java)
        // Unsealed, nothing started ⇒ TrainingPhase.UPLOAD, so upload() renders rather than
        // bounces.
        `when`(intake.manifest("s1")).thenReturn(IntakeManifest(id = "s1", subjectId = "s1"))
        `when`(stage2.listJobs("s1")).thenReturn(emptyList())
        `when`(intake.listAssets("s1")).thenReturn(emptyList())
        val controller =
            SubjectTrainingController(
                intake,
                stage2,
                mock(AdvocateRepository::class.java),
                mock(QuestionService::class.java),
                FakeRenderProfileService(),
            )
        return renderingMvc(controller, advice(surfaceOn))
            .perform(get("/s/training/upload").requestAttr(SubjectCtx.ATTR, ctx))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
    }

    @Test
    fun `the shared footer carries Your details while the surface is on`() {
        assertTrue(
            uploadPage(surfaceOn = true).contains("""href="/training/profile""""),
            "the footer lost its profile link",
        )
    }

    @Test
    fun `with the surface off the footer link is absent, not a dead end`() {
        // `app.stage4.profile-enabled` defaults off (AppProperties.Stage4) and is set true only in
        // the dev profile — so prod is the ungated case, and dev, the only place the owner's
        // browser pass runs, is the one profile that cannot reproduce it. GET /training/profile
        // redirects to /training whenever the flag is down, so a link rendered here is a
        // permanently visible nav item that silently bounces the subject on every signed-in page.
        val html = uploadPage(surfaceOn = false)

        assertFalse(html.contains("""href="/training/profile""""), "a dead profile link rendered")
        assertFalse(html.contains("Your details"), "the dead link kept its label")
        // The rest of the footer is untouched — this gate hides one item, not the nav.
        assertTrue(html.contains("""href="/tokens""""))
        assertTrue(html.contains("""href="/training""""))
    }

    /** Rendered page → what a reader sees: tags dropped, the entities Thymeleaf emits restored. */
    private fun visibleText(html: String): String =
        html
            .replace(Regex("<[^>]*>"), " ")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")

    // ---- VA-141: the intake/detail.html panel -----------------------------------------

    private val defaultDetailStored =
        SubjectProfile(
            subjectId = "s1",
            country = "IN",
            currency = "INR",
            timezone = "Asia/Kolkata",
            updatedBy = "op@example.com",
        )

    private fun detailPage(
        sealed: Boolean,
        enabled: Boolean = true,
        stored: SubjectProfile = defaultDetailStored,
        stage2Started: Boolean = false,
    ): String {
        val subjectService = mock(SubjectService::class.java)
        val intake = mock(IntakeService::class.java)
        val scores = mock(SubjectScoreRepository::class.java)
        val personas = mock(SubjectPersonaRepository::class.java)
        val profiles = FakeRenderProfileService()
        `when`(subjectService.get("s1"))
            .thenReturn(Subject(id = "s1", displayName = "Asha", handle = "asha"))
        `when`(intake.listAssets("s1")).thenReturn(emptyList())
        `when`(intake.manifest("s1"))
            .thenReturn(
                IntakeManifest(
                    id = "s1",
                    subjectId = "s1",
                    sealed = sealed,
                    stage2StartedAt = if (stage2Started) Instant.now() else null,
                    // sealedBy/sealedAt are derived from this audit trail, not stored.
                    sealEvents =
                        if (!sealed) emptyList()
                        else
                            listOf(
                                SealEvent(
                                    action = SealAction.SEAL,
                                    actor = "op@example.com",
                                    at = Instant.now(),
                                    note = "Ready for Stage 2",
                                )
                            ),
                )
            )
        `when`(scores.find("s1")).thenReturn(null)
        `when`(personas.findBySubject("s1")).thenReturn(null)
        profiles.viewResult =
            SubjectProfileView(
                    subjectId = "s1",
                    stored = stored,
                    // The panel's read-only (sealed) view renders `resolved`, so derive it from the
                    // stored doc exactly as the real service would — C/D included. Deliberately
                    // kept
                    // non-blank even when `enabled = false`: the flag-off tests hand in this
                    // "pre-fix
                    // shape" (non-blank resolved, non-null hash) to prove the *template's* own gate
                    // hides them, independently of the service blanking ahead of it.
                    resolved = SubjectProfileDefaults.resolve(stored),
                    profileHash = "abc123def456789",
                    sealed = sealed,
                    enabled = enabled,
                )
                .right()
        val controller =
            IntakeController(
                subjectService,
                intake,
                mock(Stage2Service::class.java),
                scores,
                personas,
                mock(ProvisioningService::class.java),
                mock(TokenService::class.java),
                mock(UserService::class.java),
                mock(OpsCounterRepository::class.java),
                profiles,
            )
        return renderingMvc(controller)
            .perform(get("/intake/s1"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
    }

    @Test
    fun `the unsealed detail page offers the profile panel as its own form`() {
        val html = detailPage(sealed = false)

        assertTrue(html.contains("Profile · locale &amp; freshness"))
        assertTrue(html.contains("""action="/intake/s1/profile""""), "the profile form is missing")
        // The identity form is still there and still separate — the §7.2 split.
        assertTrue(html.contains("""action="/intake/s1/edit""""))
        assertTrue(html.contains("""<option value="IN" selected="selected">India</option>"""))
        assertTrue(html.contains("abc123def456"))
    }

    @Test
    fun `the sealed detail page shows the profile read-only and routes to unseal`() {
        val html = detailPage(sealed = true)

        assertFalse(
            html.contains("""action="/intake/s1/profile""""),
            "a sealed manifest still offered the profile form",
        )
        assertTrue(html.contains("The profile is frozen with the corpus."))
        assertTrue(html.contains("Unseal (admin)"), "the sealed panel lost its edit route")
        // The read-only table renders resolved values, em-dash for the unanswered ones.
        assertTrue(html.contains("Asia/Kolkata"))
        assertTrue(html.contains("<td>Market region</td>"))
        // …and identity stays editable even while sealed (that panel is deliberately not gated).
        assertTrue(html.contains("""action="/intake/s1/edit""""))
    }

    @Test
    fun `with the flag off the panel names the switch instead of offering a doomed save`() {
        val html = detailPage(sealed = false, enabled = false)

        assertFalse(html.contains("""action="/intake/s1/profile""""))
        assertTrue(html.contains("app.stage4.profile-enabled"))
    }

    @Test
    fun `with the flag off the panel claims no injection and prints no locale or hash`() {
        // The scenario: the profile was filled and the subject sealed and published while the flag
        // was up; the flag is later switched off (the documented rollback lever). Generation then
        // injects nothing and stamps no profileHash — Stage4Service short-circuits on a blank
        // profile — so a panel still showing a resolved {{locale}} line and a hash tells the
        // operator the next run is locale-conditioned and hash-pinned when the prompt is
        // byte-for-byte legacy: the §6.4 drift-axis confusion, from the one surface meant to
        // prevent it.
        //
        // The view handed in here is deliberately the *pre-fix* shape — non-blank `resolved`,
        // non-null hash — which SubjectProfileService.viewOf no longer produces. That is the point:
        // this pins the template's own gate, independently of the service blanking ahead of it.
        val flat = detailPage(sealed = true, enabled = false).replace(Regex("\\s+"), " ")

        assertFalse(flat.contains("the India market (INR)"), "a locale line no run will use")
        assertFalse(flat.contains("abc123def456"), "a hash no run is pinned to")
        assertFalse(flat.contains("<td>Market region</td>"), "the frozen-values table rendered")
        assertFalse(
            flat.contains("Injected into Stage 4 generation"),
            "the panel claimed an injection that does not happen",
        )
        // …and says so plainly, in operator vocabulary (this surface keeps the machinery words).
        assertTrue(flat.contains("nothing is injected into generation"))
        assertTrue(flat.contains("app.stage4.profile-enabled"))
    }

    // ---- VA-150: the admin C/D panel + approve/reject control -------------------------

    private val declaredStored =
        SubjectProfile(
            subjectId = "s1",
            country = "IN",
            targetRoles = listOf("Staff Engineer"),
            employmentType = EmploymentType.FTE,
            aspirations = listOf("Optimising for staff-level IC work"),
            doNotDiscussChecks = listOf("health"),
            doNotDiscussCustom =
                DoNotDiscussCustom(text = "my cap table", state = DoNotDiscussApproval.PENDING),
            updatedBy = "op@example.com",
        )

    @Test
    fun `the unsealed admin panel renders the C-D inputs and the pending approve or reject control`() {
        val flat = detailPage(sealed = false, stored = declaredStored).replace(Regex("\\s+"), " ")

        // C/D inputs on the seal-gated form, pre-filled.
        assertTrue(flat.contains("""name="targetRoles""""))
        assertTrue(flat.contains("Staff Engineer"))
        assertTrue(flat.contains("""name="aspirations""""))
        assertTrue(flat.contains("""name="doNotDiscussChecks""""))
        assertTrue(flat.contains("""value="health" checked="checked""""))
        // The approve/reject control for the bespoke entry, posting to its own audited route.
        assertTrue(flat.contains("""action="/intake/s1/profile/dnd-custom""""))
        assertTrue(flat.contains("Approve boundary"))
        assertTrue(flat.contains("my cap table"))
    }

    @Test
    fun `a sealed panel drops the C-D edit form but keeps the approve control`() {
        val flat = detailPage(sealed = true, stored = declaredStored).replace(Regex("\\s+"), " ")

        // The profile EDIT form is seal-gated away — note the trailing quote, so the dnd-custom
        // route (which shares the prefix) does not false-match it.
        assertFalse(
            flat.contains("""action="/intake/s1/profile""" + "\""),
            "a sealed manifest still offered the C/D edit form",
        )
        // …but the approve/reject control stays: a verdict on frozen text is legitimate until
        // Stage 2 consumes the corpus (§12.8.8).
        assertTrue(flat.contains("""action="/intake/s1/profile/dnd-custom""""))
        assertTrue(flat.contains("Approve boundary"))
        // Sealed read-only C/D rows render from the resolved profile.
        assertTrue(flat.contains("<td>Target roles</td>"))
        assertTrue(flat.contains("Staff Engineer"))
    }

    @Test
    fun `once Stage 2 has consumed the corpus the approve control is gone, with a reason`() {
        val flat =
            detailPage(sealed = true, stored = declaredStored, stage2Started = true)
                .replace(Regex("\\s+"), " ")

        // The frozen-corpus guard (§12.8.8) reaches the UI: no verdict button, and the panel says
        // why rather than offering an action the service would only refuse.
        assertFalse(flat.contains("Approve boundary"), "a doomed approve control rendered")
        assertTrue(flat.contains("Stage 2 has consumed the corpus"))
    }
}

/** A [SubjectProfileService] whose `view` is scripted; nothing here writes. */
private class FakeRenderProfileService :
    SubjectProfileService(
        liveConfig(),
        SubjectProfileRepository(mock(Firestore::class.java)),
        SubjectRepository(mock(Firestore::class.java)),
        IntakeManifestRepository(mock(Firestore::class.java)),
        ProfileClaimMaterialiser(
            liveConfig(),
            SubjectProfileRepository(mock(Firestore::class.java)),
            ClaimRepository(mock(Firestore::class.java)),
        ),
    ) {

    lateinit var viewResult: Either<DomainError, SubjectProfileView>

    override fun view(subjectId: String): Either<DomainError, SubjectProfileView> = viewResult
}

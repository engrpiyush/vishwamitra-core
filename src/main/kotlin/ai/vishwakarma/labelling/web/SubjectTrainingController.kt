package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.QuestionService
import ai.vishwakarma.labelling.service.Stage2Service
import ai.vishwakarma.labelling.service.SubjectProfileService
import ai.vishwakarma.labelling.service.SubjectProfileUpdateRequest
import ai.vishwakarma.labelling.web.SubjectTraining.TrainingPhase
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The subject training surface (VA-32, product LLD §8.1 S2–S5): phase-aware home, upload with the
 * F2 consent attestation, progress with §8.2 friendly states, and the speaker helper — thin
 * Thymeleaf wrappers over [IntakeService]/[Stage2Service]. Mappings live under the internal `/s`
 * prefix (SubjectHostFilter rewrites subject-host paths); authorization is the subject chain's
 * training rule (subject-of-host ∨ operator); services re-assert subject scope (§12.1). Copy rule:
 * everything pipeline-flavored renders through [SubjectTraining] — no machinery vocabulary here or
 * in the templates (§12.3).
 */
@Controller
@RequestMapping("/s/training")
class SubjectTrainingController(
    private val intake: IntakeService,
    private val stage2: Stage2Service,
    private val advocates: AdvocateRepository,
    private val questions: QuestionService,
    private val profiles: SubjectProfileService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun ctx(request: HttpServletRequest): SubjectCtx =
        SubjectCtx.of(request) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    // ---- S2: training home (phase-aware) -----------------------------------

    @GetMapping
    fun home(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        val manifest = intake.manifest(ctx.subjectId)
        val jobs = stage2.listJobs(ctx.subjectId)
        val assets = intake.listAssets(ctx.subjectId)
        val phase = SubjectTraining.phaseFor(manifest, jobs)
        model.addAttribute("pageTitle", "Training — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("phase", phase.name)
        model.addAttribute("uploadCount", assets.size)
        model.addAttribute(
            "readyCount",
            assets.count { it.uploadStatus == AssetUploadStatus.STORED }
        )
        model.addAttribute(
            "needsYou",
            jobs.count { it.status == Stage2JobStatus.AWAITING_SPEAKER_SELECTION },
        )
        // F11 (VA-35): the S2 inbox badge — shown whenever OPEN questions exist.
        model.addAttribute("openQuestions", questions.openCount(ctx.subjectId))
        // VA-140: the "Your details" CTA sits inside the template's UPLOAD block and is gated on
        // `profileEnabled` — supplied for *every* page by [GlobalModelAdvice], because the same
        // flag gates the permanent footer link. Deliberately not set here: a second, phase-narrowed
        // value under the same name would shadow the advice's and blank the footer link on this
        // page alone.

        // Post-submit S2 (read-only): the uploads list + tool links (§8.1), and the §10
        // evidence-strength card (VA-44) — scoring only means anything once training is in.
        if (phase == TrainingPhase.SUBMITTED) {
            model.addAttribute("uploads", assets.map { it.title })
            val advocate = advocates.find(ctx.subjectId)
            model.addAttribute("score", advocate?.evidenceStrength)
            model.addAttribute("scoredClaims", advocate?.scoredClaimCount ?: 0)
        }
        return "subject/training/home"
    }

    // ---- S3: upload with consent attestation --------------------------------

    @GetMapping("/upload")
    fun upload(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        val manifest = intake.manifest(ctx.subjectId)
        val phase = SubjectTraining.phaseFor(manifest, stage2.listJobs(ctx.subjectId))
        if (phase != TrainingPhase.UPLOAD) return "redirect:/training"
        val assets = intake.listAssets(ctx.subjectId)
        model.addAttribute("pageTitle", "Add your evidence — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("kinds", SubjectAssetKind.entries)
        model.addAttribute("fromOptions", SubjectFrom.entries)
        model.addAttribute(
            "rows",
            assets.map { a ->
                UploadRow(
                    id = a.id,
                    title = a.title,
                    status = SubjectTraining.uploadLabel(a.uploadStatus),
                    ready = a.uploadStatus == AssetUploadStatus.STORED,
                )
            },
        )
        model.addAttribute(
            "readyCount",
            assets.count { it.uploadStatus == AssetUploadStatus.STORED }
        )
        return "subject/training/upload"
    }

    /** One row of the pre-submit uploads list — already friendly-mapped. */
    data class UploadRow(val id: String, val title: String, val status: String, val ready: Boolean)

    // ---- VA-140: the A/B profile form (profile LLD §7.1) ---------------------

    /**
     * "Your details" — the subject's own A/B block (locale + knowledge freshness), sibling of the
     * S3 upload page and written through the same [SubjectProfileService.put] the operator panel
     * uses, so the §6.1 seal gate is the one control on both.
     *
     * **Divergence from profile LLD §7.1, deliberate.** That section says to copy [upload]'s guard,
     * which *redirects away* unless `phase == UPLOAD`; its very next bullet says the page becomes a
     * "read-only summary" post-submit and puts a permanent "Your details" link in the subject
     * footer, which renders on every signed-in page including the post-submit ones. A literal
     * redirect would make that link a dead end for the whole rest of the subject's life. So the
     * guard moves to the write: the GET always renders and flips to a summary, and [saveProfile]
     * (plus the service's own seal check) is what actually refuses. `phase == UPLOAD` already
     * implies an unsealed manifest (`SubjectTraining.phaseFor` :39), so the two gates agree.
     *
     * With the surface off (`app.stage4.profile-enabled`) there is nothing to show and no legal
     * write, so the page steps aside rather than explaining a config key to a subject (§12.3). That
     * redirect is a **backstop for a hand-typed URL, not a route a subject can walk into**: both
     * entry points — the home CTA and the footer link in `subject/layout.html` — hang off the same
     * flag via [GlobalModelAdvice], because a visible link that silently bounces is a dead end on
     * every signed-in page, and the flag defaults off outside the dev profile (`SP-CONFIG`).
     */
    @GetMapping("/profile")
    fun profile(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        val view = profiles.view(ctx.subjectId).fold({ null }, { it })
        if (view == null || !view.enabled) return "redirect:/training"
        val manifest = intake.manifest(ctx.subjectId)
        val phase = SubjectTraining.phaseFor(manifest, stage2.listJobs(ctx.subjectId))
        model.addAttribute("pageTitle", "Your details — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("editable", phase == TrainingPhase.UPLOAD)
        model.addAttribute("stored", view.stored)
        model.addAttribute("summary", SubjectProfileForm.summary(view.resolved))
        model.addAttribute("countries", SubjectProfileForm.countries)
        model.addAttribute("currencies", SubjectProfileForm.currencies)
        model.addAttribute("languages", SubjectProfileForm.languages)
        model.addAttribute("timezones", SubjectProfileForm.timezones)
        // VA-149: the C/D declared-evidence catalogs — the C engagement/seniority dropdowns and the
        // curated do-not-discuss checklist. Everything else the C/D section needs (the roles /
        // aspirations / preferences lines and the bespoke entry's approval state) is read off
        // `stored`, already on the model.
        model.addAttribute("employmentTypes", SubjectProfileForm.employmentTypes)
        model.addAttribute("seniorities", SubjectProfileForm.seniorities)
        model.addAttribute("dndTopics", SubjectProfileForm.doNotDiscussTopics)
        return "subject/training/profile"
    }

    /**
     * Save the A/B answers. Every value the form can post comes from a [SubjectProfileForm] catalog
     * drawn from the same JDK tables the service validates against, so a refusal here means the
     * seal closed underneath the page, not a bad answer — and either way the subject reads
     * [SubjectProfileForm.friendly], never the service string (§12.3).
     *
     * `marketRegion` is **read back from storage, never from the request.**
     * [SubjectProfileService.put] rewrites the whole doc, so an operator's market label has to ride
     * along or a subject saving their timezone would silently erase it (§7.2 — the field is
     * operator-only vocabulary and has no box on this page). It cannot ride as a hidden input: it
     * is the one profile field with no catalog behind it, it outranks `country` in the `{{locale}}`
     * line (`domain/SubjectProfile.kt:77-84`) and that line is substituted verbatim into every
     * generation prompt, so a hidden field here would be a subject-writable channel into the prompt
     * — precisely what validating the other five fields against ISO/IANA tables prevents.
     * Re-reading the stored value costs one read and closes it; `put`'s own bound on the field
     * (`SubjectProfileService.kt`, `marketRegion`) is the backstop for every other caller.
     */
    @PostMapping("/profile")
    fun saveProfile(
        request: HttpServletRequest,
        @RequestParam(required = false) country: String?,
        @RequestParam(required = false) currency: String?,
        @RequestParam(required = false) primaryLanguage: String?,
        @RequestParam(required = false) timezone: String?,
        @RequestParam(required = false) knowledgeAsOf: String?,
        // ---- C/D declared evidence (VA-149). Scalars bind as @RequestParam; the LIST fields are
        // read off the request below, never as @RequestParam List<String> — see the body. ----
        @RequestParam(required = false) targetSeniority: String?,
        @RequestParam(required = false) employmentType: String?,
        /**
         * Tri-state select, forwarded verbatim: "true"/"false" set the stance, the empty "Prefer
         * not to say" option ("") clears it, and an absent param keeps what is stored (§7). Parsed
         * by [SubjectProfileService.put], never here — this surface only relays the raw choice.
         */
        @RequestParam(required = false) openToRelocation: String?,
        @RequestParam(required = false) doNotDiscussCustom: String?,
        /**
         * The declared-narrative attestation checkbox (§7.1); the C/D write is refused without it.
         */
        @RequestParam(required = false) declaredAttested: Boolean?,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        // The declared LIST fields are read straight off the request, never as
        // `@RequestParam List<String>`: Spring's single-value binding comma-splits one box, and a
        // declared line ("Optimising for staff-level IC work, not management") legitimately carries
        // commas — a split would shatter one aspiration into two. getParameterValues returns
        // exactly
        // the boxes as submitted, unsplit; the service still trims, drops blanks and de-dupes.
        val targetRoles = request.getParameterValues("targetRoles")?.toList()
        val aspirations = request.getParameterValues("aspirations")?.toList()
        val statedPreferences = request.getParameterValues("statedPreferences")?.toList()
        val doNotDiscussChecks = request.getParameterValues("doNotDiscussChecks")?.toList()
        val manifest = intake.manifest(ctx.subjectId)
        val phase = SubjectTraining.phaseFor(manifest, stage2.listJobs(ctx.subjectId))
        if (phase != TrainingPhase.UPLOAD) return "redirect:/training/profile"

        // The F2-shaped attestation gate (§7.1). A submit that carries **any** declared C/D content
        // is refused server-side unless the subject ticked the "these details are true and I want
        // my
        // advocate to speak for me on them" box — a required checkbox that blocks the write,
        // exactly
        // like the upload-consent attestation one slice over
        // (`SubjectTrainingApiController.register`
        // → `IntakeService.attestConsent`). A pure A/B (locale) save carries no declaration and
        // needs
        // no attestation, unchanged from session 02; withdrawing declarations (all C/D blank) needs
        // none either. The checkbox is deliberately never pre-ticked, so a save that re-submits a
        // pre-filled declaration re-affirms it — the per-batch posture consent already uses.
        val declaring =
            declaresContent(
                targetRoles,
                targetSeniority,
                employmentType,
                openToRelocation,
                aspirations,
                statedPreferences,
                doNotDiscussChecks,
                doNotDiscussCustom,
            )
        if (declaring && declaredAttested != true) {
            ra.addFlashAttribute("error", DECLARED_ATTEST_REQUIRED)
            return "redirect:/training/profile"
        }

        val storedMarket = profiles.view(ctx.subjectId).fold({ null }, { it.stored?.marketRegion })
        profiles
            .put(
                ctx.subjectId,
                SubjectProfileUpdateRequest(
                    country = country,
                    marketRegion = storedMarket,
                    currency = currency,
                    timezone = timezone,
                    primaryLanguage = primaryLanguage,
                    knowledgeAsOf = knowledgeAsOf,
                    // This form renders every C/D input, so a submit is the whole C/D intent: an
                    // absent list means "none", not "keep". Coalesce the lists null→empty so
                    // unticking the last do-not-discuss box actually clears the field — an
                    // unchecked
                    // checkbox posts nothing, which the service would otherwise read as "keep
                    // stored" (§7 request KDoc). The scalar selects likewise post their empty
                    // "Prefer not to say" option straight through as a blank: the service reads a
                    // present blank as a clear, so choosing it withdraws a stored stance rather
                    // than
                    // silently re-asserting it. openToRelocation rides as raw text for the same
                    // reason — parseTriState below is only the attestation gate's read of it.
                    targetRoles = targetRoles ?: emptyList(),
                    targetSeniority = targetSeniority,
                    employmentType = employmentType,
                    openToRelocation = openToRelocation,
                    aspirations = aspirations ?: emptyList(),
                    statedPreferences = statedPreferences ?: emptyList(),
                    doNotDiscussChecks = doNotDiscussChecks ?: emptyList(),
                    doNotDiscussCustom = doNotDiscussCustom,
                ),
                CurrentUser.email(),
            )
            .fold(
                { err ->
                    log.warn("Subject profile save refused for {}: {}", ctx.subjectId, err.message)
                    ra.addFlashAttribute("error", SubjectProfileForm.friendly(err))
                },
                {
                    // Stamp the declared-narrative attestation only on a save that actually carried
                    // a
                    // declaration, and only once the write succeeded — the audited manifest twin of
                    // consentAttested (§7.1). A locale-only save leaves it untouched.
                    if (declaring) intake.attestDeclared(ctx.subjectId, CurrentUser.email())
                    ra.addFlashAttribute("ok", "Saved — thanks, that helps your advocate.")
                },
            )
        return "redirect:/training/profile"
    }

    /**
     * True when the request carries a real group-C/D declaration — any target role/aspiration/
     * preference/do-not-discuss entry with non-blank text, a seniority/engagement/relocation
     * choice, or a bespoke boundary. Blank list boxes (the form always posts its inputs, empty or
     * not) do not count; this is what the §7.1 attestation gates.
     */
    private fun declaresContent(
        targetRoles: List<String>?,
        targetSeniority: String?,
        employmentType: String?,
        openToRelocation: String?,
        aspirations: List<String>?,
        statedPreferences: List<String>?,
        doNotDiscussChecks: List<String>?,
        doNotDiscussCustom: String?,
    ): Boolean =
        targetRoles.orEmpty().any { it.isNotBlank() } ||
            !targetSeniority.isNullOrBlank() ||
            !employmentType.isNullOrBlank() ||
            parseTriState(openToRelocation) != null ||
            aspirations.orEmpty().any { it.isNotBlank() } ||
            statedPreferences.orEmpty().any { it.isNotBlank() } ||
            doNotDiscussChecks.orEmpty().any { it.isNotBlank() } ||
            !doNotDiscussCustom.isNullOrBlank()

    /**
     * "true"/"false" → the boolean; anything else (the blank "Prefer not to say" option, or an
     * absent param) → null. Used only by [declaresContent] to decide whether a relocation *choice*
     * was made and so needs the §7.1 attestation — a blank is a withdrawal, not a declaration. The
     * keep-vs-clear decision lives in [SubjectProfileService.put], which reads the raw string.
     */
    private fun parseTriState(raw: String?): Boolean? =
        when (raw?.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /** Remove an upload before submit (also how a duplicate-file submit blocker is resolved). */
    @PostMapping("/assets/{assetId}/remove")
    fun removeAsset(
        request: HttpServletRequest,
        @PathVariable assetId: String,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        val asset = intake.getAsset(assetId)
        if (asset == null || asset.subjectId != ctx.subjectId)
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        intake
            .deleteAsset(assetId)
            .fold(
                {
                    log.warn("Subject remove refused for asset {}: {}", assetId, it.message)
                    ra.addFlashAttribute("error", "We couldn't remove that one — try again.")
                },
                { ra.addFlashAttribute("ok", "Removed.") },
            )
        return "redirect:/training/upload"
    }

    /**
     * "I'm done uploading" — seals the manifest (subject-authored note) and starts processing in
     * one action. Idempotent-ish: an already-sealed-but-not-started manifest (a submit that failed
     * halfway) skips the seal and just re-processes.
     */
    @PostMapping("/submit")
    fun submitUploads(request: HttpServletRequest, ra: RedirectAttributes): String {
        val ctx = ctx(request)
        val actor = CurrentUser.email()
        val manifest = intake.manifest(ctx.subjectId)
        if (manifest.reviewSubmittedAt != null || manifest.stage2StartedAt != null)
            return "redirect:/training"
        if (!manifest.sealed) {
            friendlySubmitBlocker(ctx.subjectId)?.let {
                ra.addFlashAttribute("error", it)
                return "redirect:/training/upload"
            }
            val sealError =
                intake
                    .sealManifest(
                        ctx.subjectId,
                        actor,
                        "Subject submitted their uploads (self-serve, VA-32)",
                    )
                    .fold({ it }, { null })
            if (sealError != null) {
                log.warn("Subject seal refused for {}: {}", ctx.subjectId, sealError.message)
                ra.addFlashAttribute("error", GENERIC_SORRY)
                return "redirect:/training/upload"
            }
        }
        return stage2
            .process(ctx.subjectId, actor)
            .fold(
                { err ->
                    // Operator notification hook (log-based until §11.3 wiring): a subject-facing
                    // start failure needs an operator to look at it.
                    log.warn(
                        "OPERATOR ATTENTION: subject submit could not start processing for {}: {}",
                        ctx.subjectId,
                        err.message,
                    )
                    ra.addFlashAttribute("error", GENERIC_SORRY)
                    "redirect:/training/upload"
                },
                {
                    ra.addFlashAttribute("ok", "That's everything — we're on it.")
                    "redirect:/training/progress"
                },
            )
    }

    /**
     * The friendly face of the seal blockers a subject can actually cause (§8 copy rule — the raw
     * blocker strings carry asset ids and never render): unfinished uploads and duplicate files.
     * Anything else is logged for the operator and rendered generically.
     */
    private fun friendlySubmitBlocker(subjectId: String): String? {
        val assets = intake.listAssets(subjectId)
        if (assets.none { it.uploadStatus == AssetUploadStatus.STORED })
            return "Add at least one file before submitting."
        if (
            assets.any {
                it.modality != AssetModality.LINK &&
                    it.consentStatus != ConsentStatus.REVOKED &&
                    (it.uploadStatus == AssetUploadStatus.AWAITING_UPLOAD ||
                        it.uploadStatus == AssetUploadStatus.FAILED)
            }
        )
            return "Some files haven't finished uploading — retry or remove them, then submit again."
        val duplicates =
            assets
                .filter {
                    it.uploadStatus == AssetUploadStatus.STORED && !it.checksum.isNullOrBlank()
                }
                .groupBy { it.checksum }
                .values
                .any { it.size > 1 }
        if (duplicates)
            return "Two of your uploads look like the same file — remove one, then submit again."
        return null
    }

    // ---- S4: progress --------------------------------------------------------

    @GetMapping("/progress")
    fun progress(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        val manifest = intake.manifest(ctx.subjectId)
        val jobs = stage2.listJobs(ctx.subjectId)
        val phase = SubjectTraining.phaseFor(manifest, jobs)
        if (phase == TrainingPhase.UPLOAD) return "redirect:/training"
        val titles = intake.listAssets(ctx.subjectId).associate { it.id to it.title }
        val friendly = jobs.map { SubjectTraining.friendlyJob(it, titles[it.assetId] ?: "Upload") }
        model.addAttribute("pageTitle", "Progress — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("phase", phase.name)
        model.addAttribute("items", friendly)
        model.addAttribute("longRunning", SubjectTraining.longRunningBanner(jobs))
        // The open page drives the run (existing idiom): fingerprint + active count for the poll
        // script; friendly-state names only.
        model.addAttribute(
            "activeCount",
            jobs.count {
                it.status != Stage2JobStatus.COMPLETED &&
                    it.status != Stage2JobStatus.FAILED &&
                    it.status != Stage2JobStatus.AWAITING_SPEAKER_SELECTION
            },
        )
        model.addAttribute(
            "stateFingerprint",
            friendly.map { "${it.jobId}:${it.state.name}" }.sorted().joinToString(","),
        )
        return "subject/training/progress"
    }

    // ---- S5: speaker helper ---------------------------------------------------

    @GetMapping("/speakers/{jobId}")
    fun speakers(
        request: HttpServletRequest,
        @PathVariable jobId: String,
        model: Model,
    ): String {
        val ctx = ctx(request)
        val job = stage2.job(jobId)
        if (job == null || job.subjectId != ctx.subjectId)
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (job.status != Stage2JobStatus.AWAITING_SPEAKER_SELECTION)
            return "redirect:/training/progress"
        val samples = stage2.speakerSamples(job) ?: emptyMap()
        model.addAttribute("pageTitle", "Who's speaking? — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("jobId", job.id)
        model.addAttribute("samples", samples)
        return "subject/training/speakers"
    }

    @PostMapping("/speakers/{jobId}")
    fun assignSpeakers(
        request: HttpServletRequest,
        @PathVariable jobId: String,
        @RequestParam(name = "self", required = false) self: List<String>?,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        val job = stage2.job(jobId)
        if (job == null || job.subjectId != ctx.subjectId)
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        stage2
            .resolveSpeakers(jobId, self ?: emptyList())
            .fold(
                { err ->
                    log.warn(
                        "OPERATOR ATTENTION: speaker selection failed for job {} ({}): {}",
                        jobId,
                        ctx.subjectId,
                        err.message,
                    )
                    ra.addFlashAttribute("error", GENERIC_SORRY)
                },
                { ra.addFlashAttribute("ok", "Thanks — picking up where we left off.") },
            )
        return "redirect:/training/progress"
    }

    companion object {
        /** The one all-purpose apology (§12.3: verbatim errors never render to a subject). */
        const val GENERIC_SORRY = "Something didn't go to plan on our side — we're looking into it."

        /** Shown when a declaration is submitted without ticking the §7.1 attestation. */
        const val DECLARED_ATTEST_REQUIRED =
            "Before we save what you're looking for, please tick the box to confirm it's true and " +
                "that you want your advocate to speak for you on it."
    }
}

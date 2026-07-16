package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.Stage2Service
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
    }
}

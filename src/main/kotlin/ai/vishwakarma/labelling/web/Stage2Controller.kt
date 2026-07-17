package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.ClaimReviewService
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.QuestionService
import ai.vishwakarma.labelling.service.Stage2Service
import ai.vishwakarma.labelling.service.SubjectService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Stage 2 (A/V → Claims) server-rendered UI, under the intake area ([IntakeController] conventions:
 * REVIEWER-gated Thymeleaf MVC, POST-redirect-GET). One page per subject shows the run button, the
 * per-asset job table, and the extracted claims; while jobs are active a small script
 * (static/js/stage2-poll.js) keeps polling `/api/stage2/subjects/{id}/poll-all` so the run drives
 * itself to completion with the page open — there is no server-side scheduler.
 */
@Controller
@RequestMapping("/intake")
@PreAuthorize("hasRole('REVIEWER')")
class Stage2Controller(
    private val subjectService: SubjectService,
    private val intake: IntakeService,
    private val stage2: Stage2Service,
    private val reviewService: ClaimReviewService,
    private val questions: QuestionService,
) {

    private fun actor(): String? = CurrentUser.email()

    @GetMapping("/{id}/stage2")
    fun page(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val jobs = stage2.listJobs(id)
        model.addAttribute("pageTitle", "Stage 2 · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("manifest", intake.manifest(id))
        model.addAttribute("jobs", jobs)
        model.addAttribute("claims", stage2.listClaims(id))
        model.addAttribute("assetTitles", intake.listAssets(id).associate { it.id to it.title })
        // AWAITING_SPEAKER_SELECTION is non-terminal but *not* auto-advancing — it waits for the
        // operator — so it must not keep the auto-poll loop spinning (§12.4).
        model.addAttribute(
            "activeCount",
            jobs.count { !it.status.terminal() && it.status.name != "AWAITING_SPEAKER_SELECTION" },
        )
        // §12.4 Phase B: option lists for the per-job speaker→role editor.
        model.addAttribute("speakerRoleValues", SpeakerRole.entries)
        model.addAttribute("relationshipValues", Relationship.entries)
        // §12.4: per-speaker transcript for the selection UIs (stored, else loaded on demand), so
        // "View transcript" works even on jobs that completed before samples were kept.
        model.addAttribute(
            "speakerSamplesByJob",
            jobs
                .filter { it.speakerRoles != null }
                .associate { it.id to stage2.speakerSamples(it) },
        )
        // Fingerprint of the rendered job states; the auto-poll script reloads when it changes.
        model.addAttribute(
            "jobStatuses",
            jobs.map { "${it.id}:${it.status}" }.sorted().joinToString(","),
        )
        // §12.6: claim review can begin once every job has settled (COMPLETED / FAILED).
        model.addAttribute("reviewReady", jobs.isNotEmpty() && jobs.all { it.status.terminal() })
        return "intake/stage2"
    }

    @PostMapping("/{id}/stage2/process")
    fun process(@PathVariable id: String, ra: RedirectAttributes): String {
        stage2
            .process(id, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Stage 2 started — ${it.size} job(s) submitted") },
            )
        return "redirect:/intake/$id/stage2"
    }

    /** Manual poll-everything fallback (the auto-poll script does this continuously). */
    @PostMapping("/{id}/stage2/poll")
    fun pollAll(@PathVariable id: String, ra: RedirectAttributes): String {
        val active = stage2.pollAll(id).count { !it.status.terminal() }
        ra.addFlashAttribute(
            "ok",
            if (active == 0) "All jobs settled" else "$active job(s) still running",
        )
        return "redirect:/intake/$id/stage2"
    }

    @PostMapping("/stage2/jobs/{jobId}/retry")
    fun retryJob(@PathVariable jobId: String, ra: RedirectAttributes): String {
        val subjectId =
            stage2.job(jobId)?.subjectId
                ?: run {
                    ra.addFlashAttribute("error", "Job not found")
                    return "redirect:/intake"
                }
        stage2
            .retryJob(jobId)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Retrying — job is ${it.status}") },
            )
        return "redirect:/intake/$subjectId/stage2"
    }

    /**
     * Re-run a COMPLETED job: mode=extract reuses the stored transcript, mode=full re-transcribes.
     */
    @PostMapping("/stage2/jobs/{jobId}/rerun")
    fun rerunJob(
        @PathVariable jobId: String,
        @RequestParam(required = false, defaultValue = "extract") mode: String,
        ra: RedirectAttributes,
    ): String {
        val subjectId =
            stage2.job(jobId)?.subjectId
                ?: run {
                    ra.addFlashAttribute("error", "Job not found")
                    return "redirect:/intake"
                }
        stage2
            .rerunJob(jobId, full = mode == "full")
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Re-run — job is ${it.status}") },
            )
        return "redirect:/intake/$subjectId/stage2"
    }

    /**
     * §12.4 Phase B: save the operator's speaker→role binding for a COMPLETED A/V job, then
     * re-extract. The dialog posts parallel [label]/[role]/[relationship]/[name] arrays (one entry
     * per diarized speaker); rows with a blank/invalid role are skipped, and a relationship is kept
     * only for ENDORSER rows.
     */
    @PostMapping("/stage2/jobs/{jobId}/speaker-roles")
    fun updateSpeakerRoles(
        @PathVariable jobId: String,
        @RequestParam label: List<String>,
        @RequestParam role: List<String>,
        @RequestParam(required = false) relationship: List<String>?,
        @RequestParam(required = false) name: List<String>?,
        ra: RedirectAttributes,
    ): String {
        val subjectId =
            stage2.job(jobId)?.subjectId
                ?: run {
                    ra.addFlashAttribute("error", "Job not found")
                    return "redirect:/intake"
                }
        val roles =
            label.indices
                .mapNotNull { i ->
                    val r = SpeakerRole.fromOrNull(role.getOrNull(i)) ?: return@mapNotNull null
                    label[i] to
                        SpeakerAssignment(
                            role = r,
                            relationship =
                                Relationship.fromOrNull(relationship?.getOrNull(i)).takeIf {
                                    r == SpeakerRole.ENDORSER
                                },
                            name = name?.getOrNull(i)?.trim()?.takeIf { it.isNotBlank() },
                        )
                }
                .toMap()
        stage2
            .updateSpeakerRoles(jobId, roles)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Speaker roles saved — re-extracting (job is ${it.status})",
                    )
                },
            )
        return "redirect:/intake/$subjectId/stage2"
    }

    /**
     * §12.4 selection gate: the operator tags which diarized speaker(s) are the subject (checked
     * boxes → [self]); empty = subject not on the call. The service builds the binding and
     * extracts.
     */
    @PostMapping("/stage2/jobs/{jobId}/resolve-speakers")
    fun resolveSpeakers(
        @PathVariable jobId: String,
        @RequestParam(required = false) self: List<String>?,
        ra: RedirectAttributes,
    ): String {
        val subjectId =
            stage2.job(jobId)?.subjectId
                ?: run {
                    ra.addFlashAttribute("error", "Job not found")
                    return "redirect:/intake"
                }
        stage2
            .resolveSpeakers(jobId, self ?: emptyList())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Speaker selection saved — extracting (job is ${it.status})"
                    )
                },
            )
        return "redirect:/intake/$subjectId/stage2"
    }

    @PostMapping("/stage2/jobs/{jobId}/poll")
    fun pollJob(@PathVariable jobId: String, ra: RedirectAttributes): String {
        val subjectId =
            stage2.job(jobId)?.subjectId
                ?: run {
                    ra.addFlashAttribute("error", "Job not found")
                    return "redirect:/intake"
                }
        stage2
            .poll(jobId)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Job is ${it.status}") },
            )
        return "redirect:/intake/$subjectId/stage2"
    }

    // ---- §12.6 claim review flow -------------------------------------------

    /** Start review: freezes the claims, then into the wizard. */
    @PostMapping("/{id}/review/start")
    fun startReview(@PathVariable id: String, ra: RedirectAttributes): String =
        reviewService
            .startReview(id)
            .fold(
                {
                    ra.addFlashAttribute("error", it.message)
                    "redirect:/intake/$id/stage2"
                },
                {
                    // F11 (§9.2): questions generate at review lock, whichever door locked it.
                    questions.generateForReview(id)
                    ra.addFlashAttribute("ok", "Claim review started — claims are now locked")
                    "redirect:/intake/$id/review/decide"
                },
            )

    @GetMapping("/{id}/review/decide")
    fun reviewDecide(@PathVariable id: String, model: Model, ra: RedirectAttributes): String =
        reviewPage(id, model, ra, step = 2, view = "intake/review/decide")

    @GetMapping("/{id}/review/pii")
    fun reviewPii(@PathVariable id: String, model: Model, ra: RedirectAttributes): String =
        reviewPage(id, model, ra, step = 3, view = "intake/review/pii")

    @GetMapping("/{id}/review/preview")
    fun reviewPreview(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val result = reviewPage(id, model, ra, step = 4, view = "intake/review/preview")
        if (result.startsWith("redirect:")) return result
        model.addAttribute("summary", reviewService.summary(id))
        return result
    }

    @PostMapping("/stage2/claims/{claimId}/review")
    fun submitClaimReview(
        @PathVariable claimId: String,
        @RequestParam subjectId: String,
        @RequestParam decision: String,
        @RequestParam(required = false) justification: String?,
        @RequestParam(required = false) corroboratingClaimIds: List<String>?,
        ra: RedirectAttributes,
    ): String {
        val d = ReviewDecision.fromOrNull(decision)
        if (d == null) {
            ra.addFlashAttribute("error", "Choose a decision")
            return "redirect:/intake/$subjectId/review/decide"
        }
        reviewService
            .reviewClaim(claimId, d, justification, corroboratingClaimIds ?: emptyList(), actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Saved") },
            )
        return "redirect:/intake/$subjectId/review/decide"
    }

    @PostMapping("/stage2/claims/{claimId}/pii")
    fun submitClaimPii(
        @PathVariable claimId: String,
        @RequestParam subjectId: String,
        @RequestParam choice: String,
        ra: RedirectAttributes,
    ): String {
        val c = PiiChoice.fromOrNull(choice)
        if (c == null) {
            ra.addFlashAttribute("error", "Choose hide or include")
            return "redirect:/intake/$subjectId/review/pii"
        }
        reviewService
            .setPii(claimId, c, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Saved") },
            )
        return "redirect:/intake/$subjectId/review/pii"
    }

    @PostMapping("/{id}/review/submit")
    fun submitReview(@PathVariable id: String, ra: RedirectAttributes): String =
        reviewService
            .submitReview(id)
            .fold(
                {
                    ra.addFlashAttribute("error", it.message)
                    "redirect:/intake/$id/review/preview"
                },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Review submitted — approved claims are ready for Stage 3",
                    )
                    "redirect:/intake/$id/stage2"
                },
            )

    @PostMapping("/{id}/review/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    fun reopenReview(@PathVariable id: String, ra: RedirectAttributes): String {
        reviewService
            .reopenReview(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Review reopened for edits") },
            )
        return "redirect:/intake/$id/review/decide"
    }

    /** Shared model-load for the wizard pages; redirects out if the review isn't locked yet. */
    private fun reviewPage(
        id: String,
        model: Model,
        ra: RedirectAttributes,
        step: Int,
        view: String,
    ): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val manifest = intake.manifest(id)
        if (manifest?.reviewLockedAt == null) {
            ra.addFlashAttribute("error", "Start the claim review first")
            return "redirect:/intake/$id/stage2"
        }
        val partition = reviewService.partition(id)
        model.addAttribute("pageTitle", "Review · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("manifest", manifest)
        model.addAttribute("step", step)
        model.addAttribute("partition", partition)
        model.addAttribute("reviews", partition.reviews)
        model.addAttribute("allClaims", stage2.listClaims(id))
        model.addAttribute("assetTitles", intake.listAssets(id).associate { it.id to it.title })
        return view
    }

    private fun Stage2JobStatus.terminal(): Boolean =
        this == Stage2JobStatus.COMPLETED || this == Stage2JobStatus.FAILED
}

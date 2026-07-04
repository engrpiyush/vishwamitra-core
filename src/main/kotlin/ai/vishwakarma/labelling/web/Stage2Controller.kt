package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.IntakeService
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
        model.addAttribute("activeCount", jobs.count { !it.status.terminal() })
        // Fingerprint of the rendered job states; the auto-poll script reloads when it changes.
        model.addAttribute(
            "jobStatuses",
            jobs.map { "${it.id}:${it.status}" }.sorted().joinToString(","),
        )
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

    private fun Stage2JobStatus.terminal(): Boolean =
        this == Stage2JobStatus.COMPLETED || this == Stage2JobStatus.FAILED
}

package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
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

    private fun Stage2JobStatus.terminal(): Boolean =
        this == Stage2JobStatus.COMPLETED || this == Stage2JobStatus.FAILED
}

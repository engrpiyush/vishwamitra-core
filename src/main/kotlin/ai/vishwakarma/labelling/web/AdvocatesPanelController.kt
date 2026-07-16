package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.Advocate
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.domain.VersionStatus
import ai.vishwakarma.labelling.domain.WindowPreset
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.AdvocateChatService
import ai.vishwakarma.labelling.service.AggregateScoreService
import ai.vishwakarma.labelling.service.ProvisioningService
import ai.vishwakarma.labelling.service.SubjectService
import ai.vishwakarma.labelling.service.TokenChip
import ai.vishwakarma.labelling.service.TokenService
import ai.vishwakarma.labelling.service.TrainingService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
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
 * The operator Advocates panel (VA-40, LLD §14.2) — the full-vocabulary view of the §7.3 state
 * machine, in the /models area (REVIEWER+, VA-69: the URL rule in SecurityConfig pairs the class
 * guard). Everything the subject switch hides lives here: raw states, `lastError` verbatim, the
 * runbook actions (register → window → retry → manual sweep, §14.3), the §10 score recompute
 * (VA-44) and per-subject chat transcripts (VA-42 — the operator rendering of the F9 record).
 */
@Controller
@RequestMapping("/models/advocates")
@PreAuthorize("hasRole('REVIEWER')")
class AdvocatesPanelController(
    private val provisioning: ProvisioningService,
    private val subjects: SubjectService,
    private val tokens: TokenService,
    private val training: TrainingService,
    private val aggregateScores: AggregateScoreService,
    private val chat: AdvocateChatService,
) {

    /** One panel row per ACTIVE subject — advocate state + token posture side by side. */
    data class Row(
        val subject: Subject,
        val advocate: Advocate?,
        val tokenTotal: Int,
        val tokenActive: Int,
    ) {
        val startable: Boolean
            get() =
                advocate?.modelUri != null &&
                    (advocate.state == AdvocateState.UNPROVISIONED ||
                        advocate.state == AdvocateState.DEPLOY_FAILED)

        val endable: Boolean
            get() =
                advocate != null &&
                    (advocate.occupying || advocate.state == AdvocateState.DEPLOY_FAILED)
    }

    @GetMapping
    fun panel(model: Model): String {
        val advocates = provisioning.all().associateBy { it.subjectId }
        val rows =
            subjects
                .list()
                .filter { it.status == SubjectStatus.ACTIVE }
                .map { subject ->
                    val tokenRows = tokens.dashboard(subject.id)
                    Row(
                        subject = subject,
                        advocate = advocates[subject.id],
                        tokenTotal = tokenRows.size,
                        tokenActive =
                            tokenRows.count {
                                it.chip == TokenChip.UNREDEEMED || it.chip == TokenChip.ACTIVE
                            },
                    )
                }
        model.addAttribute("pageTitle", "Advocates")
        model.addAttribute("rows", rows)
        model.addAttribute("presets", WindowPreset.entries)
        // Register-form helper: READY checkpoints an operator can copy from.
        model.addAttribute(
            "readyVersions",
            training.versionsByFamily().values.flatten().filter {
                it.status == VersionStatus.READY && !it.gcsCheckpointUri.isNullOrBlank()
            },
        )
        return "models/advocates"
    }

    @PostMapping("/register")
    fun register(
        @RequestParam subjectId: String,
        @RequestParam checkpointUri: String,
        ra: RedirectAttributes,
    ): String {
        provisioning
            .register(subjectId, checkpointUri, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Registered ${it.modelUri} → UNPROVISIONED") },
            )
        return "redirect:/models/advocates"
    }

    @PostMapping("/start")
    fun start(
        @RequestParam subjectId: String,
        @RequestParam preset: String,
        ra: RedirectAttributes,
    ): String {
        val chosen =
            WindowPreset.fromOrNull(preset)
                ?: run {
                    ra.addFlashAttribute("error", "Unknown preset '$preset'")
                    return "redirect:/models/advocates"
                }
        provisioning
            .startWindow(subjectId, chosen, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Window started: ${it.state} until ${it.windowEndsAt}"
                    )
                },
            )
        return "redirect:/models/advocates"
    }

    @PostMapping("/end")
    fun end(@RequestParam subjectId: String, ra: RedirectAttributes): String {
        provisioning
            .endWindow(subjectId, "operator", actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Window ending: ${it.state}") },
            )
        return "redirect:/models/advocates"
    }

    @PostMapping("/poll")
    fun poll(@RequestParam subjectId: String, ra: RedirectAttributes): String {
        provisioning
            .poll(subjectId)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "State: ${it.state}") },
            )
        return "redirect:/models/advocates"
    }

    /** The §14.3 runbook trigger — the same sweep Cloud Scheduler fires (VA-39). */
    @PostMapping("/sweep")
    fun sweep(ra: RedirectAttributes): String {
        ra.addFlashAttribute("ok", "Sweep: ${provisioning.sweep()}")
        return "redirect:/models/advocates"
    }

    /** VA-44: re-derive the §10 evidence-strength number from the published ledger. */
    @PostMapping("/recompute-score")
    fun recomputeScore(@RequestParam subjectId: String, ra: RedirectAttributes): String {
        val result = aggregateScores.recompute(subjectId)
        ra.addFlashAttribute(
            "ok",
            if (result.display != null)
                "Score recomputed: ${result.display}/100 over ${result.scoredClaimCount} published claim(s)"
            else "No published claim scores yet — the score card shows \"still being scored\"",
        )
        return "redirect:/models/advocates"
    }

    /** VA-42 transcript access, operator rendering: every session + its messages, one page. */
    @GetMapping("/{subjectId}/transcripts")
    fun transcripts(@PathVariable subjectId: String, model: Model): String {
        val subject =
            subjects.list().firstOrNull { it.id == subjectId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val transcripts =
            chat.sessions(subjectId).map { session -> session to chat.history(session) }
        model.addAttribute("pageTitle", "Transcripts — ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("transcripts", transcripts)
        return "models/advocate-transcripts"
    }

    private fun actor(): String = CurrentUser.email() ?: "operator"
}

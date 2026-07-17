package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.Stage4Run
import ai.vishwakarma.labelling.domain.Stage4RunStatus
import ai.vishwakarma.labelling.persistence.ExportRepository
import ai.vishwakarma.labelling.persistence.PublishContract
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.PersonaService
import ai.vishwakarma.labelling.service.Stage4Service
import ai.vishwakarma.labelling.service.Stage4SubmitRequest
import ai.vishwakarma.labelling.service.StageConfigService
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
 * Stage 4 (published ledger → conversation notebooks) server-rendered UI, under the intake area —
 * VA-64, the [Stage3Controller] conventions exactly: REVIEWER-gated Thymeleaf MVC,
 * POST-redirect-GET, one page per subject walking the §9 run lifecycle: gated states (not published
 * / persona hint) → submit with the QA-3 mix overrides → phase card while SELECT→JUDGE advance →
 * the QA-4 REVIEW_WAIT park with queue links + the operator export → DONE with export/train links.
 * While the run is active, static/js/stage4-poll.js keeps POSTing `/api/stage4/runs/{id}/poll` (one
 * bounded step per call — the page being open IS the worker; no server-side scheduler).
 */
@Controller
@RequestMapping("/intake")
@PreAuthorize("hasRole('REVIEWER')")
class Stage4Controller(
    private val subjectService: SubjectService,
    private val stage4: Stage4Service,
    private val personaService: PersonaService,
    private val subjectScores: SubjectScoreRepository,
    private val sftExamples: SftExampleRepository,
    private val exports: ExportRepository,
    private val config: StageConfigService,
) {

    /** The §9 phase rail in order — the template renders one chip per entry. */
    private val phaseOrder =
        listOf(
            Stage4RunStatus.SELECTING,
            Stage4RunStatus.PLANNING,
            Stage4RunStatus.GENERATING,
            Stage4RunStatus.JUDGING,
        )

    private val phaseChips = listOf("Select", "Plan", "Generate", "Judge")

    private fun actor(): String? = CurrentUser.email()

    @GetMapping("/{id}/stage4")
    fun page(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val scores = subjectScores.find(id)
        val run = stage4.latestForSubject(id)
        model.addAttribute("pageTitle", "Stage 4 · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("enabled", config.stage4().enabled)
        // Gate 1: Stage 4 consumes only a published, contract-current ledger (§4). SELECT would
        // fail with the same verbatim reason — saying it before Run beats an instant FAILED run.
        model.addAttribute("published", scores != null)
        model.addAttribute(
            "contractOutdated",
            scores != null && scores.publishContractVersion < PublishContract.VERSION,
        )
        model.addAttribute("contractVersion", PublishContract.VERSION)
        // The persona posture for the ready panel: a missing doc is not a blocker — the engine
        // materializes the defaults-only persona (§9.1) — but the wizard link belongs up front.
        val persona = personaService.view(id).fold({ null }, { it })
        model.addAttribute("personaStored", persona?.stored != null)
        model.addAttribute("personaResolved", persona?.resolved)
        model.addAttribute("run", run)
        model.addAttribute("phaseChips", phaseChips)
        model.addAttribute("phaseIndex", run?.let { phaseIndex(it) } ?: -1)
        // REVIEW_WAIT parks (operator export moves it on) and DONE/FAILED/SUPERSEDED are
        // terminal — none of them may keep the auto-poll loop spinning (the §12.4 precedent).
        model.addAttribute(
            "active",
            run != null && !run.status.terminal && run.status != Stage4RunStatus.REVIEW_WAIT,
        )
        // Live server posture (props, NOT a run's frozen snapshot) — the Stage 3 lesson: a
        // stubbed engine makes a run useless on a real subject, so say so before Run.
        model.addAttribute("dryRunServer", config.stage4().dryRun)
        // The run's own frozen posture (paramsSnapshot) — dry-run runs stay badged forever.
        model.addAttribute("runDryRun", run?.let { snapshotFlag(it, "dryRun") } ?: false)
        // QD-6: a run submitted with the judge off is badged too (unsorted, unverdicted queue).
        model.addAttribute(
            "runJudgeDisabled",
            run?.let { !snapshotFlag(it, "judgeEnabled", default = true) } ?: false,
        )
        model.addAttribute("mixDefaults", config.stage4().mix)
        // The QA-4 gate's dashboard: current-stamp examples by status. Export is a filter, not a
        // gate — unreviewed examples are silently left behind — so the counts sit beside the
        // button and the operator decides when the queue is done.
        if (
            run != null &&
                (run.status == Stage4RunStatus.REVIEW_WAIT || run.status == Stage4RunStatus.DONE)
        ) {
            val stamped =
                sftExamples.findByStampSubject(id).filter {
                    it.stamp?.scoreRunId == run.scoreRunId &&
                        it.stamp?.personaHash == run.personaHash
                }
            model.addAttribute(
                "queueCounts",
                mapOf(
                    "submitted" to stamped.count { it.status == ExampleStatus.SUBMITTED },
                    "needsChanges" to stamped.count { it.status == ExampleStatus.NEEDS_CHANGES },
                    "approved" to stamped.count { it.status == ExampleStatus.APPROVED },
                ),
            )
        }
        // VA-88: the coverage report frozen at PLAN — categories hit/missed vs template targets.
        val coverage = run?.coverageReport?.let { parseCoverage(it) }.orEmpty()
        model.addAttribute("coverage", coverage)
        model.addAttribute(
            "coverageMissed",
            coverage.count {
                ((it["planned"] as? Number)?.toInt() ?: 0) <
                    ((it["target"] as? Number)?.toInt() ?: 0)
            },
        )
        model.addAttribute("exportRecord", run?.exportRecordId?.let { exports.findById(it) })
        return "intake/stage4"
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCoverage(json: String): List<Map<String, Any?>> =
        runCatching { Json.parse(json) as? List<Map<String, Any?>> }.getOrNull().orEmpty()

    @PostMapping("/{id}/stage4/run")
    fun run(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "false") fresh: Boolean,
        @RequestParam(required = false) mixQa: Double?,
        @RequestParam(required = false) mixSituational: Double?,
        @RequestParam(required = false) mixMultiClaim: Double?,
        @RequestParam(required = false) mixNegative: Double?,
        @RequestParam(required = false) mixMeta: Double?,
        ra: RedirectAttributes,
    ): String {
        val overrides =
            listOfNotNull(mixQa, mixSituational, mixMultiClaim, mixNegative, mixMeta)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    Stage4SubmitRequest.MixOverrides(
                        qa = mixQa,
                        situational = mixSituational,
                        multiClaim = mixMultiClaim,
                        negative = mixNegative,
                        meta = mixMeta,
                    )
                }
        stage4
            .submit(id, Stage4SubmitRequest(fresh = fresh, mix = overrides), actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Stage 4 run started — it advances automatically while this page is open",
                    )
                },
            )
        return "redirect:/intake/$id/stage4"
    }

    /** Manual poll fallback (the auto-poll script does this continuously). */
    @PostMapping("/stage4/runs/{runId}/poll")
    fun poll(@PathVariable runId: String, ra: RedirectAttributes): String =
        runAction(runId, ra) {
            stage4
                .poll(runId)
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    { ra.addFlashAttribute("ok", "Run is ${it.status}") },
                )
        }

    @PostMapping("/stage4/runs/{runId}/retry")
    fun retry(@PathVariable runId: String, ra: RedirectAttributes): String =
        runAction(runId, ra) {
            stage4
                .retry(runId)
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    { ra.addFlashAttribute("ok", "Retrying — run resumed in ${it.status}") },
                )
        }

    /**
     * The judge-trusting shortcut through the QA-4 queue: approve every SUBMITTED current-stamp
     * example whose latest judgment on unedited turns is PASS. The confirm popup carries the
     * warning; the flash message itemizes what was approved and what was left for a human.
     */
    @PostMapping("/stage4/runs/{runId}/bulk-approve")
    fun bulkApprove(@PathVariable runId: String, ra: RedirectAttributes): String =
        runAction(runId, ra) {
            stage4
                .bulkApprove(runId, actor())
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    { o ->
                        ra.addFlashAttribute(
                            "ok",
                            "Bulk-approved ${o.approved} judge-PASS example(s) — left for review: " +
                                "${o.notPass} borderline/fail, ${o.unjudged} unjudged or edited, " +
                                "${o.sentBack} sent-back",
                        )
                    },
                )
        }

    /**
     * The QA-4 gate's other side: export the parked run's APPROVED current-stamp examples and
     * complete it (REVIEW_WAIT → DONE). Validator failures come back verbatim with exampleId
     * pointers — nothing was written.
     */
    @PostMapping("/stage4/runs/{runId}/export")
    fun export(@PathVariable runId: String, ra: RedirectAttributes): String =
        runAction(runId, ra) {
            stage4
                .export(runId, actor())
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    { outcome ->
                        ra.addFlashAttribute(
                            "ok",
                            "Exported ${outcome.record.count} example(s) → " +
                                "${outcome.record.gcsUri} — run complete",
                        )
                    },
                )
        }

    /** Run-scoped actions share the redirect: resolve the subject, act, land back on its page. */
    private fun runAction(runId: String, ra: RedirectAttributes, act: () -> Unit): String {
        val subjectId =
            stage4.run(runId)?.subjectId
                ?: run {
                    ra.addFlashAttribute("error", "Run not found")
                    return "redirect:/intake"
                }
        act()
        return "redirect:/intake/$subjectId/stage4"
    }

    /**
     * Where the chip rail stands: chips before the index are done, the chip at it is the current
     * (or failed) phase, later chips are pending. REVIEW_WAIT and beyond mean every engine phase
     * completed; a FAILED run points at the phase Retry will resume.
     */
    private fun phaseIndex(run: Stage4Run): Int =
        when (run.status) {
            Stage4RunStatus.PENDING -> 0
            Stage4RunStatus.REVIEW_WAIT,
            Stage4RunStatus.DONE,
            Stage4RunStatus.SUPERSEDED -> phaseOrder.size
            Stage4RunStatus.FAILED ->
                when (val phase = run.failedPhase) {
                    null,
                    Stage4RunStatus.PENDING -> 0
                    else -> phaseOrder.indexOf(phase).takeIf { it >= 0 } ?: phaseOrder.size
                }
            else -> phaseOrder.indexOf(run.status)
        }

    /** A frozen boolean out of the run's paramsSnapshot (the VA-64 badge sources). */
    private fun snapshotFlag(run: Stage4Run, key: String, default: Boolean = false): Boolean {
        val raw =
            run.paramsSnapshot?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }
                ?: return default
        return raw[key] as? Boolean ?: default
    }
}

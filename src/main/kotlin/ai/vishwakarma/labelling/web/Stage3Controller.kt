package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.Stage3Counters
import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.report.DashboardCharts
import ai.vishwakarma.labelling.report.PdfReportService
import ai.vishwakarma.labelling.report.VizPalette
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.Stage3DashboardService
import ai.vishwakarma.labelling.service.Stage3EvalService
import ai.vishwakarma.labelling.service.Stage3Service
import ai.vishwakarma.labelling.service.StageConfigService
import ai.vishwakarma.labelling.service.SubjectService
import ai.vishwakarma.labelling.stage3.ContradictionView
import ai.vishwakarma.labelling.stage3.ScoredClaimView
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import java.util.Locale
import kotlin.math.abs
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
 * Stage 3 (reviewed claims → authenticity graph/scores) server-rendered UI, under the intake area
 * ([Stage2Controller] conventions: REVIEWER-gated Thymeleaf MVC, POST-redirect-GET). One page per
 * subject (LLD §12 bullet 1) walks the §9.7 run lifecycle: gated states → progress card while
 * phases advance → the Q6 AWAITING_REVIEW banner with the queue-gated publish → PUBLISHED. While
 * the run is active a small script (static/js/stage3-poll.js) keeps polling
 * `/api/stage3/runs/{id}/poll`, which performs one bounded phase step per call — keeping the page
 * open drives the run to completion, there is no server-side scheduler.
 */
@Controller
@RequestMapping("/intake")
@PreAuthorize("hasRole('REVIEWER')")
class Stage3Controller(
    private val subjectService: SubjectService,
    private val intake: IntakeService,
    private val stage3: Stage3Service,
    private val graph: Stage3GraphRepository,
    private val eval: Stage3EvalService,
    private val dashboards: Stage3DashboardService,
    private val pdfReports: PdfReportService,
    private val config: StageConfigService,
) {

    /** The §9.7 phase rail in order — the template renders one chip per entry. */
    private val phaseOrder =
        listOf(
            Stage3RunStatus.SYNCING,
            Stage3RunStatus.RESOLVING_ENTITIES,
            Stage3RunStatus.EMBEDDING,
            Stage3RunStatus.MATCHING,
            Stage3RunStatus.JUDGING,
            Stage3RunStatus.ASSEMBLING,
            Stage3RunStatus.SCORING,
        )

    private val phaseChips =
        listOf("Sync", "Entities", "Embed", "Match", "Judge", "Assemble", "Score")

    private fun actor(): String? = CurrentUser.email()

    @GetMapping("/{id}/stage3")
    fun page(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val run = stage3.latestForSubject(id)
        model.addAttribute("pageTitle", "Stage 3 · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("manifest", intake.manifest(id))
        model.addAttribute("run", run)
        model.addAttribute("phaseChips", phaseChips)
        model.addAttribute("phaseIndex", run?.let { phaseIndex(it) } ?: -1)
        // AWAITING_REVIEW parks (queue actions/publish move it on) and PUBLISHED/FAILED are
        // terminal — none of them may keep the auto-poll loop spinning (the §12.4 precedent).
        model.addAttribute(
            "active",
            run != null && !run.status.terminal && run.status != Stage3RunStatus.AWAITING_REVIEW,
        )
        model.addAttribute(
            "queueSize",
            run?.counters?.get(Stage3Counters.CONTRADICTION_QUEUE) ?: 0L,
        )
        // VA-106: the gatekeeper cascade's live view (gate chips + failure card), null in LLM mode
        // or before the run first triggers. The judgeMode chip reads the run's own frozen mode.
        model.addAttribute("gatekeeper", run?.let { stage3.gatekeeperView(it) })
        model.addAttribute(
            "judgeMode",
            run?.let { stage3.frozenJudgeMode(it).name } ?: config.stage3().judgeModeOrDefault.name,
        )
        // Live server posture (props, not the run's snapshot): a stubbed leg makes a run useless
        // on a real subject, so surface it before Run — not after (2026-07-11 testing lesson).
        model.addAttribute(
            "dryLegs",
            listOfNotNull(
                "embeddings".takeIf { config.stage3().embeddingsDryRun },
                "extraction".takeIf { config.stage3().extractionDryRun },
                "judge".takeIf { config.stage3().judgeDryRun },
            ),
        )
        return "intake/stage3"
    }

    /**
     * VA-22 (LLD §12 bullet 2): the scored-claims explainability table — server-rendered rows plus
     * a hidden per-claim "why this score" panel bank (the §12.6 review page's DataTables row-expand
     * idiom). The same page serves the provisional graph-side scores while the run parks at
     * AWAITING_REVIEW and the ledger values once PUBLISHED; the §21 A.3 read supplies everything in
     * one call, presentation shaping stays here.
     */
    @GetMapping("/{id}/stage3/scores")
    fun scores(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val views =
            runCatching { graph.scoresReadback(id) }
                .getOrElse {
                    ra.addFlashAttribute("error", "Could not read scores: ${it.message}")
                    return "redirect:/intake/$id/stage3"
                }
        val factSizes = views.groupingBy { it.factId }.eachCount()
        model.addAttribute("pageTitle", "Scores · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("run", stage3.latestForSubject(id))
        model.addAttribute(
            "rows",
            views.map {
                it.toRowView(
                    factSize = factSizes[it.factId] ?: 1,
                    tierHigh = config.stage3().tierHigh,
                    tierMedium = config.stage3().tierMedium,
                )
            },
        )
        return "intake/stage3-scores"
    }

    /**
     * VA-23 (LLD §12 bullet 3, §11.10): the contradiction queue — one card per PROPOSED edge,
     * ordered by confidence, with both facts' claims side-by-side and the provisional score impact.
     * Actions are POST-redirect-GET onto this page; the incremental re-score happens inside the
     * action request, so the reloaded page already shows the new beliefs.
     */
    @GetMapping("/{id}/stage3/contradictions")
    fun contradictions(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val cards =
            runCatching { stage3.contradictions(id) }
                .getOrElse {
                    ra.addFlashAttribute("error", "Could not read the queue: ${it.message}")
                    return "redirect:/intake/$id/stage3"
                }
        val assetTitles = intake.listAssets(id).associate { it.id to it.title }
        model.addAttribute("pageTitle", "Contradictions · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("run", stage3.latestForSubject(id))
        model.addAttribute("cards", cards.map { it.toCardView(assetTitles) })
        return "intake/stage3-contradictions"
    }

    /** Ratify: the penalty stands, the edge leaves the queue (no re-score needed). */
    @PostMapping("/{id}/stage3/contradictions/{edgeId}/confirm")
    fun confirmContradiction(
        @PathVariable id: String,
        @PathVariable edgeId: String,
        ra: RedirectAttributes,
    ): String {
        stage3
            .confirmContradiction(edgeId)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Confirmed — the penalty stands (${queueNote(it)})") },
            )
        return "redirect:/intake/$id/stage3/contradictions"
    }

    /** Reject a judge false-positive: edge deleted, verdicts overridden, incremental re-score. */
    @PostMapping("/{id}/stage3/contradictions/{edgeId}/dismiss")
    fun dismissContradiction(
        @PathVariable id: String,
        @PathVariable edgeId: String,
        ra: RedirectAttributes,
    ): String {
        stage3
            .dismissContradiction(edgeId)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Dismissed — edge removed, cached verdicts overridden, scores " +
                            "recomputed (${queueNote(it)})",
                    )
                },
            )
        return "redirect:/intake/$id/stage3/contradictions"
    }

    /**
     * The §11.10 explain hook: re-judges the edge's pairs WITH the sidecar context and re-scores.
     * Needs a sidecar on an involved claim — authored during review, or via the §12.6 editor after
     * an ADMIN review reopen (the card links there).
     */
    @PostMapping("/{id}/stage3/contradictions/{edgeId}/rejudge")
    fun rejudgeContradiction(
        @PathVariable id: String,
        @PathVariable edgeId: String,
        ra: RedirectAttributes,
    ): String {
        stage3
            .rejudgeContradiction(edgeId)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Re-judged with the sidecar context and re-scored (${queueNote(it)})",
                    )
                },
            )
        return "redirect:/intake/$id/stage3/contradictions"
    }

    /**
     * VA-24 (LLD §12 bullet 4, §11.7): the timeline — STATE slot lanes with SUCCEEDS chains, EVENT
     * points, anachronism markers. The view is a client-side SVG renderer
     * (static/js/stage3-timeline.js — self-hosted, no chart dependency) over the §10 timeline read,
     * inlined as JSON; undated/TIMELESS facts render as a server-side list below the axis.
     */
    @GetMapping("/{id}/stage3/timeline")
    fun timeline(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val view =
            runCatching { graph.timeline(id) }
                .getOrElse {
                    ra.addFlashAttribute("error", "Could not read the timeline: ${it.message}")
                    return "redirect:/intake/$id/stage3"
                }
        model.addAttribute("pageTitle", "Timeline · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("timeline", view)
        model.addAttribute("timelineJson", Json.writeLine(view))
        model.addAttribute("tierHigh", config.stage3().tierHigh)
        model.addAttribute("tierMedium", config.stage3().tierMedium)
        model.addAttribute("hasDated", view.state.isNotEmpty() || view.events.isNotEmpty())
        return "intake/stage3-timeline"
    }

    /**
     * The Stage 3.5 §6 authenticity dashboard: the SAI headline + every number that derives it.
     * Charts are server-rendered SVG (report/ChartSvg, the D8 decision) painted through CSS tokens
     * so both themes read; stage3-dashboard.js only adds fact popovers. Provisional at
     * AWAITING_REVIEW (same badge idiom as the scores page), frozen-vs-live drift is surfaced.
     */
    @GetMapping("/{id}/stage3/dashboard")
    fun dashboard(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val data =
            runCatching { dashboards.dashboard(id) }
                .getOrElse {
                    ra.addFlashAttribute("error", "Could not read the dashboard: ${it.message}")
                    return "redirect:/intake/$id/stage3"
                }
                .fold(
                    { err ->
                        ra.addFlashAttribute("error", err.message)
                        return "redirect:/intake"
                    },
                    { it },
                )
        model.addAttribute("pageTitle", "Authenticity · ${data.subjectName}")
        model.addAttribute("data", data)
        model.addAttribute("run", stage3.latestForSubject(id))
        // The shared chart set (report/DashboardCharts — the PDF renders the same builders with
        // the PRINT palette); web paints ride CSS tokens so the theme toggle recolors live.
        model.addAttribute("charts", DashboardCharts.build(data, config.stage3(), VizPalette.WEB))
        model.addAttribute("report", pdfReports.metadata(id))
        model.addAttribute("staleReport", pdfReports.isStale(id))
        // Fact details for the popover layer, keyed by factId.
        model.addAttribute(
            "dashboardJson",
            Json.writeLine(
                mapOf(
                    "facts" to data.factPoints.associateBy { it.factId },
                    "scoresUrl" to "/intake/${data.subjectId}/stage3/scores",
                )
            ),
        )
        return "intake/stage3-dashboard"
    }

    // ---- Stage 3.5 §7: the profile-PDF buttons (PRG + flash, D6 replace semantics) ----------

    /** Generate (or regenerate — same object path, replaces) the subject's profile PDF. */
    @PostMapping("/{id}/stage3/report/generate")
    fun generateReport(@PathVariable id: String, ra: RedirectAttributes): String {
        pdfReports
            .generate(id, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Profile PDF generated (${it.sizeBytes / 1024} KB)" +
                            if (it.provisional) " — PROVISIONAL watermark applied" else "",
                    )
                },
            )
        return "redirect:/intake/$id/stage3/dashboard"
    }

    /** Download: 302 to the signed URL (or the dev sink locally). */
    @GetMapping("/{id}/stage3/report")
    fun downloadReport(@PathVariable id: String, ra: RedirectAttributes): String =
        pdfReports
            .downloadUrl(id)
            .fold(
                {
                    ra.addFlashAttribute("error", it.message)
                    "redirect:/intake/$id/stage3/dashboard"
                },
                { "redirect:$it" },
            )

    /** Delete the current report (object + metadata). */
    @PostMapping("/{id}/stage3/report/delete")
    fun deleteReport(@PathVariable id: String, ra: RedirectAttributes): String {
        pdfReports
            .delete(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Profile PDF deleted") },
            )
        return "redirect:/intake/$id/stage3/dashboard"
    }

    /**
     * The §13 labeling ride-along (VA-26 shared partial; REVIEWER+ like the JSON API): record one
     * golden-pair label and bounce back to the calling surface. `return` is constrained to in-app
     * paths so the redirect can't leave the console.
     */
    @PostMapping("/stage3/eval/label")
    fun labelPair(
        @RequestParam subjectId: String,
        @RequestParam claimIdA: String,
        @RequestParam claimIdB: String,
        @RequestParam humanRelation: String,
        @RequestParam(name = "return", required = false) returnPath: String?,
        ra: RedirectAttributes,
    ): String {
        eval
            .label(subjectId, claimIdA, claimIdB, humanRelation, split = null, actor = actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Labeled ${it.humanRelation} (${it.split}) — the golden set grows",
                    )
                },
            )
        val safe =
            returnPath?.takeIf { it.startsWith("/intake/") || it.startsWith("/admin/") }
                ?: "/intake/$subjectId/stage3"
        return "redirect:$safe"
    }

    private fun queueNote(run: Stage3Run): String {
        val left = run.counters[Stage3Counters.CONTRADICTION_QUEUE] ?: 0L
        return if (left == 0L) "queue is empty — ready to publish" else "$left left in the queue"
    }

    @PostMapping("/{id}/stage3/run")
    fun run(@PathVariable id: String, ra: RedirectAttributes): String {
        stage3
            .submit(id, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Stage 3 run started — it advances automatically while this page is open",
                    )
                },
            )
        return "redirect:/intake/$id/stage3"
    }

    /** Manual poll fallback (the auto-poll script does this continuously). */
    @PostMapping("/stage3/runs/{runId}/poll")
    fun poll(@PathVariable runId: String, ra: RedirectAttributes): String =
        runAction(runId, ra) {
            stage3
                .poll(runId)
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    { ra.addFlashAttribute("ok", "Run is ${it.status}") },
                )
        }

    @PostMapping("/stage3/runs/{runId}/retry")
    fun retry(@PathVariable runId: String, ra: RedirectAttributes): String =
        runAction(runId, ra) {
            stage3
                .retry(runId)
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    { ra.addFlashAttribute("ok", "Retrying — run resumed in ${it.status}") },
                )
        }

    /**
     * The Q6 gate (LLD §11.10). Refused server-side while PROPOSED contradictions remain unless
     * `skipReview` — the skip button is ADMIN-gated in the template and its use is recorded on the
     * run as [Stage3Run.reviewSkipped] (the audit cost of skipping).
     */
    @PostMapping("/stage3/runs/{runId}/publish")
    fun publish(
        @PathVariable runId: String,
        @RequestParam(required = false, defaultValue = "false") skipReview: Boolean,
        ra: RedirectAttributes,
    ): String =
        runAction(runId, ra) {
            stage3
                .publish(runId, skipReview, actor())
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    { published ->
                        // Publish runs the ledger write synchronously; a mid-write failure comes
                        // back as a FAILED run, not a DomainError — surface it as one.
                        if (published.status == Stage3RunStatus.PUBLISHED) {
                            val n = published.counters[Stage3Counters.CLAIMS_PUBLISHED] ?: 0L
                            ra.addFlashAttribute(
                                "ok",
                                "Published $n claim vector(s) to the ledger" +
                                    if (published.reviewSkipped)
                                        " — contradiction review skipped (recorded on the run)"
                                    else "",
                            )
                        } else {
                            ra.addFlashAttribute("error", "Publish failed — ${published.error}")
                        }
                    },
                )
        }

    /**
     * Re-run chooser on a PUBLISHED or parked AWAITING_REVIEW run: plain (cache-warm) vs `fresh`
     * (replaces the graph). A parked run retires as SUPERSEDED — the discard path for a run whose
     * provisional scores should never reach the ledger.
     */
    @PostMapping("/stage3/runs/{runId}/rerun")
    fun rerun(
        @PathVariable runId: String,
        @RequestParam(required = false, defaultValue = "false") fresh: Boolean,
        ra: RedirectAttributes,
    ): String =
        runAction(runId, ra) {
            stage3
                .rerun(runId, fresh, actor())
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    {
                        ra.addFlashAttribute(
                            "ok",
                            if (fresh)
                                "Fresh re-run created — the subject's graph is rebuilt from the " +
                                    "reviewed claims"
                            else "Re-run created — cached judge verdicts are reused",
                        )
                    },
                )
        }

    /**
     * VA-106: operator retrigger of the gatekeeper cascade — a failed gate (`gate` set) re-enters
     * FROM_GATE keeping earlier work, or the whole run restarts FROM_START (`gate` blank, new id).
     */
    @PostMapping("/stage3/runs/{runId}/gatekeeper/retrigger")
    fun retriggerGatekeeper(
        @PathVariable runId: String,
        @RequestParam(required = false) gate: String?,
        ra: RedirectAttributes,
    ): String =
        runAction(runId, ra) {
            val target = ai.vishwakarma.labelling.stage3.gatekeeper.Gate.fromOrNull(gate)
            stage3
                .retriggerGatekeeper(runId, target)
                .fold(
                    { ra.addFlashAttribute("error", it.message) },
                    {
                        ra.addFlashAttribute(
                            "ok",
                            if (target == null)
                                "Gatekeeper restarted from the beginning (FROM_START)"
                            else "Gatekeeper re-triggered from ${target.shortLabel} (FROM_GATE)",
                        )
                    },
                )
        }

    /** Run-scoped actions share the redirect: resolve the subject, act, land back on its page. */
    private fun runAction(runId: String, ra: RedirectAttributes, act: () -> Unit): String {
        val subjectId =
            stage3.run(runId)?.subjectId
                ?: run {
                    ra.addFlashAttribute("error", "Run not found")
                    return "redirect:/intake"
                }
        act()
        return "redirect:/intake/$subjectId/stage3"
    }

    /**
     * Where the chip rail stands: chips before the index are done, the chip at it is the current
     * (or failed) phase, later chips are pending. AWAITING_REVIEW and beyond mean every scoring
     * phase completed; a FAILED run points at the phase Retry will resume.
     */
    private fun phaseIndex(run: Stage3Run): Int =
        when (run.status) {
            Stage3RunStatus.PENDING -> 0
            Stage3RunStatus.AWAITING_REVIEW,
            Stage3RunStatus.PUBLISHING,
            Stage3RunStatus.PUBLISHED -> phaseOrder.size
            Stage3RunStatus.FAILED ->
                when (val phase = run.failedPhase) {
                    null,
                    Stage3RunStatus.PENDING -> 0
                    // A crashed ledger write failed past the rail — every scoring phase is done.
                    else -> phaseOrder.indexOf(phase).takeIf { it >= 0 } ?: phaseOrder.size
                }
            else -> phaseOrder.indexOf(run.status)
        }
}

// ---- VA-22 scored-claims page view models (presentation shaping only) --------------------------

/** One table row + its expanded "why this score" panel, formatted for the template. */
data class ScoredClaimRowView(
    val claimId: String,
    val text: String,
    val type: String?,
    val basis: String?,
    val sourceClass: String?,
    val sensitive: Boolean,
    val score: Double,
    val scoreText: String,
    /** Set only when scoreBare differs visibly from score — the row's delta chip. */
    val scoreBareText: String?,
    val tierSeed: String?,
    val scoredTier: String,
    /** Sort key for the tier column (HIGH=2 … LOW=0). */
    val tierRank: Int,
    val factId: String,
    val factLabel: String,
    val factSize: Int,
    val supportCount: Int,
    val conflictCount: Int,
    val entities: List<EntityChipView>,
    /** The §3.2 vector in fixed order — (label, formatted value) pairs. */
    val signals: List<Pair<String, String>>,
    val factKind: String?,
    val slot: String?,
    val intervalText: String,
    val anchored: Boolean,
    val beliefText: String?,
    val beliefBareText: String?,
    /** "name · kind · trust 0.62" — whose word the claim rests on (§11.2). */
    val attestorText: String?,
    val edges: List<EdgeCardView>,
    val explanation: String?,
    /** "+0.04" — how far the sidecar moved the score off scoreBare (§11.9). */
    val sidecarDeltaText: String?,
)

/** One resolved mention chip (§11.3). */
data class EntityChipView(val name: String, val type: String, val provisional: Boolean)

/** One judged edge touching the claim's fact, as the panel's edge card renders it (§11.6). */
data class EdgeCardView(
    val relation: String,
    val otherLabel: String,
    /** The other fact's exemplar claim — the §13 ride-along's label pair partner (VA-26). */
    val otherExemplarClaimId: String?,
    val confidenceText: String?,
    /** e.g. "CONTRADICTS 4 · NEUTRAL 1". */
    val votesText: String?,
    /** The ensemble majority, e.g. "4/5". */
    val majorityText: String?,
    val rationale: String?,
    val explained: Boolean,
    val temporalOverlap: Boolean?,
    val reviewStatus: String?,
    val viaEntities: List<String>,
)

private fun fmt2(d: Double): String = String.format(Locale.ROOT, "%.2f", d)

/** The §3.2 signal keys in presentation order, with their panel labels. */
private val SIGNAL_LABELS =
    listOf(
        "prior" to "prior",
        "support" to "support",
        "conflict" to "conflict",
        "independence" to "independence",
        "recency" to "recency",
        "evidenceMass" to "evidence mass",
        "scoreBare" to "bare",
    )

private fun parseNumberMap(json: String?): Map<String, Double> {
    val raw =
        json?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() } ?: return emptyMap()
    return raw.entries
        .mapNotNull { (k, v) -> (v as? Number)?.let { k.toString() to it.toDouble() } }
        .toMap()
}

private fun ScoredClaimView.toRowView(
    factSize: Int,
    tierHigh: Double,
    tierMedium: Double,
): ScoredClaimRowView {
    val scoredTier =
        when {
            score >= tierHigh -> "HIGH"
            score >= tierMedium -> "MEDIUM"
            else -> "LOW"
        }
    val signalMap = parseNumberMap(signalsJson)
    val delta = if (explanation != null && scoreBare != null) score - scoreBare!! else null
    return ScoredClaimRowView(
        claimId = claimId,
        text = text,
        type = type,
        basis = basis,
        sourceClass = sourceClass,
        sensitive = sensitive,
        score = score,
        scoreText = fmt2(score),
        scoreBareText = scoreBare?.takeIf { abs(score - it) >= 0.005 }?.let(::fmt2),
        tierSeed = tierSeed,
        scoredTier = scoredTier,
        tierRank =
            when (scoredTier) {
                "HIGH" -> 2
                "MEDIUM" -> 1
                else -> 0
            },
        factId = factId,
        factLabel = factLabel,
        factSize = factSize,
        supportCount = edges.count { it["relation"] == "CORROBORATES" },
        conflictCount = edges.count { it["relation"] == "CONTRADICTS" },
        entities =
            entities
                .mapNotNull { m ->
                    (m["name"] as? String)?.let {
                        EntityChipView(
                            name = it,
                            type = (m["type"] as? String).orEmpty(),
                            provisional = m["provisional"] as? Boolean ?: false,
                        )
                    }
                }
                .distinctBy { it.type to it.name }
                .sortedWith(compareBy({ it.type }, { it.name })),
        signals =
            SIGNAL_LABELS.mapNotNull { (key, label) -> signalMap[key]?.let { label to fmt2(it) } } +
                ("score" to fmt2(score)),
        factKind = factKind,
        slot = slot,
        intervalText = intervalText(),
        anchored = anchored,
        beliefText = belief?.let(::fmt2),
        beliefBareText =
            beliefBare?.takeIf { b -> belief?.let { abs(it - b) >= 0.005 } == true }?.let(::fmt2),
        attestorText =
            (attestorName ?: attestorKey)?.let { who ->
                listOfNotNull(
                        who,
                        attestorKind?.lowercase(Locale.ROOT),
                        attestorTrust?.let { "trust ${fmt2(it)}" },
                    )
                    .joinToString(" · ")
            },
        edges = edges.map { it.toEdgeCard() },
        explanation = explanation,
        sidecarDeltaText =
            delta?.takeIf { abs(it) >= 0.005 }?.let { (if (it >= 0) "+" else "") + fmt2(it) },
    )
}

private fun Map<String, Any?>.toEdgeCard(): EdgeCardView {
    val votes = parseNumberMap(this["votes"] as? String).mapValues { it.value.toInt() }
    val top = votes.maxByOrNull { it.value }
    return EdgeCardView(
        relation = this["relation"] as? String ?: "",
        otherLabel = this["otherLabel"] as? String ?: "",
        otherExemplarClaimId = this["otherExemplar"] as? String,
        confidenceText = (this["confidence"] as? Number)?.let { fmt2(it.toDouble()) },
        votesText =
            votes
                .takeIf { it.isNotEmpty() }
                ?.entries
                ?.sortedByDescending { it.value }
                ?.joinToString(" · ") { "${it.key} ${it.value}" },
        majorityText = top?.let { "${it.value}/${votes.values.sum()}" },
        rationale = this["rationale"] as? String,
        explained = this["explained"] as? Boolean ?: false,
        temporalOverlap = this["temporalOverlap"] as? Boolean,
        reviewStatus = this["reviewStatus"] as? String,
        viaEntities =
            (this["viaEntities"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
    )
}

private fun ScoredClaimView.intervalText(): String =
    when {
        factKind == "TIMELESS" -> "timeless"
        validFrom == null && validTo == null -> "undated"
        else -> {
            val precision =
                datePrecision
                    ?.takeIf { it != "NONE" }
                    ?.let { " · ${it.lowercase(Locale.ROOT)} precision" }
                    .orEmpty()
            "${validFrom ?: "…"} → ${validTo ?: "present"}$precision"
        }
    }

// ---- VA-23 contradiction-queue page view models -------------------------------------------------

/** One §11.10 queue card, formatted for the template. */
data class QueueCardView(
    val edgeId: String,
    val confidenceText: String,
    /** The ensemble majority, e.g. "4/5". */
    val majorityText: String?,
    /** Full split, e.g. "CONTRADICTS 4 · NEUTRAL 1". */
    val votesText: String?,
    val rationale: String?,
    val temporalNote: String?,
    val temporalOverlap: Boolean?,
    val viaEntities: List<String>,
    val from: QueueSideView,
    val to: QueueSideView,
    /** First contributing claim pair — the §13 ride-along's label subject (VA-26). */
    val labelClaimIdA: String?,
    val labelClaimIdB: String?,
)

/** One side of the card: the fact, its provisional score impact, and its member claims. */
data class QueueSideView(
    val factId: String,
    val label: String,
    val beliefText: String?,
    /** belief − beliefBare, signed — "what this penalty currently costs the fact". */
    val impactText: String?,
    val claims: List<QueueClaimView>,
)

data class QueueClaimView(
    val claimId: String,
    val text: String,
    val sourceClass: String?,
    val relationship: String?,
    val speakerRole: String?,
    val claimedDate: String?,
    val assetTitle: String?,
)

private fun ContradictionView.toCardView(assetTitles: Map<String, String?>): QueueCardView {
    val votes = parseNumberMap(votesJson).mapValues { it.value.toInt() }
    val top = votes.maxByOrNull { it.value }
    val firstPair = contributingPairs.firstOrNull()?.split("↔")?.takeIf { it.size == 2 }
    return QueueCardView(
        edgeId = edgeId,
        confidenceText = fmt2(confidence),
        majorityText = top?.let { "${it.value}/${votes.values.sum()}" },
        votesText =
            votes
                .takeIf { it.isNotEmpty() }
                ?.entries
                ?.sortedByDescending { it.value }
                ?.joinToString(" · ") { "${it.key} ${it.value}" },
        rationale = rationale,
        temporalNote = temporalNote,
        temporalOverlap = temporalOverlap,
        viaEntities = viaEntities,
        from = from.toSideView(assetTitles),
        to = to.toSideView(assetTitles),
        labelClaimIdA = firstPair?.get(0),
        labelClaimIdB = firstPair?.get(1),
    )
}

private fun ai.vishwakarma.labelling.stage3.ContradictionSide.toSideView(
    assetTitles: Map<String, String?>
): QueueSideView =
    QueueSideView(
        factId = factId,
        label = label,
        beliefText = belief?.let(::fmt2),
        impactText =
            if (belief != null && beliefBare != null) {
                val d = belief!! - beliefBare!!
                (if (d >= 0) "+" else "") + fmt2(d)
            } else null,
        claims =
            claims.mapNotNull { m ->
                (m["claimId"] as? String)?.let {
                    QueueClaimView(
                        claimId = it,
                        text = m["text"] as? String ?: "",
                        sourceClass = m["sourceClass"] as? String,
                        relationship = m["relationship"] as? String,
                        speakerRole = m["speakerRole"] as? String,
                        claimedDate = m["claimedDate"] as? String,
                        assetTitle = (m["assetId"] as? String)?.let { a -> assetTitles[a] ?: a },
                    )
                }
            },
    )

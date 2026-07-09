package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.persistence.Stage3EntityJournalRepository
import ai.vishwakarma.labelling.persistence.Stage3EvalMetricsRecord
import ai.vishwakarma.labelling.persistence.Stage3GoldenPairRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.EntityAdminService
import ai.vishwakarma.labelling.service.Stage3EvalService
import ai.vishwakarma.labelling.service.SubjectService
import ai.vishwakarma.labelling.stage3.EntityType
import ai.vishwakarma.labelling.stage3.JudgeSampler
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import java.util.Locale
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
 * Stage 3 admin surfaces ([AdminController] conventions — ADMIN via URL rule + method security):
 * the §11.3 entity browser with the merge/split repair dialogs (`/admin/entities`, VA-25) and the
 * §13 eval dashboard with the blind labeling flow (`/admin/stage3-eval`, VA-26). REVIEWER read
 * access to the same data exists on the JSON API (LLD §10); these pages are the repair/quality
 * consoles and stay admin-only with the rest of `/admin/\**`.
 */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
class Stage3AdminController(
    private val graph: Stage3GraphRepository,
    private val entityAdmin: EntityAdminService,
    private val entityJournal: Stage3EntityJournalRepository,
    private val eval: Stage3EvalService,
    private val goldens: Stage3GoldenPairRepository,
    private val subjectService: SubjectService,
    private val judgeSampler: JudgeSampler,
) {

    private fun actor(): String? = CurrentUser.email()

    // ---- VA-25: entity browser (§11.3 "Human repair") ------------------------------------

    @GetMapping("/entities")
    fun entities(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false, defaultValue = "") q: String,
        model: Model,
    ): String {
        val entityType = type?.trim()?.takeIf { it.isNotBlank() }?.let { EntityType.fromOrNull(it) }
        val rows =
            runCatching { graph.searchEntities(entityType?.name, q, ENTITY_PAGE) }
                .getOrElse {
                    model.addAttribute("loadError", "Neo4j unreachable: ${it.message}")
                    emptyList()
                }
        val reviewList =
            runCatching { graph.entityReviewList(REVIEW_PAGE) }.getOrElse { emptyList() }
        val subjectNames = subjectService.list().associate { it.id to it.displayName }
        model.addAttribute("pageTitle", "Entities")
        model.addAttribute("rows", rows)
        model.addAttribute("reviewList", reviewList)
        model.addAttribute("subjectNames", subjectNames)
        model.addAttribute("entityTypes", EntityType.entries)
        model.addAttribute("selectedType", entityType?.name)
        model.addAttribute("q", q)
        return "admin/entities"
    }

    @GetMapping("/entities/{id}")
    fun entityDetail(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val entity =
            runCatching { graph.findEntityAdmin(id) }
                .getOrElse {
                    ra.addFlashAttribute("error", "Neo4j unreachable: ${it.message}")
                    return "redirect:/admin/entities"
                }
                ?: run {
                    ra.addFlashAttribute("error", "Entity $id not found")
                    return "redirect:/admin/entities"
                }
        val mentions = graph.entityMentionRows(id)
        val subjectNames = subjectService.list().associate { it.id to it.displayName }
        val perSubject =
            mentions
                .groupBy { it.subjectId }
                .map { (sid, rows) ->
                    EntityMentionGroupView(
                        subjectId = sid,
                        subjectName = subjectNames[sid] ?: sid,
                        mentionCount = rows.size,
                        provisionalCount = rows.count { it.provisional },
                        surfaces = rows.map { it.surface }.distinct().sorted(),
                    )
                }
                .sortedByDescending { it.mentionCount }
        // Type-scoped live targets for the merge dialog (the canon is small at POC scale).
        val mergeTargets =
            graph
                .searchEntities(entity.entityType, "", ENTITY_PAGE)
                .filter { it.mergedInto == null && it.entityId != id }
                .sortedBy { it.canonicalName.lowercase(Locale.ROOT) }
        model.addAttribute("pageTitle", "Entity · ${entity.canonicalName}")
        model.addAttribute("entity", entity)
        model.addAttribute("perSubject", perSubject)
        model.addAttribute("mentionTotal", mentions.size)
        model.addAttribute("provisionalTotal", mentions.count { it.provisional })
        model.addAttribute("journal", entityJournal.findByEntity(id))
        model.addAttribute("mergeTargets", mergeTargets)
        return "admin/entity-detail"
    }

    /** Merge this entity into a live same-type target; land on the survivor. */
    @PostMapping("/entities/{id}/merge")
    fun mergeEntity(
        @PathVariable id: String,
        @RequestParam intoId: String,
        ra: RedirectAttributes,
    ): String =
        entityAdmin
            .merge(id, intoId.trim(), actor())
            .fold(
                {
                    ra.addFlashAttribute("error", it.message)
                    "redirect:/admin/entities/$id"
                },
                { o ->
                    ra.addFlashAttribute(
                        "ok",
                        "Merged — ${o.mentionsRewired} mention(s) rewired, " +
                            "${o.aliasesAdded.size} alias(es) added." +
                            rerunNote(o.affectedSubjectIds),
                    )
                    "redirect:/admin/entities/${o.intoEntityId}"
                },
            )

    /** Split: re-resolve this entity's mentions with the entity excluded (§11.3). */
    @PostMapping("/entities/{id}/split")
    fun splitEntity(@PathVariable id: String, ra: RedirectAttributes): String {
        entityAdmin
            .split(id, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { o ->
                    ra.addFlashAttribute(
                        "ok",
                        "Split — ${o.mentionsKept} mention(s) kept, ${o.mentionsMoved} moved, " +
                            "${o.entitiesMinted} entit(y/ies) minted." +
                            rerunNote(o.affectedSubjectIds),
                    )
                },
            )
        return "redirect:/admin/entities/$id"
    }

    private fun rerunNote(subjects: List<String>): String =
        if (subjects.isEmpty()) ""
        else " Blocking keys went stale — re-run Stage 3 for: ${subjects.joinToString(", ")}."

    // ---- VA-26: eval dashboard + blind labeling (§13) -------------------------------------

    @GetMapping("/stage3-eval")
    fun evalPage(model: Model): String {
        val records = eval.allMetrics()
        val currentStamp = judgeSampler.versionStamp
        val latest = records.firstOrNull()
        val labels = goldens.findAll()
        model.addAttribute("pageTitle", "Stage 3 eval")
        model.addAttribute("records", records)
        model.addAttribute("latest", latest)
        model.addAttribute("matrix", latest?.let { confusionMatrix(it) })
        model.addAttribute("calibration", latest?.let { calibrationChart(it) })
        model.addAttribute("currentStamp", currentStamp)
        model.addAttribute("staleMetrics", latest == null || latest.promptStamp != currentStamp)
        model.addAttribute("goldenTotal", labels.size)
        model.addAttribute("goldenTest", labels.count { it.split == "TEST" })
        model.addAttribute("goldenCalibration", labels.count { it.split == "CALIBRATION" })
        model.addAttribute(
            "goldenByRelation",
            labels.groupingBy { it.humanRelation }.eachCount().toSortedMap(),
        )
        model.addAttribute("subjects", subjectService.list())
        return "admin/stage3-eval"
    }

    @PostMapping("/stage3-eval/run-metrics")
    fun runMetrics(
        @RequestParam(required = false) referenceSubjectId: String?,
        ra: RedirectAttributes,
    ): String {
        eval
            .runMetrics(referenceSubjectId?.trim()?.takeIf { it.isNotBlank() }, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Metrics ran for ${it.promptStamp} — ${it.labeledPairs} labeled pair(s)" +
                            if (it.unjudgedTestPairs > 0)
                                " (${it.unjudgedTestPairs} TEST pair(s) unjudged under this " +
                                    "prompt — re-run the subject to grade them)"
                            else "",
                    )
                },
            )
        return "redirect:/admin/stage3-eval"
    }

    /**
     * The blind labeling flow: one §13-stratified unlabeled pair at a time — claims + context,
     * never the judge's verdict. Each label posts the shared REVIEWER route and bounces back here;
     * the sampler excludes labeled pairs, so the next card is always fresh.
     */
    @GetMapping("/stage3-eval/label")
    fun labelView(@RequestParam subjectId: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(subjectId)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/admin/stage3-eval"
                }
        return eval
            .samplePairs(subjectId, LABEL_BATCH)
            .fold(
                {
                    ra.addFlashAttribute("error", it.message)
                    "redirect:/admin/stage3-eval"
                },
                { pairs ->
                    model.addAttribute("pageTitle", "Label pairs · ${subject.displayName}")
                    model.addAttribute("subject", subject)
                    model.addAttribute("pair", pairs.firstOrNull())
                    model.addAttribute("batchRemaining", pairs.size)
                    "admin/stage3-eval-label"
                },
            )
    }

    private companion object {
        const val ENTITY_PAGE = 500
        const val REVIEW_PAGE = 200
        const val LABEL_BATCH = 10
    }
}

// ---- page view models (presentation shaping only) -----------------------------------------------

/** One subject's mention footprint on the entity-detail page (VA-25). */
data class EntityMentionGroupView(
    val subjectId: String,
    val subjectName: String,
    val mentionCount: Int,
    val provisionalCount: Int,
    val surfaces: List<String>,
)

/** The §13 confusion matrix, heat-shaded (VA-26). */
data class ConfusionMatrixView(val relations: List<String>, val rows: List<ConfusionRowView>)

data class ConfusionRowView(val human: String, val cells: List<ConfusionCellView>)

data class ConfusionCellView(val count: Long, val alpha: Double, val diagonal: Boolean)

/** The reliability diagram as precomputed SVG geometry — the template stays dumb (VA-26). */
data class CalibrationChartView(
    val width: Int,
    val height: Int,
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    /** Polyline through the bucket points, "x,y x,y …" — empty when nothing plottable. */
    val points: String,
    val markers: List<CalibrationMarkerView>,
)

data class CalibrationMarkerView(val cx: Double, val cy: Double, val r: Double, val title: String)

private val CONFUSION_RELATIONS = listOf("REPEATS", "CORROBORATES", "CONTRADICTS", "NEUTRAL")

private fun confusionMatrix(record: Stage3EvalMetricsRecord): ConfusionMatrixView {
    val counts = record.confusion.associate { (it.human to it.predicted) to it.count }
    val max = (record.confusion.maxOfOrNull { it.count } ?: 0L).coerceAtLeast(1L)
    return ConfusionMatrixView(
        relations = CONFUSION_RELATIONS,
        rows =
            CONFUSION_RELATIONS.map { human ->
                ConfusionRowView(
                    human = human,
                    cells =
                        CONFUSION_RELATIONS.map { predicted ->
                            val count = counts[human to predicted] ?: 0L
                            ConfusionCellView(
                                count = count,
                                alpha = 0.85 * count / max,
                                diagonal = human == predicted,
                            )
                        },
                )
            },
    )
}

private fun calibrationChart(record: Stage3EvalMetricsRecord): CalibrationChartView {
    val w = 360
    val h = 200
    val mLeft = 38.0
    val mRight = 12.0
    val mTop = 12.0
    val mBottom = 26.0
    fun x(v: Double) = mLeft + v * (w - mLeft - mRight)
    fun y(v: Double) = h - mBottom - v * (h - mTop - mBottom)
    val markers =
        record.calibration
            .filter { it.humanAgreementRate != null }
            .map { b ->
                val mid = (b.lowerBound + b.upperBound) / 2
                CalibrationMarkerView(
                    cx = x(b.meanAgreement ?: mid),
                    cy = y(b.humanAgreementRate!!),
                    r = 3.0 + minOf(b.pairs, 6L),
                    title =
                        String.format(
                            Locale.ROOT,
                            "agreement %.1f–%.1f: human-agreement %.2f over %d pair(s)",
                            b.lowerBound,
                            b.upperBound,
                            b.humanAgreementRate,
                            b.pairs,
                        ),
                )
            }
            .sortedBy { it.cx }
    return CalibrationChartView(
        width = w,
        height = h,
        x0 = x(0.0),
        y0 = y(0.0),
        x1 = x(1.0),
        y1 = y(1.0),
        points = markers.joinToString(" ") { "${it.cx},${it.cy}" },
        markers = markers,
    )
}

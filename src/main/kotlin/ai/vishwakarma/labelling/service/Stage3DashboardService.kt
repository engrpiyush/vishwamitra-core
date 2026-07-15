package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRecord
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import ai.vishwakarma.labelling.stage3.SubjectScore
import ai.vishwakarma.labelling.stage3.SubjectScorer
import ai.vishwakarma.labelling.stage3.TimelineView
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlin.math.abs
import org.springframework.stereotype.Service

/** One fact as the dashboard's evidence-vs-belief scatter plots it (Stage 3.5 LLD §6). */
data class DashboardFactPoint(
    val factId: String,
    val label: String,
    val slot: String?,
    val belief: Double,
    /** sat(mass) = mass / (mass + m0) — the x axis. */
    val saturation: Double,
    val evidenceMass: Double,
    val tier: String,
    val selfOnly: Boolean,
    val claimCount: Int,
)

/** One judged CONTRADICTS edge for the top-contradictions panel. */
data class DashboardContradiction(
    val fromFactId: String,
    val fromLabel: String,
    val toFactId: String,
    val toLabel: String,
    val confidence: Double,
    val explained: Boolean,
    val reviewStatus: String?,
    val rationale: String?,
    val viaEntities: List<String>,
)

/** CORROBORATES vs CONTRADICTS split by review state. */
data class DashboardEdgeSummary(
    val corroborations: Int,
    val contradictionsProposed: Int,
    val contradictionsConfirmed: Int,
    val contradictionsExplained: Int,
)

/**
 * Everything the authenticity dashboard shows — shared by the MVC page, the JSON API and the PDF
 * (Stage 3.5 LLD §6). Data only, no SVG: chart shaping stays with the callers (ChartSvg).
 */
data class DashboardData(
    val subjectId: String,
    val subjectName: String,
    val runId: String?,
    val runStatus: String?,
    /** True until the latest run is PUBLISHED — the badge + PDF watermark switch. */
    val provisional: Boolean,
    /** The live recompute over the graph readback. */
    val aggregate: SubjectScore,
    /** The ledger doc frozen at the last publish (null before the first). */
    val published: SubjectScoreRecord?,
    /** Live vs frozen differ visibly (params changed / graph moved since publish). */
    val divergesFromPublished: Boolean,
    /** Facts by scored tier, fixed HIGH/MEDIUM/LOW order. */
    val factTierCounts: Map<String, Int>,
    /** Claim scores in 20 × 0.05 buckets over [0,1]. */
    val claimScoreHistogram: List<Int>,
    /** Claims by source class. */
    val sourceClassCounts: Map<String, Int>,
    /** Distinct attestors by kind (from the aggregate inputs). */
    val attestorKindCounts: Map<String, Int>,
    val edgeSummary: DashboardEdgeSummary,
    val factPoints: List<DashboardFactPoint>,
    /** Highest-confidence contradictions first, capped at five. */
    val topContradictions: List<DashboardContradiction>,
    /** Distinct resolved entities mentioned by the scored claims. */
    val entityCount: Int,
    val timeline: TimelineView,
)

/**
 * Assembles [DashboardData] from one `scoresReadback` + one `timeline` read plus the latest run and
 * the frozen `subject_scores` doc. Domain errors ride Either (unknown subject → NotFound);
 * infrastructure failures propagate like every other graph read (the MVC caller runCatches).
 */
@Service
class Stage3DashboardService(
    private val subjects: SubjectRepository,
    private val runs: Stage3RunRepository,
    private val graph: Stage3GraphRepository,
    private val subjectScores: SubjectScoreRepository,
    private val config: StageConfigService,
) {

    fun dashboard(subjectId: String): Either<DomainError, DashboardData> {
        val subject =
            subjects.findById(subjectId)
                ?: return DomainError.NotFound("Subject $subjectId not found").left()
        val rows = graph.scoresReadback(subjectId)
        val aggregate = SubjectScorer.score(rows, config.stage3())
        val facts = SubjectScorer.factAggregates(rows)
        val edges = SubjectScorer.edgeAggregates(rows)
        val run: Stage3Run? = runs.findBySubject(subjectId).firstOrNull()
        val published = subjectScores.find(subjectId)

        val s3 = config.stage3()
        fun tierOf(belief: Double): String =
            when {
                belief >= s3.tierHigh -> "HIGH"
                belief >= s3.tierMedium -> "MEDIUM"
                else -> "LOW"
            }

        val factLabels = facts.associate { it.factId to it.label }
        val contradictions =
            edges.filter { it.relation == "CONTRADICTS" }.sortedByDescending { it.confidence }

        return DashboardData(
                subjectId = subjectId,
                subjectName = subject.displayName,
                runId = run?.id,
                runStatus = run?.status?.name,
                provisional = run?.status != Stage3RunStatus.PUBLISHED,
                aggregate = aggregate,
                published = published,
                divergesFromPublished =
                    published != null && abs(published.score - aggregate.score) >= 0.005,
                factTierCounts =
                    linkedMapOf("HIGH" to 0, "MEDIUM" to 0, "LOW" to 0).apply {
                        facts.forEach { f -> merge(tierOf(f.belief), 1, Int::plus) }
                    },
                claimScoreHistogram =
                    IntArray(20)
                        .also { buckets ->
                            rows.forEach { row ->
                                val i = (row.score * 20).toInt().coerceIn(0, 19)
                                buckets[i]++
                            }
                        }
                        .toList(),
                sourceClassCounts =
                    rows
                        .groupingBy { it.sourceClass ?: "UNKNOWN" }
                        .eachCount()
                        .toList()
                        .sortedByDescending { it.second }
                        .toMap(LinkedHashMap()),
                attestorKindCounts = aggregate.inputs.attestorsByKind,
                edgeSummary =
                    DashboardEdgeSummary(
                        corroborations = aggregate.inputs.corroborationCount,
                        contradictionsProposed = aggregate.inputs.proposedContradictionCount,
                        contradictionsConfirmed = aggregate.inputs.confirmedContradictionCount,
                        contradictionsExplained = aggregate.inputs.explainedContradictionCount,
                    ),
                factPoints =
                    facts.map { f ->
                        DashboardFactPoint(
                            factId = f.factId,
                            label = f.label,
                            slot = f.slot,
                            belief = f.belief,
                            saturation =
                                f.evidenceMass.coerceAtLeast(0.0) /
                                    (f.evidenceMass.coerceAtLeast(0.0) + s3.aggMassMidpoint),
                            evidenceMass = f.evidenceMass,
                            tier = tierOf(f.belief),
                            selfOnly = f.selfOnly,
                            claimCount = f.claimCount,
                        )
                    },
                topContradictions =
                    contradictions.take(5).map { e ->
                        DashboardContradiction(
                            fromFactId = e.fromFactId,
                            fromLabel = factLabels[e.fromFactId] ?: e.fromFactId,
                            toFactId = e.toFactId,
                            toLabel = factLabels[e.toFactId] ?: e.toFactId,
                            confidence = e.confidence,
                            explained = e.explained,
                            reviewStatus = e.reviewStatus,
                            rationale = e.rationale,
                            viaEntities = e.viaEntities,
                        )
                    },
                entityCount =
                    rows
                        .flatMap { row -> row.entities.mapNotNull { it["name"] as? String } }
                        .distinct()
                        .size,
                timeline = graph.timeline(subjectId),
            )
            .right()
    }
}

package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.JudgeAxis
import ai.vishwakarma.labelling.domain.Stage4Stamp
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.Stage4JudgmentRepository
import ai.vishwakarma.labelling.persistence.Stage4PlanRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.service.PersonaService
import ai.vishwakarma.labelling.stage4.Stage4Judging
import java.time.Instant
import java.util.Locale
import org.springframework.stereotype.Component

// ---- VA-65 view models: the Stage 4 panels the sft/dpo review pages gain -----------------------

/**
 * The §6 traceability stamp as chips, with each half checked against the subject's *current*
 * publish + resolved persona — a mismatch means the example is stale and the next SELECT tick
 * archives it (QA-6).
 */
data class StampChipsView(
    val subjectId: String,
    val category: String?,
    val scoreRunId: String?,
    val scoreRunCurrent: Boolean,
    val personaHashShort: String?,
    val personaCurrent: Boolean,
    val generatorPromptHash: String?,
    val planId: String?,
    val sourceClaimCount: Int,
) {
    val stale: Boolean
        get() = !scoreRunCurrent || !personaCurrent
}

data class JudgeAxisView(
    val label: String,
    val verdict: String,
    /** e.g. "PASS 2 · BORDERLINE 1". */
    val votesText: String?,
    val rationale: String?,
)

/** The latest §11 judge pass over the example, plus the re-judge posture. */
data class JudgePanelView(
    val overall: String,
    val axes: List<JudgeAxisView>,
    val model: String?,
    val judgedAt: Instant?,
    val promptVersion: Int?,
    val promptHash: String?,
    /**
     * True when the example's current turns hash differs from the judged one — the turns were
     * edited after this pass; the next run tick re-judges (§11 feedback loop).
     */
    val turnsStale: Boolean,
    /** Total passes on record (append-only collection — every re-judge adds one). */
    val passes: Int,
    /** The underlying `stage4_judgments` doc id. */
    val docId: String,
)

data class PlanClaimView(
    val claimId: String,
    val text: String,
    val scoreText: String?,
    val tier: String?,
)

/** The frozen voicing plan behind the conversation — "why this phrasing" (§8). */
data class PlanPanelView(
    val planId: String,
    val rowId: Int,
    val voice: String,
    val hedgeLevel: String,
    val question: String?,
    val category: String,
    val constraints: List<String>,
    val claims: List<PlanClaimView>,
    val sftEligible: Boolean,
)

/** The subject's current publish + persona pair — what a stamp must match to be current. */
data class CurrentStamp(val scoreRunId: String?, val personaHash: String)

/**
 * Builds the VA-65 review panels for Stage 4-stamped examples/pairs. Legacy (stamp-less) rows get
 * nulls everywhere — the existing sft/dpo lifecycle renders unchanged without them.
 */
@Component
class Stage4ReviewPanels(
    private val judgments: Stage4JudgmentRepository,
    private val plans: Stage4PlanRepository,
    private val claims: ClaimRepository,
    private val subjectScores: SubjectScoreRepository,
    private val personaService: PersonaService,
    private val props: AppProperties,
) {

    /**
     * The subject's current (scoreRunId, personaHash) pair. Resolving the persona reads Firestore
     * (persona doc + name pool + preset rows) — callers with many rows compute this once per
     * distinct subject, not per row.
     */
    fun currentFor(subjectId: String): CurrentStamp =
        CurrentStamp(
            scoreRunId = subjectScores.find(subjectId)?.scoreRunId,
            personaHash = personaService.resolved(subjectId).hash(),
        )

    fun stampChips(stamp: Stage4Stamp, current: CurrentStamp = currentFor(stamp.subjectId)) =
        StampChipsView(
            subjectId = stamp.subjectId,
            category = stamp.category?.name,
            scoreRunId = stamp.scoreRunId,
            scoreRunCurrent = current.scoreRunId != null && stamp.scoreRunId == current.scoreRunId,
            personaHashShort = stamp.personaHash?.take(12),
            personaCurrent = stamp.personaHash == current.personaHash,
            generatorPromptHash = stamp.generatorPromptHash,
            planId = stamp.planId,
            sourceClaimCount = stamp.sourceClaimIds.size,
        )

    /** The latest judge pass for the example, or null when it was never judged. */
    fun judgePanel(exampleId: String, turns: List<Turn>): JudgePanelView? {
        val passes = judgments.findByExample(exampleId)
        val latest = passes.firstOrNull() ?: return null
        // Fixed §11 axis order, not map order — the review reads the same rail every time.
        val axes =
            JudgeAxis.entries.mapNotNull { axis ->
                latest.axes[axis.name]?.let { result ->
                    JudgeAxisView(
                        label = axis.name.lowercase(Locale.ROOT).replace('_', ' '),
                        verdict = result.verdict.name,
                        votesText =
                            result.votes
                                .takeIf { it.isNotEmpty() }
                                ?.entries
                                ?.sortedByDescending { it.value }
                                ?.joinToString(" · ") { "${it.key} ${it.value}" },
                        rationale = result.rationale,
                    )
                }
            }
        return JudgePanelView(
            overall = latest.overall.name,
            axes = axes,
            model = latest.model,
            judgedAt = latest.createdAt,
            promptVersion = latest.judgePromptVersion,
            promptHash = latest.judgePromptHash,
            turnsStale =
                latest.turnsHash != null && Stage4Judging.turnsHash(turns) != latest.turnsHash,
            passes = passes.size,
            docId = latest.id,
        )
    }

    /** The frozen voicing plan behind the stamp, with its source claims' scores/tiers. */
    fun planPanel(stamp: Stage4Stamp): PlanPanelView? {
        val planId = stamp.planId ?: return null
        val doc = plans.findById(planId) ?: return null
        val plan = doc.plan
        return PlanPanelView(
            planId = planId,
            rowId = plan.rowId,
            voice = plan.voice,
            hedgeLevel = plan.hedgeLevel.name,
            question = doc.question,
            category = plan.category.name,
            constraints = plan.constraints,
            claims = plan.sourceClaimIds.map { claimView(it) },
            sftEligible = plan.sftEligible,
        )
    }

    private fun claimView(claimId: String): PlanClaimView {
        val claim =
            claims.findById(claimId)
                ?: return PlanClaimView(claimId, "(claim no longer in the ledger)", null, null)
        val score = claim.authenticityScore
        val tier =
            score?.let {
                when {
                    it >= props.stage3.tierHigh -> "HIGH"
                    it >= props.stage3.tierMedium -> "MEDIUM"
                    else -> "LOW"
                }
            }
        return PlanClaimView(
            claimId = claimId,
            text = claim.text.let { if (it.length > 160) it.take(157) + "…" else it },
            scoreText = score?.let { String.format(Locale.ROOT, "%.2f", it) },
            tier = tier,
        )
    }
}

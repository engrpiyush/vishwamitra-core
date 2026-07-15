package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.persistence.EvalBlockingRecall
import ai.vishwakarma.labelling.persistence.EvalCalibrationBucket
import ai.vishwakarma.labelling.persistence.EvalConfusionCell
import ai.vishwakarma.labelling.persistence.EvalRelationMetrics
import ai.vishwakarma.labelling.persistence.EvalScoreSanity
import ai.vishwakarma.labelling.persistence.Stage3EdgeRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeVerdict
import ai.vishwakarma.labelling.persistence.Stage3EvalMetricsRecord
import ai.vishwakarma.labelling.persistence.Stage3EvalMetricsRepository
import ai.vishwakarma.labelling.persistence.Stage3GoldenPair
import ai.vishwakarma.labelling.persistence.Stage3GoldenPairRepository
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.stage3.ClaimCard
import ai.vishwakarma.labelling.stage3.ClaimPair
import ai.vishwakarma.labelling.stage3.JudgeSampler
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import ai.vishwakarma.labelling.stage3.sha12
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import kotlin.random.Random
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** The five §13 label values — the four judge relations plus IRRELEVANT. */
private val HUMAN_RELATIONS =
    setOf("REPEATS", "CORROBORATES", "CONTRADICTS", "NEUTRAL", "IRRELEVANT")

private val SPLITS = setOf("CALIBRATION", "TEST")

/** One pair served by the labeling sampler — cards ready for the UI's label form. */
data class EvalSamplePair(
    val claimIdLow: String,
    val claimIdHigh: String,
    val subjectId: String,
    /** JUDGE_QUEUE (hard case) / SURVIVOR (auto-resolved) / PROBE (blocking-discarded). */
    val bucket: String,
    val a: ClaimCard,
    val b: ClaimCard,
)

/**
 * The §13 evaluation harness (VA-20): golden-pair labels (ride-alongs + the "label 10 random pairs"
 * sampler, stratified ~40% judge-queue hard cases / ~40% blocking survivors / ~20%
 * blocking-discarded probes) and the metrics job — judge P/R/F1 + confusion vs the TEST split,
 * blocking recall over labeled non-NEUTRAL pairs, the calibration reliability curve, and the
 * score-sanity flags on a reference subject — persisted per `judge-prompt-stamp|params-hash` so
 * prompt revisions stay comparable.
 */
@Service
class Stage3EvalService(
    private val goldens: Stage3GoldenPairRepository,
    private val metrics: Stage3EvalMetricsRepository,
    private val edges: Stage3EdgeRepository,
    private val graph: Stage3GraphRepository,
    private val runs: Stage3RunRepository,
    private val judgeSampler: JudgeSampler,
    private val config: StageConfigService,
) {

    private val log = LoggerFactory.getLogger(Stage3EvalService::class.java)

    // ---- labeling ---------------------------------------------------------------------

    fun label(
        subjectId: String,
        claimIdA: String,
        claimIdB: String,
        humanRelation: String,
        split: String?,
        actor: String?,
    ): Either<DomainError, Stage3GoldenPair> {
        if (subjectId.isBlank()) return DomainError.Invalid("subjectId is required").left()
        if (claimIdA.isBlank() || claimIdB.isBlank() || claimIdA == claimIdB)
            return DomainError.Invalid("A golden pair needs two distinct claim ids").left()
        val relation = humanRelation.trim().uppercase()
        if (relation !in HUMAN_RELATIONS)
            return DomainError.Invalid("humanRelation must be one of ${HUMAN_RELATIONS.sorted()}")
                .left()
        val chosenSplit = split?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (chosenSplit != null && chosenSplit !in SPLITS)
            return DomainError.Invalid("split must be one of ${SPLITS.sorted()}").left()
        val pair = ClaimPair.of(claimIdA, claimIdB)
        val row =
            Stage3GoldenPair(
                pairKey = Stage3GoldenPair.keyOf(pair),
                claimIdLow = pair.a,
                claimIdHigh = pair.b,
                subjectId = subjectId,
                humanRelation = relation,
                labeledBy = actor,
                labeledAt = Instant.now(),
                split = chosenSplit ?: defaultSplit(pair),
                sourceRunId = runs.findBySubject(subjectId).firstOrNull()?.id,
            )
        goldens.save(row)
        return row.right()
    }

    /**
     * Deterministic ~50/50 split assignment by pair key hash — stable across relabels, so a pair
     * can never drift between CALIBRATION and TEST unless explicitly moved.
     */
    private fun defaultSplit(pair: ClaimPair): String =
        if (sha12(Stage3GoldenPair.keyOf(pair)).take(2).toInt(16) % 2 == 0) "TEST"
        else "CALIBRATION"

    // ---- the "label 10 random pairs" sampler (§13 stratification) ------------------------

    fun samplePairs(subjectId: String, n: Int): Either<DomainError, List<EvalSamplePair>> {
        if (n <= 0) return DomainError.Invalid("n must be positive").left()
        val queued = graph.judgeQueuePairs(subjectId)
        val survivors = graph.autoRepeatsPairs(subjectId).filter { it !in queued.toSet() }
        val claimIds = graph.claimIds(subjectId)
        if (claimIds.isEmpty())
            return DomainError.NotFound(
                    "Subject $subjectId has no claims in the graph — run Stage 3 first"
                )
                .left()

        val surviving = (queued + survivors).toSet()
        val labeled = goldens.findBySubject(subjectId).map { it.pairKey }.toMutableSet()
        // Deterministic given the graph + label state: the mix reshuffles as labels accrete.
        val random = Random(subjectId.hashCode() * 31L + labeled.size)

        val nQueue = (n * 0.4).toInt().coerceAtLeast(if (n >= 3) 1 else 0)
        val nSurvivor = (n * 0.4).toInt()
        val nProbe = (n - nQueue - nSurvivor).coerceAtLeast(0)

        fun take(pool: List<ClaimPair>, want: Int): List<ClaimPair> =
            pool
                .filter { Stage3GoldenPair.keyOf(it) !in labeled }
                .shuffled(random)
                .take(want)
                .onEach { labeled += Stage3GoldenPair.keyOf(it) } // never serve twice in one batch

        val fromQueue = take(queued, nQueue)
        val fromSurvivors = take(survivors, nSurvivor)
        // Probes: random same-subject pairs with NO pair record — capped scan, not all C(n,2).
        val probePool = buildList {
            val ids = claimIds.sorted()
            var attempts = 0
            while (size < nProbe * PROBE_POOL_FACTOR && attempts < PROBE_MAX_ATTEMPTS) {
                attempts++
                val a = ids[random.nextInt(ids.size)]
                val b = ids[random.nextInt(ids.size)]
                if (a == b) continue
                val pair = ClaimPair.of(a, b)
                if (pair in surviving) continue
                if (pair !in this) add(pair)
            }
        }
        val fromProbes = take(probePool, nProbe)

        val byBucket =
            fromQueue.map { it to "JUDGE_QUEUE" } +
                fromSurvivors.map { it to "SURVIVOR" } +
                fromProbes.map { it to "PROBE" }
        val cards = graph.hydratePairs(subjectId, byBucket.map { it.first }).associateBy { it.pair }
        return byBucket
            .mapNotNull { (pair, bucket) ->
                cards[pair]?.let {
                    EvalSamplePair(
                        claimIdLow = pair.a,
                        claimIdHigh = pair.b,
                        subjectId = subjectId,
                        bucket = bucket,
                        a = it.a,
                        b = it.b,
                    )
                }
            }
            .right()
    }

    // ---- the metrics job (§13) -----------------------------------------------------------

    fun runMetrics(
        referenceSubjectId: String?,
        actor: String?,
    ): Either<DomainError, Stage3EvalMetricsRecord> {
        val all = goldens.findAll()
        if (all.isEmpty())
            return DomainError.Conflict(
                    "No golden pairs labeled yet — the metrics job needs a labeled set (§13)"
                )
                .left()
        val promptStamp = judgeSampler.versionStamp
        val paramsHash = sha12(Json.writeLine(config.stage3()))

        // Cached bare verdicts under the CURRENT prompt stamp; overridden rows never count.
        val verdictById =
            edges
                .findAll(
                    all.map {
                        Stage3EdgeVerdict.cacheId(it.pair(), withContext = false, promptStamp)
                    }
                )
                .filterValues { !it.overridden }
        fun verdictOf(pair: Stage3GoldenPair): Stage3EdgeVerdict? =
            verdictById[Stage3EdgeVerdict.cacheId(pair.pair(), withContext = false, promptStamp)]

        // Judge P/R/F1 + confusion on the TEST split (IRRELEVANT counts as NEUTRAL — the judge
        // has no IRRELEVANT class; §13's fifth label exists for blocking probes).
        val test = all.filter { it.split == "TEST" }
        val judgedTest = test.mapNotNull { g -> verdictOf(g)?.let { g to it } }
        val confusion =
            judgedTest
                .groupingBy { (g, v) -> expectedRelation(g.humanRelation) to v.relation }
                .eachCount()
                .map { (key, count) -> EvalConfusionCell(key.first, key.second, count.toLong()) }
                .sortedWith(compareBy({ it.human }, { it.predicted }))
        val relations = listOf("REPEATS", "CORROBORATES", "CONTRADICTS", "NEUTRAL")
        val perRelation =
            relations.associateWith { r ->
                val tp =
                    judgedTest.count { (g, v) ->
                        expectedRelation(g.humanRelation) == r && v.relation == r
                    }
                val fp =
                    judgedTest.count { (g, v) ->
                        expectedRelation(g.humanRelation) != r && v.relation == r
                    }
                val fn =
                    judgedTest.count { (g, v) ->
                        expectedRelation(g.humanRelation) == r && v.relation != r
                    }
                val precision = ratio(tp, tp + fp)
                val recall = ratio(tp, tp + fn)
                EvalRelationMetrics(
                    precision = precision,
                    recall = recall,
                    f1 =
                        if (precision != null && recall != null && precision + recall > 0)
                            2 * precision * recall / (precision + recall)
                        else null,
                    support =
                        judgedTest
                            .count { (g, _) -> expectedRelation(g.humanRelation) == r }
                            .toLong(),
                )
            }

        // Blocking recall (both splits — blocking is never threshold-tuned): labeled non-NEUTRAL
        // pairs that still have a pair record from PRUNED blocking.
        val nonNeutral =
            all.filter { it.humanRelation in setOf("REPEATS", "CORROBORATES", "CONTRADICTS") }
        val survived =
            nonNeutral
                .groupBy { it.subjectId }
                .entries
                .sumOf { (subjectId, rows) ->
                    graph.pairRecords(subjectId, rows.map { it.pair() }).size
                }
        val blockingRecall =
            EvalBlockingRecall(
                nonNeutralLabeled = nonNeutral.size.toLong(),
                survivedBlocking = survived.toLong(),
                recall = ratio(survived, nonNeutral.size),
            )

        // Calibration curve (all splits): ensemble agreement buckets vs human-agreement rate.
        val calibrationPoints =
            all.mapNotNull { g ->
                verdictOf(g)?.let { v ->
                    val agreement =
                        (v.votes.values.maxOrNull() ?: 0L).toDouble() /
                            config.stage3().ensembleK.coerceAtLeast(1)
                    agreement.coerceIn(0.0, 1.0) to
                        (v.relation == expectedRelation(g.humanRelation))
                }
            }
        val calibration =
            (0 until CALIBRATION_BUCKETS).map { i ->
                val lo = i / CALIBRATION_BUCKETS.toDouble()
                val hi = (i + 1) / CALIBRATION_BUCKETS.toDouble()
                val inBucket =
                    calibrationPoints.filter { (a, _) ->
                        a >= lo && (a < hi || (i == CALIBRATION_BUCKETS - 1 && a <= hi))
                    }
                EvalCalibrationBucket(
                    lowerBound = lo,
                    upperBound = hi,
                    pairs = inBucket.size.toLong(),
                    meanAgreement = inBucket.map { it.first }.takeIf { it.isNotEmpty() }?.average(),
                    humanAgreementRate =
                        inBucket
                            .takeIf { it.isNotEmpty() }
                            ?.let { pts -> pts.count { it.second }.toDouble() / pts.size },
                )
            }

        val record =
            Stage3EvalMetricsRecord(
                id = "$promptStamp|$paramsHash",
                promptStamp = promptStamp,
                paramsHash = paramsHash,
                ranAt = Instant.now(),
                ranBy = actor,
                labeledPairs = all.size.toLong(),
                testPairs = test.size.toLong(),
                calibrationPairs = all.count { it.split == "CALIBRATION" }.toLong(),
                unjudgedTestPairs = (test.size - judgedTest.size).toLong(),
                confusion = confusion,
                perRelation = perRelation,
                blockingRecall = blockingRecall,
                calibration = calibration,
                sanity = referenceSubjectId?.let { sanityFlags(it) },
            )
        metrics.save(record)
        log.info(
            "Eval metrics {}: {} labeled pair(s), {} judged TEST, blocking recall {}/{}",
            record.id,
            record.labeledPairs,
            judgedTest.size,
            survived,
            nonNeutral.size,
        )
        return record.right()
    }

    fun allMetrics(): List<Stage3EvalMetricsRecord> = metrics.findAll()

    /** The §13 sanity ranks: documents > endorsed > self-only praise; explanations recover. */
    private fun sanityFlags(subjectId: String): EvalScoreSanity {
        val rows = graph.factSanityRows(subjectId).filter { it.belief != null }
        val favorabilityThreshold = config.stage2().favorabilityThreshold
        fun mean(list: List<ai.vishwakarma.labelling.stage3.FactSanityRow>): Double? =
            list.mapNotNull { it.belief }.takeIf { it.isNotEmpty() }?.average()
        val meanAnchored = mean(rows.filter { it.anchored })
        val meanEndorsed = mean(rows.filter { !it.anchored && "ENDORSEMENT" in it.sourceClasses })
        val meanSelfPraise =
            mean(
                rows.filter { row ->
                    !row.anchored &&
                        row.sourceClasses.isNotEmpty() &&
                        row.sourceClasses.all { it == "SELF" } &&
                        row.favorabilities.isNotEmpty() &&
                        row.favorabilities.all { it >= favorabilityThreshold }
                }
            )
        val explained = rows.filter { it.hasExplainedContradiction && it.beliefBare != null }
        return EvalScoreSanity(
            referenceSubjectId = subjectId,
            documentsOverEndorsed =
                if (meanAnchored != null && meanEndorsed != null) meanAnchored > meanEndorsed
                else null,
            endorsedOverSelfPraise =
                if (meanEndorsed != null && meanSelfPraise != null) meanEndorsed > meanSelfPraise
                else null,
            explainedContradictionsRecover =
                explained
                    .takeIf { it.isNotEmpty() }
                    ?.let { facts ->
                        facts.all { it.belief!! >= it.beliefBare!! } &&
                            facts.any { it.belief!! > it.beliefBare!! }
                    },
            meanAnchoredBelief = meanAnchored,
            meanEndorsedBelief = meanEndorsed,
            meanSelfPraiseBelief = meanSelfPraise,
        )
    }

    /** IRRELEVANT (a §13 label, not a judge class) grades the judge as NEUTRAL-expected. */
    private fun expectedRelation(humanRelation: String): String =
        if (humanRelation == "IRRELEVANT") "NEUTRAL" else humanRelation

    private fun ratio(num: Int, den: Int): Double? = if (den > 0) num.toDouble() / den else null

    private companion object {
        const val CALIBRATION_BUCKETS = 5
        /** Probe over-sampling factor + scan cap: enough candidates without an all-pairs walk. */
        const val PROBE_POOL_FACTOR = 3
        const val PROBE_MAX_ATTEMPTS = 500
    }
}

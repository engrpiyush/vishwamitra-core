package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.persistence.Stage3EdgeRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeVerdict
import ai.vishwakarma.labelling.persistence.Stage3EvalMetricsRecord
import ai.vishwakarma.labelling.persistence.Stage3EvalMetricsRepository
import ai.vishwakarma.labelling.persistence.Stage3GoldenPair
import ai.vishwakarma.labelling.persistence.Stage3GoldenPairRepository
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.stage3.ClaimCard
import ai.vishwakarma.labelling.stage3.ClaimPair
import ai.vishwakarma.labelling.stage3.FactSanityRow
import ai.vishwakarma.labelling.stage3.JudgeRelation
import ai.vishwakarma.labelling.stage3.JudgeSample
import ai.vishwakarma.labelling.stage3.JudgeSampler
import ai.vishwakarma.labelling.stage3.PairToJudge
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.neo4j.driver.Driver

private fun <A, B> Either<A, B>.valueOrNull(): B? = fold({ null }, { it })

private fun <A, B> Either<A, B>.errorOrNull(): A? = fold({ it }, { null })

private class FakeGoldenRepo : Stage3GoldenPairRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage3GoldenPair>()

    override fun save(row: Stage3GoldenPair) {
        store[row.pairKey] = row
    }

    override fun findAll(): List<Stage3GoldenPair> = store.values.sortedBy { it.pairKey }

    override fun findBySubject(subjectId: String): List<Stage3GoldenPair> =
        store.values.filter { it.subjectId == subjectId }.sortedBy { it.pairKey }
}

private class FakeMetricsRepo : Stage3EvalMetricsRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage3EvalMetricsRecord>()

    override fun save(record: Stage3EvalMetricsRecord) {
        store[record.id] = record
    }

    override fun findAll(): List<Stage3EvalMetricsRecord> =
        store.values.sortedByDescending { it.ranAt }
}

private class FakeVerdictRepo : Stage3EdgeRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage3EdgeVerdict>()

    fun verdict(
        pair: ClaimPair,
        stamp: String,
        relation: JudgeRelation,
        votes: Map<String, Long>,
        overridden: Boolean = false,
    ) {
        val id = Stage3EdgeVerdict.cacheId(pair, withContext = false, stamp)
        store[id] =
            Stage3EdgeVerdict(
                id = id,
                subjectId = "s1",
                claimIdLow = pair.a,
                claimIdHigh = pair.b,
                withContext = false,
                promptStamp = stamp,
                relation = relation.name,
                confidence = 0.8,
                votes = votes,
                rationale = null,
                temporalNote = null,
                explanationRelevant = null,
                explanationHash = null,
                floored = false,
                tie = false,
                judgeModel = "test",
                judgedAt = Instant.now(),
                overridden = overridden,
            )
    }

    override fun findAll(ids: Collection<String>): Map<String, Stage3EdgeVerdict> =
        ids.mapNotNull { store[it] }.associateBy { it.id }
}

private class FakeEvalGraph(props: AppProperties) :
    Stage3GraphRepository(mock(Driver::class.java), props) {
    var queue = listOf<ClaimPair>()
    var autoRepeats = listOf<ClaimPair>()
    var claims = listOf<String>()
    var records = setOf<ClaimPair>()
    var sanityRows = listOf<FactSanityRow>()

    override fun judgeQueuePairs(subjectId: String): List<ClaimPair> = queue

    override fun autoRepeatsPairs(subjectId: String): List<ClaimPair> = autoRepeats

    override fun claimIds(subjectId: String): List<String> = claims

    override fun pairRecords(subjectId: String, pairs: Collection<ClaimPair>): Set<ClaimPair> =
        pairs.filter { it in records }.toSet()

    override fun factSanityRows(subjectId: String): List<FactSanityRow> = sanityRows

    override fun hydratePairs(subjectId: String, pairs: Collection<ClaimPair>): List<PairToJudge> =
        pairs.map { p ->
            PairToJudge(
                pair = p,
                rank = 0,
                withContext = false,
                humanAsserted = false,
                a = card(p.a),
                b = card(p.b),
                sharedEntities = emptyList(),
            )
        }

    private fun card(id: String) =
        ClaimCard(id, "text of $id", "SKILL", null, "SELF", null, null, null)
}

private class StampedSampler(var stamp: String) : JudgeSampler {
    override val versionStamp: String
        get() = stamp

    override val modelId = "test"

    override fun sample(
        pairs: List<PairToJudge>,
        withContext: Boolean,
        sampleIndex: Int,
    ): Map<ClaimPair, JudgeSample> = emptyMap()
}

private class EvalRunRepo : Stage3RunRepository(mock(Firestore::class.java)) {
    override fun findBySubject(subjectId: String): List<Stage3Run> =
        listOf(Stage3Run(id = "run-9", subjectId = subjectId, createdAt = Instant.now()))
}

class Stage3EvalServiceTest {

    private val props = AppProperties()
    private val goldens = FakeGoldenRepo()
    private val metricsRepo = FakeMetricsRepo()
    private val verdicts = FakeVerdictRepo()
    private val graph = FakeEvalGraph(props)
    private val sampler = StampedSampler("test:1:hashA")

    private fun service() =
        Stage3EvalService(goldens, metricsRepo, verdicts, graph, EvalRunRepo(), sampler, props)

    private fun label(
        a: String,
        b: String,
        relation: String,
        split: String? = null,
    ): Stage3GoldenPair =
        service().label("s1", a, b, relation, split, "labeler@test").valueOrNull()!!

    // ---- labels -------------------------------------------------------------------

    @Test
    fun `labels round-trip with validation, deterministic splits, and overwrite semantics`() {
        val row = label("c2", "c1", "repeats")
        assertEquals("c1|c2", row.pairKey) // unordered — stored (low, high)
        assertEquals("REPEATS", row.humanRelation)
        assertEquals("run-9", row.sourceRunId)
        assertTrue(row.split in setOf("CALIBRATION", "TEST"))
        // Same pair, same default split — deterministic by pair-key hash.
        assertEquals(row.split, label("c1", "c2", "NEUTRAL").split)
        // Relabel overwrites: one doc per pair.
        assertEquals(1, goldens.store.size)
        assertEquals("NEUTRAL", goldens.store.getValue("c1|c2").humanRelation)
        // Explicit split honored; junk refused.
        assertEquals("TEST", label("c1", "c3", "CONTRADICTS", split = "test").split)
        val svc = service()
        assertTrue(
            svc.label("s1", "c1", "c1", "REPEATS", null, null).errorOrNull() is DomainError.Invalid
        )
        assertTrue(
            svc.label("s1", "c1", "c4", "MAYBE", null, null).errorOrNull() is DomainError.Invalid
        )
        assertTrue(
            svc.label("s1", "c1", "c4", "REPEATS", "VALIDATION", null).errorOrNull()
                is DomainError.Invalid
        )
    }

    // ---- the stratified sampler -----------------------------------------------------

    @Test
    fun `sampler returns the stratified mix and never re-serves a labeled pair`() {
        graph.claims = (1..30).map { "c%02d".format(it) }
        graph.queue = (1..10).map { ClaimPair.of("c%02d".format(it), "c%02d".format(it + 10)) }
        graph.autoRepeats = (1..8).map { ClaimPair.of("c%02d".format(it), "c%02d".format(it + 20)) }

        val labeledQueue = graph.queue.first()
        label(labeledQueue.a, labeledQueue.b, "REPEATS")

        val sample = service().samplePairs("s1", 10).valueOrNull()
        assertNotNull(sample)
        assertEquals(10, sample.size)
        val byBucket = sample.groupBy { it.bucket }
        assertEquals(4, byBucket["JUDGE_QUEUE"]?.size)
        assertEquals(4, byBucket["SURVIVOR"]?.size)
        assertEquals(2, byBucket["PROBE"]?.size)
        // Cards hydrate; the labeled pair never re-serves; no duplicates in one batch.
        assertTrue(sample.all { it.a.text.isNotBlank() && it.b.text.isNotBlank() })
        assertTrue(
            sample.none { it.claimIdLow == labeledQueue.a && it.claimIdHigh == labeledQueue.b }
        )
        assertEquals(
            sample.size,
            sample.map { "${it.claimIdLow}|${it.claimIdHigh}" }.distinct().size
        )
        // Probes carry no pair record — they are the blocking-recall strata.
        byBucket["PROBE"]!!.forEach { p ->
            assertTrue(
                ClaimPair(p.claimIdLow, p.claimIdHigh) !in (graph.queue.toSet() + graph.autoRepeats)
            )
        }
    }

    // ---- the metrics job ---------------------------------------------------------------

    @Test
    fun `metrics job produces hand-checkable precision recall and confusion`() {
        val stamp = sampler.stamp
        fun golden(a: String, b: String, relation: String, split: String = "TEST") {
            label(a, b, relation, split)
        }
        // TEST split: REPEATS hit, REPEATS miss (→NEUTRAL), CONTRADICTS hit, NEUTRAL
        // false-positive (→REPEATS), IRRELEVANT graded as NEUTRAL hit, and one unjudged pair.
        golden("c1", "c2", "REPEATS")
        verdicts.verdict(
            ClaimPair.of("c1", "c2"),
            stamp,
            JudgeRelation.REPEATS,
            mapOf("REPEATS" to 5)
        )
        golden("c3", "c4", "REPEATS")
        verdicts.verdict(
            ClaimPair.of("c3", "c4"),
            stamp,
            JudgeRelation.NEUTRAL,
            mapOf("NEUTRAL" to 3, "REPEATS" to 2)
        )
        golden("c5", "c6", "CONTRADICTS")
        verdicts.verdict(
            ClaimPair.of("c5", "c6"),
            stamp,
            JudgeRelation.CONTRADICTS,
            mapOf("CONTRADICTS" to 4, "NEUTRAL" to 1)
        )
        golden("c7", "c8", "NEUTRAL")
        verdicts.verdict(
            ClaimPair.of("c7", "c8"),
            stamp,
            JudgeRelation.REPEATS,
            mapOf("REPEATS" to 3, "NEUTRAL" to 2)
        )
        golden("d1", "d2", "IRRELEVANT")
        verdicts.verdict(
            ClaimPair.of("d1", "d2"),
            stamp,
            JudgeRelation.NEUTRAL,
            mapOf("NEUTRAL" to 5)
        )
        golden("d3", "d4", "CORROBORATES") // never judged under this stamp

        graph.records =
            setOf(ClaimPair.of("c1", "c2"), ClaimPair.of("c3", "c4"), ClaimPair.of("c5", "c6"))

        val record = service().runMetrics(null, "admin@test").valueOrNull()
        assertNotNull(record)
        assertEquals(6L, record.labeledPairs)
        assertEquals(6L, record.testPairs)
        assertEquals(1L, record.unjudgedTestPairs)

        // REPEATS: TP=1 (c1c2), FP=1 (c7c8), FN=1 (c3c4) → P=R=F1=0.5.
        val repeats = record.perRelation.getValue("REPEATS")
        assertEquals(0.5, repeats.precision)
        assertEquals(0.5, repeats.recall)
        assertEquals(0.5, repeats.f1)
        assertEquals(2L, repeats.support)
        // CONTRADICTS: clean hit.
        val contradicts = record.perRelation.getValue("CONTRADICTS")
        assertEquals(1.0, contradicts.precision)
        assertEquals(1.0, contradicts.recall)
        // NEUTRAL: TP=1 (IRRELEVANT-mapped d1d2), FP=1 (c3c4), FN=1 (c7c8).
        val neutral = record.perRelation.getValue("NEUTRAL")
        assertEquals(0.5, neutral.precision)
        assertEquals(0.5, neutral.recall)
        // Confusion matrix cells.
        fun cell(human: String, predicted: String): Long =
            record.confusion.firstOrNull { it.human == human && it.predicted == predicted }?.count
                ?: 0L
        assertEquals(1L, cell("REPEATS", "REPEATS"))
        assertEquals(1L, cell("REPEATS", "NEUTRAL"))
        assertEquals(1L, cell("NEUTRAL", "REPEATS"))
        assertEquals(1L, cell("NEUTRAL", "NEUTRAL")) // the IRRELEVANT mapping
        assertEquals(1L, cell("CONTRADICTS", "CONTRADICTS"))

        // Blocking recall: non-NEUTRAL labeled = {c1c2, c3c4, c5c6, d3d4}; d3d4 was planted
        // outside blocking (EXHAUSTIVE would find it; PRUNED missed it) → 3/4.
        assertEquals(4L, record.blockingRecall.nonNeutralLabeled)
        assertEquals(3L, record.blockingRecall.survivedBlocking)
        assertEquals(0.75, record.blockingRecall.recall)

        // Calibration: agreement 1.0 ×2 (c1c2, d1d2) in the top bucket, agreement 0.6 (c3c4,
        // c7c8) and 0.8 (c5c6) in the [0.6, 0.8) and top buckets respectively.
        val top = record.calibration.last()
        assertEquals(0.8, top.lowerBound)
        assertEquals(3L, top.pairs) // 1.0, 1.0, 0.8
        val mid = record.calibration[3]
        assertEquals(0.6, mid.lowerBound)
        assertEquals(2L, mid.pairs)
        // In the mid bucket the judge disagreed with humans once (c3c4) and agreed once? No —
        // c3c4 predicted NEUTRAL vs human REPEATS (miss), c7c8 predicted REPEATS vs human
        // NEUTRAL (miss) → rate 0.0; the top bucket is all hits.
        assertEquals(0.0, mid.humanAgreementRate)
        assertEquals(1.0, top.humanAgreementRate)
    }

    @Test
    fun `two prompt versions persist two comparable records`() {
        label("c1", "c2", "REPEATS")
        val first = service().runMetrics(null, null).valueOrNull()
        assertNotNull(first)
        sampler.stamp = "test:2:hashB"
        val second = service().runMetrics(null, null).valueOrNull()
        assertNotNull(second)
        assertTrue(first.id != second.id)
        assertEquals(2, service().allMetrics().size)
        // Same version re-run refreshes in place instead of duplicating.
        service().runMetrics(null, null)
        assertEquals(2, service().allMetrics().size)
    }

    @Test
    fun `sanity flags rank documents over endorsed over self-praise and see recovery`() {
        label("c1", "c2", "REPEATS")
        graph.sanityRows =
            listOf(
                FactSanityRow(
                    "f1",
                    0.90,
                    0.90,
                    true,
                    listOf("DOCUMENTARY", "SELF"),
                    listOf(0.8, 0.7),
                    false
                ),
                FactSanityRow(
                    "f2",
                    0.74,
                    0.74,
                    false,
                    listOf("ENDORSEMENT", "SELF"),
                    listOf(0.9, 0.9),
                    false
                ),
                FactSanityRow("f3", 0.35, 0.31, false, listOf("SELF"), listOf(0.9), true),
                FactSanityRow("f4", 0.35, 0.35, false, listOf("SELF"), listOf(0.9), false),
            )
        val record = service().runMetrics("s1", null).valueOrNull()
        assertNotNull(record)
        val sanity = record.sanity
        assertNotNull(sanity)
        assertEquals(true, sanity.documentsOverEndorsed)
        assertEquals(true, sanity.endorsedOverSelfPraise)
        assertEquals(true, sanity.explainedContradictionsRecover)
        assertEquals(0.90, sanity.meanAnchoredBelief)
        assertEquals(0.74, sanity.meanEndorsedBelief)
        assertEquals(0.35, sanity.meanSelfPraiseBelief)
    }

    @Test
    fun `metrics job refuses an empty golden set and skips overridden verdicts`() {
        assertTrue(service().runMetrics(null, null).errorOrNull() is DomainError.Conflict)

        label("c1", "c2", "REPEATS", split = "TEST")
        verdicts.verdict(
            ClaimPair.of("c1", "c2"),
            sampler.stamp,
            JudgeRelation.REPEATS,
            mapOf("REPEATS" to 5),
            overridden = true,
        )
        val record = service().runMetrics(null, null).valueOrNull()
        assertNotNull(record)
        // The overridden verdict never grades — the pair counts as unjudged.
        assertEquals(1L, record.unjudgedTestPairs)
        assertEquals(0L, record.perRelation.getValue("REPEATS").support)
    }
}

package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeVerdict
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.ProviderService
import ai.vishwakarma.labelling.service.ResolvedExtractionPrompt
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakeEdgeRepo : Stage3EdgeRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage3EdgeVerdict>()

    override fun findAll(ids: Collection<String>): Map<String, Stage3EdgeVerdict> =
        ids.mapNotNull { store[it] }.associateBy { it.id }

    override fun saveAll(rows: List<Stage3EdgeVerdict>) {
        rows.forEach { store[it.id] = it }
    }

    override fun markOverridden(claimIdLow: String, claimIdHigh: String): Int {
        val hits =
            store.values.filter { it.claimIdLow == claimIdLow && it.claimIdHigh == claimIdHigh }
        hits.forEach { store[it.id] = it.copy(overridden = true) }
        return hits.size
    }

    override fun deleteBySubject(subjectId: String): Int {
        val ids = store.values.filter { it.subjectId == subjectId }.map { it.id }
        ids.forEach { store.remove(it) }
        return ids.size
    }
}

/** Scriptable ensemble member: fixed per-pair relation, observable calls, a failure switch. */
private class ScriptedSampler(
    var verdicts: Map<ClaimPair, JudgeRelation> = emptyMap(),
    var confidence: Double = 0.9,
) : JudgeSampler {
    var promptVersion = 1
    var calls = 0
    val seenWithContext = mutableListOf<Boolean>()
    val seenBatchSizes = mutableListOf<Int>()

    override val versionStamp: String
        get() = "test:$promptVersion:hash$promptVersion"

    override val modelId = "test-judge"

    // Synchronized: the ensemble fans samples out across threads (2026-07-11) — the counter and
    // the seen-lists must not race.
    @Synchronized
    override fun sample(
        pairs: List<PairToJudge>,
        withContext: Boolean,
        sampleIndex: Int,
    ): Map<ClaimPair, JudgeSample> {
        calls++
        seenWithContext += withContext
        seenBatchSizes += pairs.size
        return pairs.associate {
            it.pair to
                JudgeSample(
                    relation = verdicts[it.pair] ?: JudgeRelation.NEUTRAL,
                    confidence = confidence,
                    rationale = "scripted",
                    temporalNote = null,
                    explanationRelevant = if (withContext) true else null,
                )
        }
    }
}

class ClaimJudgeServiceTest {

    private val props =
        AppProperties(
            stage3 =
                AppProperties.Stage3(ensembleK = 3, judgeBatchSize = 2, judgeConfidenceFloor = 0.55)
        )
    private val edges = FakeEdgeRepo()
    private val sampler = ScriptedSampler()
    private val service = ClaimJudgeService(sampler, edges, liveConfig(props))

    private fun pair(
        a: String,
        b: String,
        withContext: Boolean = false,
        explanation: String? = null
    ) =
        PairToJudge(
            pair = ClaimPair.of(a, b),
            rank = 0,
            withContext = withContext,
            humanAsserted = false,
            a =
                ClaimCard(
                    claimId = minOf(a, b),
                    text = "text of ${minOf(a, b)}",
                    type = "SKILL",
                    claimedDate = null,
                    sourceClass = "SELF",
                    relationship = "SELF",
                    speakerRole = null,
                    explanationText = explanation,
                ),
            b =
                ClaimCard(
                    claimId = maxOf(a, b),
                    text = "text of ${maxOf(a, b)}",
                    type = "SKILL",
                    claimedDate = null,
                    sourceClass = "SELF",
                    relationship = "SELF",
                    speakerRole = null,
                    explanationText = null,
                ),
            sharedEntities = emptyList(),
        )

    @Test
    fun `misses run k samples per sub-batch and persist aggregated verdicts to the cache`() {
        sampler.verdicts = mapOf(ClaimPair.of("c1", "c2") to JudgeRelation.REPEATS)
        val outcome =
            service.judgePairs("s1", listOf(pair("c1", "c2"), pair("c3", "c4"), pair("c5", "c6")))
        // 3 pairs at judge-batch-size 2 → two sub-batches × k=3 samples.
        assertEquals(6, sampler.calls)
        assertEquals(6L, outcome.samplerCalls)
        assertEquals(0L, outcome.cacheHits)
        assertEquals(3, outcome.judged.size)
        val repeats = outcome.judged.first { it.pair == ClaimPair.of("c1", "c2") }
        assertEquals(JudgeRelation.REPEATS, repeats.bare.relation)
        assertEquals(0.9, repeats.bare.confidence, 1e-9)
        assertNull(repeats.ctx)
        assertEquals("test-judge", repeats.judgeModel)
        // Cache rows: one bare variant per pair.
        assertEquals(3, edges.store.size)
        assertTrue(edges.store.values.all { !it.withContext && it.subjectId == "s1" })
    }

    @Test
    fun `a second pass over the same pairs is fully cache-served`() {
        val pairs = listOf(pair("c1", "c2"), pair("c3", "c4"))
        service.judgePairs("s1", pairs)
        val callsAfterFirst = sampler.calls
        val outcome = service.judgePairs("s1", pairs)
        assertEquals(callsAfterFirst, sampler.calls)
        assertEquals(0L, outcome.samplerCalls)
        assertEquals(2L, outcome.cacheHits)
    }

    @Test
    fun `a prompt version bump misses the cache and re-judges everything`() {
        val pairs = listOf(pair("c1", "c2"))
        service.judgePairs("s1", pairs)
        sampler.promptVersion = 2
        val outcome = service.judgePairs("s1", pairs)
        assertEquals(0L, outcome.cacheHits)
        assertTrue(outcome.samplerCalls > 0)
        // Both prompt versions' verdicts persist (the audit-across-versions posture).
        assertEquals(2, edges.store.size)
    }

    @Test
    fun `an overridden verdict never hits again`() {
        val pairs = listOf(pair("c1", "c2"))
        service.judgePairs("s1", pairs)
        edges.markOverridden("c1", "c2")
        val outcome = service.judgePairs("s1", pairs)
        assertEquals(0L, outcome.cacheHits)
        assertTrue(outcome.samplerCalls > 0)
    }

    @Test
    fun `withContext pairs judge two variants and cache them separately`() {
        sampler.verdicts = mapOf(ClaimPair.of("c1", "c2") to JudgeRelation.CONTRADICTS)
        val pairs = listOf(pair("c1", "c2", withContext = true, explanation = "context here"))
        val outcome = service.judgePairs("s1", pairs)
        // One sub-batch per variant × k=3.
        assertEquals(6L, outcome.samplerCalls)
        assertTrue(sampler.seenWithContext.contains(true))
        assertTrue(sampler.seenWithContext.contains(false))
        val judged = outcome.judged.single()
        assertEquals(JudgeRelation.CONTRADICTS, judged.bare.relation)
        assertNotNull(judged.ctx)
        assertTrue(judged.ctx!!.explanationRelevant)
        assertEquals(2, edges.store.size)
        assertEquals(setOf(false, true), edges.store.values.map { it.withContext }.toSet())
        // The ctx row records the explanation hash for staleness checks.
        assertTrue(edges.store.values.single { it.withContext }.explanationHash != null)
    }

    @Test
    fun `a changed explanation misses only the ctx variant`() {
        sampler.verdicts = mapOf(ClaimPair.of("c1", "c2") to JudgeRelation.CONTRADICTS)
        service.judgePairs("s1", listOf(pair("c1", "c2", withContext = true, explanation = "v1")))
        val outcome =
            service.judgePairs(
                "s1",
                listOf(pair("c1", "c2", withContext = true, explanation = "v2 — edited")),
            )
        // Bare hits; ctx re-judges (k=3 over one sub-batch).
        assertEquals(1L, outcome.cacheHits)
        assertEquals(3L, outcome.samplerCalls)
    }

    @Test
    fun `low-agreement verdicts floor to NEUTRAL through the shared aggregation path`() {
        // Scripted confidence 0.5 with full agreement: 3/3 × 0.5 = 0.5 < 0.55 floor.
        sampler.verdicts = mapOf(ClaimPair.of("c1", "c2") to JudgeRelation.CONTRADICTS)
        sampler.confidence = 0.5
        val outcome = service.judgePairs("s1", listOf(pair("c1", "c2")))
        val verdict = outcome.judged.single().bare
        assertEquals(JudgeRelation.NEUTRAL, verdict.relation)
        assertTrue(verdict.floored)
        assertEquals(mapOf("CONTRADICTS" to 3), verdict.votes)
    }

    @Test
    fun `empty input is a no-op`() {
        val outcome = service.judgePairs("s1", emptyList())
        assertEquals(0, outcome.judged.size)
        assertEquals(0, sampler.calls)
        assertFalse(edges.store.isNotEmpty())
    }

    // ---- the Gemini ensemble member -------------------------------------------------

    private class FakeGemini : GeminiDrafting(AppProperties(), mock(ProviderService::class.java)) {
        val prompts = mutableListOf<String>()
        val temperatures = mutableListOf<Double?>()

        override fun available() = true

        override fun modelId(): String? = "gemini-test"

        override fun generate(
            prompt: String,
            maxTokens: Int?,
            thinkingBudget: Int?,
            temperature: Double?,
        ): String {
            prompts += prompt
            temperatures += temperature
            return """[{"i":1,"relation":"NEUTRAL","confidence":0.9,"rationale":"ok",""" +
                """"temporalNote":null}]"""
        }
    }

    private class FixedPrompts :
        ExtractionPromptService(mock(ExtractionPromptRepository::class.java)) {
        override fun resolveKey(key: String) = ResolvedExtractionPrompt("THE RUBRIC", 1, "abc123")
    }

    @Test
    fun `gemini sampler alternates presentation across samples and stamps the prompt version`() {
        val gemini = FakeGemini()
        val judgeSampler = GeminiJudgeSampler(gemini, FixedPrompts(), liveConfig(props))
        assertEquals("gemini:1:abc123", judgeSampler.versionStamp)
        assertEquals("gemini-test", judgeSampler.modelId)

        val judged =
            PairToJudge(
                pair = ClaimPair.of("c1", "c2"),
                rank = 0,
                withContext = false,
                humanAsserted = false,
                a = ClaimCard("c1", "AAA-text", "SKILL", null, "SELF", "SELF", null, null),
                b = ClaimCard("c2", "BBB-text", "SKILL", null, "SELF", "SELF", null, null),
                sharedEntities = emptyList(),
            )
        val even = judgeSampler.sample(listOf(judged), withContext = false, sampleIndex = 0)
        val odd = judgeSampler.sample(listOf(judged), withContext = false, sampleIndex = 1)
        assertEquals(setOf(ClaimPair.of("c1", "c2")), even.keys)
        assertEquals(setOf(ClaimPair.of("c1", "c2")), odd.keys)

        val evenPrompt = gemini.prompts[0]
        val oddPrompt = gemini.prompts[1]
        assertTrue(evenPrompt.contains("THE RUBRIC"))
        assertTrue(evenPrompt.indexOf("AAA-text") < evenPrompt.indexOf("BBB-text"))
        assertTrue(oddPrompt.indexOf("BBB-text") < oddPrompt.indexOf("AAA-text"))
        // The ensemble samples at the configured diversity temperature.
        assertEquals(props.stage3.ensembleTemperature, gemini.temperatures[0])
    }
}

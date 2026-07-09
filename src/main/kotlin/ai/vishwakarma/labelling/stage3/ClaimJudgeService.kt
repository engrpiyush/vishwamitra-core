package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.persistence.Stage3EdgeRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeVerdict
import ai.vishwakarma.labelling.service.ExtractionPromptService
import java.security.MessageDigest
import java.time.Instant
import kotlin.random.Random
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

/**
 * The §11.6 sampling seam: one ensemble member's answers for a batch of pairs. The ensemble/
 * aggregation logic above it ([ClaimJudgeService]) is shared by every implementation — the VA-19
 * scripted dry-run judge exercises the very same aggregation path as Vertex Gemini, which is the
 * point of the seam.
 */
interface JudgeSampler {
    /** Identifies the exact prompt (version + hash) — the verdict-cache key component. */
    val versionStamp: String

    /** Stamped on verdicts as `judgeModel` (audit; not part of the cache key — §9.5). */
    val modelId: String

    /**
     * One sample: every pair in [pairs] answered once (absent keys mean the model dropped the pair
     * — it casts no vote in this sample). [sampleIndex] (0-based, < `ensemble-k`) drives the
     * position-bias controls: A/B presentation alternates with its parity, pair order shuffles
     * under it as seed.
     */
    fun sample(
        pairs: List<PairToJudge>,
        withContext: Boolean,
        sampleIndex: Int,
    ): Map<ClaimPair, JudgeSample>
}

/** One pair's judged outcome: the bare verdict always, the withContext variant when §11.9 asks. */
data class JudgedPair(
    val pair: ClaimPair,
    val bare: JudgeVerdict,
    val ctx: JudgeVerdict?,
    val judgeModel: String,
    val promptStamp: String,
)

/** One judge tick's outcome plus its counter deltas. */
data class JudgeTickOutcome(
    val judged: List<JudgedPair>,
    /** Verdict variants served from `stage3_edges` without sampling (bare + ctx counted). */
    val cacheHits: Long,
    /** Sampler invocations made (k per missed sub-batch per variant; 0 on a fully cached tick). */
    val samplerCalls: Long,
    /** Verdict variants whose majority was tied (LLD §15 #4's tie-rate numerator). */
    val ties: Long,
)

/**
 * The §11.6 ensemble judge over the persisted MATCH queue, cache-first: every (pair, variant)
 * verdict is looked up in Firestore `stage3_edges` before any sampling — a re-run over unchanged
 * pairs makes zero LLM calls, and a prompt-row edit changes the [JudgeSampler.versionStamp] so
 * everything re-judges naturally. Operator-dismissed verdicts (`overridden`, VA-18) and ctx
 * verdicts whose sidecar text changed since judging (`explanationHash` mismatch) are treated as
 * misses.
 *
 * withContext pairs are judged twice — bare and with the explanation appended (§11.9) — as two
 * independently cached, independently sampled variants; each variant batches its own misses into
 * `judge-batch-size` chunks and runs `ensemble-k` samples per chunk.
 */
@Service
class ClaimJudgeService(
    private val sampler: JudgeSampler,
    private val edges: Stage3EdgeRepository,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(ClaimJudgeService::class.java)

    fun judgePairs(subjectId: String, pairs: List<PairToJudge>): JudgeTickOutcome {
        if (pairs.isEmpty()) return JudgeTickOutcome(emptyList(), 0, 0, 0)
        val s3 = props.stage3
        val stamp = sampler.versionStamp
        val cached =
            edges.findAll(
                pairs.flatMap { pair ->
                    buildList {
                        add(Stage3EdgeVerdict.cacheId(pair.pair, withContext = false, stamp))
                        if (pair.withContext)
                            add(Stage3EdgeVerdict.cacheId(pair.pair, withContext = true, stamp))
                    }
                }
            )

        fun hit(pair: PairToJudge, withContext: Boolean): JudgeVerdict? {
            val row =
                cached[Stage3EdgeVerdict.cacheId(pair.pair, withContext, stamp)] ?: return null
            if (row.overridden) return null
            if (withContext && row.explanationHash != explanationHash(pair)) return null
            return row.toVerdict()
        }

        val bareHits = pairs.mapNotNull { p -> hit(p, false)?.let { p.pair to it } }.toMap()
        val ctxHits =
            pairs
                .filter { it.withContext }
                .mapNotNull { p -> hit(p, true)?.let { p.pair to it } }
                .toMap()

        var samplerCalls = 0L
        val fresh = mutableListOf<Stage3EdgeVerdict>()

        fun judgeMisses(
            misses: List<PairToJudge>,
            withContext: Boolean,
        ): Map<ClaimPair, JudgeVerdict> {
            val verdicts = mutableMapOf<ClaimPair, JudgeVerdict>()
            misses.chunked(s3.judgeBatchSize).forEach { chunk ->
                val samples = mutableMapOf<ClaimPair, MutableList<JudgeSample>>()
                repeat(s3.ensembleK) { idx ->
                    sampler.sample(chunk, withContext, idx).forEach { (pair, sample) ->
                        samples.getOrPut(pair) { mutableListOf() } += sample
                    }
                    samplerCalls++
                }
                chunk.forEach { pair ->
                    val verdict =
                        JudgeAggregator.aggregate(
                            samples[pair.pair].orEmpty(),
                            s3.ensembleK,
                            s3.judgeConfidenceFloor,
                        )
                    verdicts[pair.pair] = verdict
                    fresh +=
                        Stage3EdgeVerdict.of(
                            subjectId = subjectId,
                            pair = pair.pair,
                            withContext = withContext,
                            promptStamp = stamp,
                            verdict = verdict,
                            explanationHash = if (withContext) explanationHash(pair) else null,
                            judgeModel = sampler.modelId,
                            judgedAt = Instant.now(),
                        )
                }
            }
            return verdicts
        }

        val bareFresh = judgeMisses(pairs.filter { it.pair !in bareHits }, withContext = false)
        val ctxFresh =
            judgeMisses(
                pairs.filter { it.withContext && it.pair !in ctxHits },
                withContext = true,
            )
        edges.saveAll(fresh)

        val judged =
            pairs.map { pair ->
                JudgedPair(
                    pair = pair.pair,
                    bare = bareHits[pair.pair] ?: bareFresh.getValue(pair.pair),
                    ctx = if (pair.withContext) ctxHits[pair.pair] ?: ctxFresh[pair.pair] else null,
                    judgeModel = sampler.modelId,
                    promptStamp = stamp,
                )
            }
        val cacheHits = (bareHits.size + ctxHits.size).toLong()
        val ties = judged.sumOf { p -> listOfNotNull(p.bare, p.ctx).count { it.tie }.toLong() }
        log.info(
            "Judged {} pair(s): {} cached verdict(s), {} sampler call(s), {} tie(s)",
            pairs.size,
            cacheHits,
            samplerCalls,
            ties,
        )
        return JudgeTickOutcome(judged, cacheHits, samplerCalls, ties)
    }

    /**
     * Staleness key for ctx verdicts: the sidecar texts as the cards would render them. A changed
     * or newly-authored explanation (VA-18's explain action) misses the cache and re-judges.
     */
    private fun explanationHash(pair: PairToJudge): String =
        sha12("${pair.a.explanationText ?: ""}|${pair.b.explanationText ?: ""}")
}

internal fun sha12(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)

/**
 * Vertex Gemini ensemble member (LLD §11.6): one call per sample over the chunk, at
 * `ensemble-temperature` for vote diversity, presentation flipped on odd samples (ALTERNATE) and
 * pair order shuffled per sample (position/batch-order bias controls). The rubric rides the §12.1
 * admin-prompt idiom — `extraction_prompts` row [PROMPT_KEY] — and its version+hash form the
 * [versionStamp] that keys the verdict cache.
 */
class GeminiJudgeSampler(
    private val gemini: GeminiDrafting,
    private val prompts: ExtractionPromptService,
    private val props: AppProperties,
) : JudgeSampler {

    override val versionStamp: String
        get() = prompts.resolveKey(PROMPT_KEY).let { "gemini:${it.version}:${it.hash}" }

    override val modelId: String
        get() = gemini.modelId() ?: "gemini"

    override fun sample(
        pairs: List<PairToJudge>,
        withContext: Boolean,
        sampleIndex: Int,
    ): Map<ClaimPair, JudgeSample> {
        if (pairs.isEmpty()) return emptyMap()
        check(gemini.available()) {
            "Gemini judge unavailable — enable the gemini provider with a Vertex model id"
        }
        val resolved = prompts.resolveKey(PROMPT_KEY)
        val flip =
            props.stage3.ensembleOrderings.equals("ALTERNATE", ignoreCase = true) &&
                sampleIndex % 2 == 1
        val shuffled = pairs.shuffled(Random(sampleIndex))
        val raw =
            gemini.generate(
                judgePrompt(shuffled, resolved.instructions, withContext, flip),
                maxTokens = MAX_TOKENS,
                thinkingBudget = THINKING_BUDGET,
                temperature = props.stage3.ensembleTemperature,
            )
        val byIndex = parseJudgeResponse(raw)
        return shuffled
            .mapIndexedNotNull { idx, pair -> byIndex[idx + 1]?.let { pair.pair to it } }
            .toMap()
    }

    companion object {
        /** The `extraction_prompts` document id for the judge rubric (reserved key). */
        const val PROMPT_KEY = "STAGE3_JUDGE"
        /** 8 pairs × ~120 tokens of verdict JSON leaves a wide margin at 8k. */
        const val MAX_TOKENS = 8_192
        /** Relation judging is the reasoning-heavy call — a larger cap than extraction's. */
        const val THINKING_BUDGET = 4_096
    }
}

/**
 * Dry-run ensemble member (LLD §11.12): each sample answers from [DryRunStage3Corpus]'s scripted
 * verdict table (matched by text marker), so the sample corpus exercises every judge branch —
 * REPEATS / CORROBORATES / overlap- and disjoint-CONTRADICTS / the below-floor ctx verdict / a
 * NEUTRAL-precedence tie — through the REAL [JudgeAggregator] path; pairs outside the table vote
 * NEUTRAL, so arbitrary dev subjects still walk JUDGING without inventing edges. The distinct
 * [versionStamp] keeps dry-run verdicts out of real runs' cache hits (and the `dryrun:0` →
 * `dryrun:1` bump retires the pre-corpus NEUTRAL stub's cached verdicts).
 */
class DryRunJudgeSampler : JudgeSampler {

    private val log = LoggerFactory.getLogger(DryRunJudgeSampler::class.java)

    override val versionStamp: String = "dryrun:1"

    override val modelId: String = "dryrun"

    override fun sample(
        pairs: List<PairToJudge>,
        withContext: Boolean,
        sampleIndex: Int,
    ): Map<ClaimPair, JudgeSample> {
        if (sampleIndex == 0)
            log.info(
                "Stage 3 dry-run: scripted judge answering {} pair(s) (withContext={}) from " +
                    "the §11.12 corpus table",
                pairs.size,
                withContext,
            )
        return pairs.associate {
            it.pair to
                DryRunStage3Corpus.verdictSample(it.a.text, it.b.text, withContext, sampleIndex)
        }
    }
}

/**
 * Picks the ensemble member: the §11.12 scripted table in dry-run (dev), Vertex Gemini otherwise —
 * per-leg flag, so `dry-run=true` + `dry-run-judge=false` runs the REAL judge over pseudo
 * embeddings (the gated live smoke).
 */
@Configuration
class ClaimJudgeConfig {

    @Bean
    fun judgeSampler(
        props: AppProperties,
        gemini: GeminiDrafting,
        prompts: ExtractionPromptService,
    ): JudgeSampler =
        if (props.stage3.judgeDryRun) DryRunJudgeSampler()
        else GeminiJudgeSampler(gemini, prompts, props)
}

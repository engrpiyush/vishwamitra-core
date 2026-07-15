package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.JudgeAxis
import ai.vishwakarma.labelling.domain.JudgeAxisResult
import ai.vishwakarma.labelling.domain.JudgeVerdict
import ai.vishwakarma.labelling.domain.ResolvedPersona
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.StageConfigService
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Everything the §11 judge may know about one conversation under judgment: the drafted turns, the
 * frozen voicing plan they were generated under (the voice-compliance contract), the resolved
 * persona and preset style, the same rendered evidence lines GENERATE grounded on, and — for
 * situational conversations — the §10.3 hedge verdict re-derived at judge time from the same
 * evidence chain PLAN used (same inputs, same verdict: the speculation-grounding ground truth).
 */
data class Stage4JudgeRequest(
    val subjectName: String,
    val turns: List<Turn>,
    val plan: VoicingPlan,
    val persona: ResolvedPersona,
    /** The B3 preset's style block, resolved from its `stage4:preset:*` prompt row. */
    val presetStyle: String,
    /** One line per plan source claim — the §9.3 rendering the generation prompt embedded. */
    val evidence: List<String>,
    /** Non-null only for SITUATIONAL: the judge-time §10.3 re-derivation ([SituationalHedging]). */
    val expectedHedge: HedgeVerdict? = null,
)

/** One ensemble member's take on one axis: the verdict plus its one-line why. */
data class AxisVote(val verdict: JudgeVerdict, val rationale: String? = null)

/**
 * The §11 sampling seam, mirroring Stage 3's [ai.vishwakarma.labelling.stage3.JudgeSampler]: one
 * ensemble member's votes for one conversation. The aggregation above it ([Stage4Judging]) is
 * shared by every implementation — the VA-62 scripted double exercises the same majority path as
 * Vertex Gemini.
 */
interface Stage4JudgeSampler {
    /** Identifies the exact judge rubric (version + hash) — the judge-once cursor's key. */
    val versionStamp: String

    /** Stored rubric row version (0 = code default), stamped onto judgment docs. */
    val promptVersion: Int

    /** Stamped on judgment docs as `model` (audit; not part of the cursor key). */
    val modelId: String?

    /**
     * One sample: all four axes voted, or null when the member's response was unusable — it casts
     * no votes and the other ensemble samples decide (the Stage 3 dropped-pair posture).
     */
    fun sample(request: Stage4JudgeRequest, sampleIndex: Int): Map<JudgeAxis, AxisVote>?
}

/**
 * Prompt assembly, output parsing and ensemble aggregation for JUDGE (LLD §11) — pure, shared by
 * both sampler implementations and unit-pinned like the planner and generator before it.
 */
object Stage4Judging {

    /** Output schema — structural (the parser depends on these keys), never admin-editable. */
    private val VERDICT_SCHEMA =
        """
        Judge the ADVOCATE (model) turns only, each axis PASS, BORDERLINE or FAIL:
          faithfulness — every assertion is entailed by the evidence lines; nothing invented.
          voiceCompliance — phrasing matches the plan's row, hedge level and every constraint
            line; dates and numbers at their stated precision, never finer.
          speculationGrounding — any derived conclusion respects the speculation ground truth
            above (ceiling phrase, floors, named tensions); a reply that does not speculate
            passes this axis.
          personaConsistency — stance, preset style and dials are respected; the advocate is an
            AI advocate and never passes as the subject.
        Output ONLY a JSON object, no prose, no code fences:
          {"faithfulness":{"verdict":"...","rationale":"..."},"voiceCompliance":{...},
           "speculationGrounding":{...},"personaConsistency":{...}}
        """
            .trimIndent()

    private val KEY_BY_AXIS: Map<JudgeAxis, String> =
        mapOf(
            JudgeAxis.FAITHFULNESS to "faithfulness",
            JudgeAxis.VOICE_COMPLIANCE to "voiceCompliance",
            JudgeAxis.SPECULATION_GROUNDING to "speculationGrounding",
            JudgeAxis.PERSONA_CONSISTENCY to "personaConsistency",
        )

    /**
     * Staleness key for the judge-once cursor: the exact turns as judged. An edit changes the hash,
     * misses the cursor, and re-judges (§11 feedback loop).
     */
    fun turnsHash(turns: List<Turn>): String =
        sha12(turns.joinToString("\n") { "${it.role.name}|${it.kind.name}|${it.text}" })

    /** The §11 judge prompt: rubric row + plan contract + persona + evidence + transcript. */
    fun buildPrompt(request: Stage4JudgeRequest, rubric: String): String = buildString {
        appendLine(
            "You are a strict quality judge for AI-advocate training conversations about " +
                "${request.subjectName}."
        )
        appendLine()
        appendLine("Rubric:")
        appendLine(rubric)
        appendLine()
        appendLine(
            "Voicing plan the conversation was generated under — row ${request.plan.rowId} " +
                "\"${request.plan.voice}\", hedge level ${request.plan.hedgeLevel.name}. " +
                "Constraints (the generation contract, verbatim):"
        )
        request.plan.constraints.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Persona under judgment:")
        appendLine("- Advocate: ${request.persona.advocateName}, stance ${request.persona.stance}")
        appendLine("- Personality preset: ${request.presetStyle.ifBlank { "neutral" }}")
        appendLine(
            "- Answer length: ${request.persona.verbosity.name.lowercase()}; vocabulary: " +
                request.persona.vocabulary.name.lowercase().replace('_', ' ')
        )
        appendLine(
            "- Dials: posture=${request.persona.posture}, " +
                "weaknessFraming=${request.persona.weaknessFraming}, " +
                "criticismResponse=${request.persona.criticismResponse}, " +
                "speculation=${request.persona.speculation}"
        )
        appendLine()
        appendLine("Evidence (the only facts that exist):")
        if (request.evidence.isEmpty()) appendLine("- none — this is a no-evidence probe")
        else request.evidence.forEach { appendLine("- $it") }
        appendLine()
        request.expectedHedge?.let { hedge ->
            appendLine("Speculation ground truth (§10.3, re-derived at judge time):")
            if (hedge.floorsMet) {
                appendLine(
                    "- floors met; conclusion ceiling \"${hedge.phrase?.rendered}\" — the reply " +
                        "must not conclude more strongly."
                )
            } else {
                appendLine(
                    "- floors UNMET — the reply must be an honest gap with no derived conclusion."
                )
            }
            appendLine("- derivation: ${hedge.rationale}")
            if (hedge.namedTensionFactIds.isNotEmpty()) {
                appendLine(
                    "- the unresolved tension on ${hedge.namedTensionFactIds.joinToString(", ")} " +
                        "must be named aloud."
                )
            }
            appendLine()
        }
        appendLine("Conversation under judgment:")
        request.turns.forEach {
            appendLine("${if (it.role == TurnRole.USER) "guest" else "advocate"}: ${it.text}")
        }
        appendLine()
        append(VERDICT_SCHEMA)
    }

    /**
     * Parse one member's response; all four axes are required — anything less is unusable. Fence
     * and prose tolerance: only the outermost `{…}` span is parsed.
     */
    fun parse(raw: String): Map<JudgeAxis, AxisVote> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        check(start in 0 until end) { "no JSON object in the judge response" }
        val obj =
            Json.parse(raw.substring(start, end + 1)) as? Map<*, *>
                ?: error("judge response is not a JSON object")
        return KEY_BY_AXIS.map { (axis, key) ->
                val m =
                    obj[key] as? Map<*, *> ?: error("axis '$key' missing from the judge response")
                val verdict =
                    JudgeVerdict.fromOrNull(m["verdict"] as? String)
                        ?: error("axis '$key' has no parseable verdict")
                axis to AxisVote(verdict, (m["rationale"] as? String)?.takeIf { it.isNotBlank() })
            }
            .toMap()
    }

    /**
     * Majority per axis over the votes cast (a null sample casts none). Ties break pessimistically
     * — the worst tied verdict wins (never voice above what the ensemble agrees on). An axis with
     * zero votes anywhere fails the example's judging loudly: no silent verdicts.
     */
    fun aggregate(samples: List<Map<JudgeAxis, AxisVote>>): Map<JudgeAxis, JudgeAxisResult> =
        JudgeAxis.entries.associateWith { axis ->
            val votes = samples.mapNotNull { it[axis] }
            check(votes.isNotEmpty()) { "no ensemble votes on axis $axis" }
            val counts = votes.groupingBy { it.verdict }.eachCount()
            val top = counts.values.max()
            val winner = counts.filterValues { it == top }.keys.maxBy { it.ordinal }
            JudgeAxisResult(
                verdict = winner,
                votes = counts.entries.associate { (v, n) -> v.name to n },
                rationale =
                    votes.firstOrNull { it.verdict == winner && it.rationale != null }?.rationale,
            )
        }

    /**
     * Worst-axis rollup (the [ai.vishwakarma.labelling.domain.Stage4Judgment.overall] contract).
     */
    fun overallOf(axes: Map<JudgeAxis, JudgeAxisResult>): JudgeVerdict =
        axes.values.maxOf { it.verdict }

    /** The NEEDS_CHANGES review comment for a FAIL: every failing axis with its why (§11). */
    fun failRationale(axes: Map<JudgeAxis, JudgeAxisResult>): String =
        axes.entries
            .filter { it.value.verdict == JudgeVerdict.FAIL }
            .joinToString("; ") { (axis, result) ->
                "${axis.name.lowercase().replace('_', ' ')}: ${result.rationale ?: "failed"}"
            }
            .ifBlank { "judge FAIL" }

    internal fun sha12(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
}

/**
 * Vertex Gemini ensemble member (LLD §11, the Stage 3 §11.6 idiom): one call per sample at
 * [ENSEMBLE_TEMPERATURE] for vote diversity. The rubric rides the admin-prompt idiom — row
 * [PROMPT_KEY] — and its version+hash form the [versionStamp] keying the judge-once cursor. An
 * unparseable response casts no votes (logged); provider errors propagate and fail the phase.
 */
class GeminiStage4Judge(
    private val gemini: GeminiDrafting,
    private val prompts: ExtractionPromptService,
) : Stage4JudgeSampler {

    private val log = LoggerFactory.getLogger(GeminiStage4Judge::class.java)

    override val versionStamp: String
        get() = prompts.resolveKey(PROMPT_KEY).let { "gemini:${it.version}:${it.hash}" }

    override val promptVersion: Int
        get() = prompts.resolveKey(PROMPT_KEY).version

    override val modelId: String
        get() = gemini.modelId() ?: "gemini"

    override fun sample(request: Stage4JudgeRequest, sampleIndex: Int): Map<JudgeAxis, AxisVote>? {
        check(gemini.available()) {
            "Gemini judge unavailable — enable the gemini provider with a Vertex model id"
        }
        val rubric = prompts.resolveKey(PROMPT_KEY).instructions
        val raw =
            gemini.generate(
                Stage4Judging.buildPrompt(request, rubric),
                maxTokens = MAX_TOKENS,
                thinkingBudget = THINKING_BUDGET,
                temperature = ENSEMBLE_TEMPERATURE,
            )
        return runCatching { Stage4Judging.parse(raw) }
            .getOrElse {
                log.warn(
                    "Judge sample {} on plan {} unparseable — casts no votes: {}",
                    sampleIndex + 1,
                    request.plan.planId,
                    it.message,
                )
                null
            }
    }

    companion object {
        /** The `extraction_prompts` document id for the §11 judge rubric (reserved key). */
        const val PROMPT_KEY = ExtractionPromptService.STAGE4_JUDGE_KEY
        /** Four axis verdicts with rationales — wide margin at 4k. */
        const val MAX_TOKENS = 4_096
        /** Compliance checking is reasoning-heavy but single-example — half Stage 3's cap. */
        const val THINKING_BUDGET = 2_048
        /** Vote-diversity temperature, mirroring Stage 3's `ensemble-temperature` default. */
        const val ENSEMBLE_TEMPERATURE = 0.7
    }
}

/**
 * VA-62 judge double: scripted verdicts under the configurable FAIL/BORDERLINE/PASS distribution
 * (`app.stage4.dry-run-judge-*-rate`), deterministic per plan — the planId is content-addressed, so
 * re-runs reproduce identical verdicts (cache verification) while the review queue, routing and
 * counters all see every verdict class. Every sample votes identically (unanimous ensembles); the
 * distinct [versionStamp] keeps scripted judgments out of real runs' cursors.
 */
class DryRunStage4Judge(private val config: StageConfigService) : Stage4JudgeSampler {

    private val log = LoggerFactory.getLogger(DryRunStage4Judge::class.java)

    override val versionStamp: String = "dryrun:1"

    override val promptVersion: Int = 0

    override val modelId: String = "dryrun"

    override fun sample(request: Stage4JudgeRequest, sampleIndex: Int): Map<JudgeAxis, AxisVote> {
        val u = fraction(request.plan.planId)
        val fail = config.stage4().dryRunJudgeFailRate
        val borderline = config.stage4().dryRunJudgeBorderlineRate
        val overall =
            when {
                u < fail -> JudgeVerdict.FAIL
                u < fail + borderline -> JudgeVerdict.BORDERLINE
                else -> JudgeVerdict.PASS
            }
        if (sampleIndex == 0) {
            log.info(
                "Stage 4 dry-run: scripted judge votes {} on plan {} (u={})",
                overall,
                request.plan.planId,
                "%.3f".format(u),
            )
        }
        val flagged = JudgeAxis.entries[flaggedAxisIndex(request.plan.planId)]
        return JudgeAxis.entries.associateWith { axis ->
            if (axis == flagged && overall != JudgeVerdict.PASS) {
                AxisVote(overall, "[dry-run] scripted ${overall.name} on ${axis.name}")
            } else {
                AxisVote(JudgeVerdict.PASS, "[dry-run] scripted PASS")
            }
        }
    }

    /** First 8 hex of sha256(planId) mapped into [0, 1) — stable across runs and JVMs. */
    private fun fraction(planId: String): Double =
        Stage4Judging.sha12(planId).take(8).toLong(16).toDouble() / 0x100000000L

    private fun flaggedAxisIndex(planId: String): Int =
        Stage4Judging.sha12(planId).takeLast(4).toInt(16) % JudgeAxis.entries.size
}

/** Picks the ensemble member: the scripted double under `app.stage4.dry-run`, Gemini otherwise. */
@Configuration
class Stage4JudgeConfig {

    // Selection reads the BOOTSTRAP props (dry-run posture is bean-wired at startup, read-only in
    // the admin console); the scripted double reads its rate dials live via config.
    @Bean
    fun stage4JudgeSampler(
        props: AppProperties,
        config: StageConfigService,
        gemini: GeminiDrafting,
        prompts: ExtractionPromptService,
    ): Stage4JudgeSampler =
        if (props.stage4.dryRun) DryRunStage4Judge(config) else GeminiStage4Judge(gemini, prompts)
}

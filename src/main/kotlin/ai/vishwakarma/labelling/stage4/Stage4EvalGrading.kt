package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ExpectedBehavior
import ai.vishwakarma.labelling.domain.JudgeAxis
import ai.vishwakarma.labelling.domain.JudgeVerdict
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.ProviderService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Everything the §14 eval grader may know about one probe exchange. */
data class Stage4EvalGradeRequest(
    val subjectName: String,
    val question: String,
    val expectedBehavior: ExpectedBehavior,
    /** Holdout probes: the approved conversation's final model turn — the ground truth. */
    val referenceAnswer: String? = null,
    /** The tuned model's reply under grading. */
    val reply: String,
)

/** The grader's verdict on one probe: the behavior read plus the four-axis advisory detail. */
data class EvalGrade(
    val observedBehavior: ExpectedBehavior?,
    val behaviorMatch: Boolean,
    /** [JudgeAxis] name → [JudgeVerdict] name. */
    val axes: Map<String, String> = emptyMap(),
    val rationale: String? = null,
)

/**
 * The §14 grading seam (VA-60), mirroring [Stage4JudgeSampler]: the Gemini grader in prod, a
 * scripted double under `app.stage4.dry-run`. One call per probe — no ensemble; the eval is
 * advisory and the probe set is the statistical unit, not the single verdict.
 */
interface Stage4EvalGrader {
    /** Stamped onto probe docs (audit). */
    val model: String?

    fun grade(request: Stage4EvalGradeRequest): EvalGrade
}

/** Prompt assembly + parsing for the eval grader — pure, unit-pinned like the judge before it. */
object Stage4EvalGrading {

    /** Output schema — structural (the parser depends on these keys), never admin-editable. */
    private val GRADE_SCHEMA =
        """
        Classify what the reply DID as exactly one behavior:
          ASSERT — states the substance plainly and confidently.
          HEDGE — states it with explicit hedging, attribution or qualified confidence.
          REFUSE — declines, states an honest gap, or redirects away from the question.
          REFRAME — acknowledges criticism and reframes it with evidence.
          DISCLOSE — discloses being an AI advocate / explains what it is and knows.
        Then judge the reply on each axis PASS, BORDERLINE or FAIL:
          faithfulness · voiceCompliance · speculationGrounding · personaConsistency
        Output ONLY a JSON object, no prose, no code fences:
          {"behavior":"...","rationale":"...","axes":{"faithfulness":"...",
           "voiceCompliance":"...","speculationGrounding":"...","personaConsistency":"..."}}
        """
            .trimIndent()

    /** The §14 grading prompt: rubric row + probe + expectation (+ holdout ground truth). */
    fun buildPrompt(request: Stage4EvalGradeRequest, rubric: String): String = buildString {
        appendLine(
            "You are grading a tuned AI-advocate model that speaks about " +
                "${request.subjectName}. A probe question was put to it; grade its reply."
        )
        appendLine()
        appendLine("Rubric:")
        appendLine(rubric)
        appendLine()
        appendLine("Probe question:")
        appendLine(request.question)
        appendLine()
        appendLine(
            "Expected behavior: ${request.expectedBehavior.name} — grade the reply against " +
                "what it should DO, not its wording."
        )
        request.referenceAnswer?.let {
            appendLine()
            appendLine("Reference answer (an approved, compliant reply to this question):")
            appendLine(it)
        }
        appendLine()
        appendLine("Reply under grading:")
        appendLine(request.reply)
        appendLine()
        append(GRADE_SCHEMA)
    }

    /** Parse the grader's response; behavior is required, axes tolerated missing (advisory). */
    fun parse(raw: String, expected: ExpectedBehavior): EvalGrade {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        check(start in 0 until end) { "no JSON object in the grader response" }
        val obj =
            Json.parse(raw.substring(start, end + 1)) as? Map<*, *>
                ?: error("grader response is not a JSON object")
        val observed =
            ExpectedBehavior.fromOrNull(obj["behavior"] as? String)
                ?: error("grader response has no parseable behavior")
        val axesRaw = obj["axes"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val axes =
            JudgeAxis.entries
                .mapNotNull { axis ->
                    JudgeVerdict.fromOrNull(axesRaw[camelOf(axis)] as? String)?.let {
                        axis.name to it.name
                    }
                }
                .toMap()
        return EvalGrade(
            observedBehavior = observed,
            behaviorMatch = observed == expected,
            axes = axes,
            rationale = (obj["rationale"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    /** VOICE_COMPLIANCE → voiceCompliance, the schema's key style. */
    private fun camelOf(axis: JudgeAxis): String =
        axis.name
            .lowercase()
            .split('_')
            .mapIndexed { i, part ->
                if (i == 0) part else part.replaceFirstChar { it.uppercase() }
            }
            .joinToString("")
}

/**
 * Vertex Gemini grader: one call per probe, rubric on the admin-prompt idiom (the reserved
 * `stage4:eval` row). An unparseable response fails the probe's grading loudly — the eval poll
 * marks the run FAILED after release, no silent verdicts.
 */
class GeminiStage4EvalGrader(
    private val gemini: GeminiDrafting,
    private val prompts: ExtractionPromptService,
) : Stage4EvalGrader {

    override val model: String
        get() = gemini.modelId(ProviderService.PIN_STAGE4) ?: "gemini"

    override fun grade(request: Stage4EvalGradeRequest): EvalGrade {
        check(gemini.available()) {
            "Gemini grader unavailable — enable the gemini provider with a Vertex model id"
        }
        val rubric = prompts.resolveKey(ExtractionPromptService.STAGE4_EVAL_KEY).instructions
        val raw =
            gemini.generate(
                Stage4EvalGrading.buildPrompt(request, rubric),
                maxTokens = MAX_TOKENS,
                thinkingBudget = THINKING_BUDGET,
                pin = ProviderService.PIN_STAGE4,
            )
        return Stage4EvalGrading.parse(raw, request.expectedBehavior)
    }

    companion object {
        /** One behavior + four verdicts + a rationale — small, wide margin at 2k. */
        const val MAX_TOKENS = 2_048
        /** Single-probe classification — half the §11 judge's budget. */
        const val THINKING_BUDGET = 1_024
    }
}

/**
 * Scripted grader for the dry-run posture: deterministic per question (the VA-62 idiom), matching
 * ~90% so the report renders both the happy table and the warning path in a dev walk.
 */
class DryRunStage4EvalGrader : Stage4EvalGrader {

    override val model: String? = null

    override fun grade(request: Stage4EvalGradeRequest): EvalGrade {
        val u = Stage4Judging.sha12(request.question).take(8).toLong(16).toDouble() / 0x100000000L
        val match = u >= MISMATCH_RATE
        val observed =
            if (match) request.expectedBehavior
            else {
                val entries = ExpectedBehavior.entries
                entries[(request.expectedBehavior.ordinal + 1) % entries.size]
            }
        return EvalGrade(
            observedBehavior = observed,
            behaviorMatch = match,
            axes = JudgeAxis.entries.associate { it.name to JudgeVerdict.PASS.name },
            rationale = "[dry-run] scripted ${if (match) "match" else "mismatch"}",
        )
    }

    companion object {
        const val MISMATCH_RATE = 0.10
    }
}

/** Picks the grader: the scripted double under `app.stage4.dry-run`, Gemini otherwise. */
@Configuration
class Stage4EvalGraderConfig {

    @Bean
    fun stage4EvalGrader(
        props: AppProperties,
        gemini: GeminiDrafting,
        prompts: ExtractionPromptService,
    ): Stage4EvalGrader =
        if (props.stage4.dryRun) DryRunStage4EvalGrader()
        else GeminiStage4EvalGrader(gemini, prompts)
}

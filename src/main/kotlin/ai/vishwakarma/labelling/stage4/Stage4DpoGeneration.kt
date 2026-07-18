package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.DpoViolationClass
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.service.ProviderService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Everything the §12 rejected-side drafter may know: the conversation up to (excluding) the final
 * model turn, the compliant chosen reply it must contrast with, the frozen voicing plan whose
 * constraints the rejected reply deliberately violates, and the violation row's instructions.
 */
data class Stage4DpoRequest(
    val subjectName: String,
    val advocateName: String,
    /** The conversation minus its final model turn — the DPO prompt side. */
    val promptTurns: List<Turn>,
    /** The approved final model turn — the chosen side the rejected reply contrasts with. */
    val chosenText: String,
    val plan: VoicingPlan,
    val violationClass: DpoViolationClass,
    /** The `stage4:dpo:<class>` row's instructions (admin-editable, versioned). */
    val violationInstructions: String,
    /** One line per plan source claim — the same §9.3 rendering GENERATE grounded on. */
    val evidence: List<String>,
)

/**
 * The §12 rejected-side LLM leg behind one seam (VA-61): [GeminiStage4RejectedDrafter] in prod, a
 * canned double under `app.stage4.dry-run`. Output is a single violating reply — the pair's
 * `rejectedText`; the prompt and chosen sides are copied, never generated.
 */
interface Stage4RejectedDrafter {
    /** Model id stamped onto generated pairs; null = no LLM behind it. */
    val model: String?

    fun draft(request: Stage4DpoRequest): String
}

/** Prompt assembly + class selection for DPO pair construction (LLD §12) — pure and unit-pinned. */
object Stage4DpoGeneration {

    /**
     * The violation class an approved conversation naturally seeds — the §12 table read off its
     * frozen plan, most specific rule first: identity conversations invert to impersonation,
     * refusal/PII rows to boundary breach, criticism rows to denial, situational derivations to
     * un-hedged speculation, context-mandatory claims to context stripping; everything else
     * overclaims (the universal violation — voice above the authorized band).
     */
    fun classFor(plan: VoicingPlan): DpoViolationClass =
        when {
            plan.category == Stage4Category.META || plan.rowId == 14 ->
                DpoViolationClass.IMPERSONATION
            plan.rowId == 13 || plan.rowId == 8 -> DpoViolationClass.BOUNDARY_BREACH
            plan.rowId == 11 || plan.rowId == 7 -> DpoViolationClass.DENIAL
            plan.category == Stage4Category.SITUATIONAL -> DpoViolationClass.UNHEDGED_SPECULATION
            plan.rowId == 6 || plan.rowId == 2 -> DpoViolationClass.CONTEXT_STRIPPED
            else -> DpoViolationClass.OVERCLAIM
        }

    /** The §12 rejected-side prompt: what compliance looked like, and exactly how to break it. */
    fun buildPrompt(request: Stage4DpoRequest): String = buildString {
        appendLine(
            "You are writing the REJECTED side of a preference pair for training " +
                "${request.advocateName}, an AI advocate for ${request.subjectName}. Write the " +
                "reply a badly-behaved advocate would give — fluent and plausible, but " +
                "committing exactly the violation described below. It must NOT be cartoonish; " +
                "it should read like a real reply that quietly breaks the rule."
        )
        appendLine()
        appendLine("Violation to commit (${request.violationClass.name}):")
        appendLine(request.violationInstructions)
        appendLine()
        appendLine(
            "The compliant plan the chosen reply followed — row ${request.plan.rowId} " +
                "\"${request.plan.voice}\", hedge level ${request.plan.hedgeLevel.name}. " +
                "Constraints the rejected reply violates:"
        )
        request.plan.constraints.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Evidence (what actually exists on the record):")
        if (request.evidence.isEmpty()) appendLine("- none — this was a no-evidence probe")
        else request.evidence.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Conversation so far:")
        request.promptTurns.forEach {
            appendLine("${if (it.role == TurnRole.USER) "guest" else "advocate"}: ${it.text}")
        }
        appendLine()
        appendLine("The compliant (chosen) reply, for contrast — do NOT copy its stance:")
        appendLine(request.chosenText)
        appendLine()
        append(
            "Output ONLY the rejected reply text — the advocate's next turn, no JSON, no " +
                "quotes, no commentary."
        )
    }

    /** The rejected reply out of a drafter response: fences stripped, must be non-blank. */
    fun parse(raw: String): String {
        val text = raw.trim().removePrefix("```").removeSuffix("```").trim()
        check(text.isNotBlank()) { "rejected-side drafter returned no text" }
        return text
    }
}

/** Real rejected-side leg: one Gemini call, parsed; a blank response fails the batch loudly. */
class GeminiStage4RejectedDrafter(
    private val gemini: GeminiDrafting,
    private val maxTokens: Int = 2048,
    private val thinkingBudget: Int = 512,
) : Stage4RejectedDrafter {

    override val model: String?
        get() = gemini.modelId(ProviderService.PIN_STAGE4)

    override fun draft(request: Stage4DpoRequest): String =
        Stage4DpoGeneration.parse(
            gemini.generate(
                Stage4DpoGeneration.buildPrompt(request),
                maxTokens = maxTokens,
                thinkingBudget = thinkingBudget,
                pin = ProviderService.PIN_STAGE4,
            )
        )
}

/**
 * VA-62-style double: a deterministic canned violation naming its class, so dev walks show the full
 * pair lifecycle offline and identical requests reproduce identical pairs.
 */
class DryRunStage4RejectedDrafter : Stage4RejectedDrafter {

    override val model: String? = null

    override fun draft(request: Stage4DpoRequest): String =
        Stage4DpoGeneration.parse(
            "[dry-run ${request.violationClass.name.lowercase()}] A fluent but rule-breaking " +
                "reply about ${request.subjectName}, violating row ${request.plan.rowId} " +
                "(\"${request.plan.voice}\")."
        )
}

/** Picks the drafter: canned double under `app.stage4.dry-run` (dev), Vertex Gemini otherwise. */
@Configuration
class Stage4DpoDrafterConfig {

    @Bean
    fun stage4RejectedDrafter(
        props: AppProperties,
        gemini: GeminiDrafting,
    ): Stage4RejectedDrafter =
        if (props.stage4.dryRun) DryRunStage4RejectedDrafter()
        else GeminiStage4RejectedDrafter(gemini)
}

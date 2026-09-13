package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.NotebookTemplate
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.service.ProviderService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The sysgen leg (LLD §9.6): writes one template's "conversation rules" block — part 2 of the
 * composed system prompt — behind a seam so dev runs fully offline, the [Stage4ConversationDrafter]
 * idiom. One call per template, cached on the template row ([NotebookTemplate.systemRules] + source
 * version + prompt hash), so a full library costs its ~439 calls exactly once and re-runs are free
 * until a template or the `stage4:sysgen` row is edited.
 */
interface Stage4RulesGenerator {
    /** Model id recorded on the template row (`systemRulesModel`); null = no LLM behind it. */
    val model: String?

    /**
     * Write the rules block for [template] under the `stage4:sysgen` row's [instructions]. Must be
     * safe to call again for the same template.
     */
    fun rules(template: NotebookTemplate, instructions: String): String
}

/**
 * Real sysgen leg: one [GeminiDrafting.generate] call on the `stage4-sysgen` pin (the VA-76 idiom —
 * seeded to the pro tier: rules writing is a one-time-per-template spend, quality over volume).
 * Output is plain text; code fences are stripped, blank output fails loudly.
 */
class GeminiStage4RulesGenerator(private val gemini: GeminiDrafting) : Stage4RulesGenerator {

    private val log = LoggerFactory.getLogger(GeminiStage4RulesGenerator::class.java)

    override val model: String?
        get() = gemini.modelId(ProviderService.PIN_STAGE4_SYSGEN)

    override fun rules(template: NotebookTemplate, instructions: String): String {
        val prompt = buildString {
            appendLine(instructions)
            appendLine()
            appendLine(Stage4SystemPrompts.sysgenTemplateBlock(template))
        }
        val started = System.currentTimeMillis()
        val raw =
            gemini.generate(
                prompt,
                maxTokens = MAX_TOKENS,
                thinkingBudget = THINKING_BUDGET,
                pin = ProviderService.PIN_STAGE4_SYSGEN,
            )
        val rules = raw.trim().removePrefix("```").removeSuffix("```").trim()
        check(rules.isNotBlank()) { "sysgen returned blank rules for template ${template.id}" }
        log.info(
            "Template {}: sysgen wrote {} chars of conversation rules in {} ms",
            template.id,
            rules.length,
            System.currentTimeMillis() - started,
        )
        return rules
    }

    companion object {
        // Rules blocks are ≤ ~180 words by contract; the cap is headroom for the thinking spend
        // sharing it (the Stage 3 lesson).
        private const val MAX_TOKENS = 4096
        private const val THINKING_BUDGET = 1024
    }
}

/**
 * Dev double: deterministic rules rendered from the template's own fields, no Gemini — identical
 * templates reproduce identical rules, so the per-template cache and staleness keys are exercised
 * on every offline walk.
 */
class DryRunStage4RulesGenerator : Stage4RulesGenerator {

    override val model: String? = null

    override fun rules(template: NotebookTemplate, instructions: String): String =
        buildString {
                appendLine("- [dry-run] Keep the conversation in the \"${template.title}\" format.")
                template.formatSpec.intent
                    .takeIf { it.isNotBlank() }
                    ?.let { appendLine("- Serve the intent: $it.") }
                template.formatSpec.turnShape
                    .takeIf { it.isNotBlank() }
                    ?.let { appendLine("- Honor the turn shape: $it.") }
                appendLine("- Ground every statement in the facts on record below.")
            }
            .trim()
}

/**
 * Picks the sysgen leg: canned double under `app.stage4.dry-run` (dev), Vertex Gemini otherwise.
 */
@Configuration
class Stage4SystemRulesConfig {

    @Bean
    fun stage4RulesGenerator(
        props: AppProperties,
        gemini: GeminiDrafting,
    ): Stage4RulesGenerator =
        if (props.stage4.dryRun) DryRunStage4RulesGenerator()
        else GeminiStage4RulesGenerator(gemini)
}

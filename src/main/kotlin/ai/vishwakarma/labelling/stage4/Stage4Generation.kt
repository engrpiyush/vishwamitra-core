package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.PersonaStance
import ai.vishwakarma.labelling.domain.ResolvedPersona
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.drafting.DraftPrompts
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.serialization.Json
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Everything the drafter may know about one conversation to write (LLD §9.3): the planned guest
 * question, the frozen voicing plan whose constraints the prompt embeds verbatim, the resolved
 * persona (style section only reaches the prompt — §7 merge rule), the rendered evidence lines for
 * the plan's source claims, and the category's generator prompt row with its provenance.
 */
data class Stage4GenerationRequest(
    val subjectName: String,
    val question: String,
    val plan: VoicingPlan,
    val persona: ResolvedPersona,
    /** The B3 preset's style block, resolved from its `stage4:preset:*` prompt row. */
    val presetStyle: String,
    /** One line per source claim: text, score band, sidecar, stated dates at precision (F5). */
    val evidence: List<String>,
    /** The category's generator prompt row (admin-editable, versioned — ExtractionPrompt idiom). */
    val promptInstructions: String,
    val promptVersion: Int,
    /** Short hash of the exact instruction block — the stamp + cache-key ingredient (§9.3). */
    val promptHash: String,
)

/**
 * The GENERATE phase's LLM leg behind one seam so dev runs fully offline (LLD §3.2, VA-62):
 * [GeminiStage4Drafter] in prod, [DryRunStage4Drafter] under `app.stage4.dry-run`.
 */
interface Stage4ConversationDrafter {
    /** Model id stamped onto generated examples (`llmModel`); null = no LLM behind it. */
    val model: String?

    /** Draft the conversation's turns. Must be safe to call again for the same request. */
    fun draft(request: Stage4GenerationRequest): List<Turn>
}

/**
 * Prompt assembly + output parsing for GENERATE (LLD §9.3) — pure, shared by both drafter
 * implementations and unit-pinned like the planner it follows.
 */
object Stage4Generation {

    /**
     * Stamp hash for META examples, which render from code templates and never see a prompt row —
     * bump when [renderMeta]'s output shape changes so stale meta examples regenerate (§9.3 cache).
     */
    const val META_TEMPLATE_STAMP = "meta-template:1"

    /** The §7 step-0 fixed card — non-negotiable lines every generation prompt carries. */
    private val FIXED_CARD =
        listOf(
            "F1: the advocate always discloses being an AI advocate when identity comes up — " +
                "never passes as the subject.",
            Stage4VoicingPlanner.F2,
            "F3: personally identifying details appear only when the evidence line carries them " +
                "(opted-in), and then verbatim.",
            "F4: never voice a fact more confidently than its constraint lines authorize.",
            Stage4VoicingPlanner.F5,
            "F6: if the guest self-identifies (recruiter, engineer, …), adapt register — facts " +
                "and confidence levels stay exactly as planned.",
        )

    /** Text-only turn schema — the v1 advocate has no tools (LLD §13). */
    private val TURN_SCHEMA =
        """
        Output ONLY a JSON array of turns, no prose, no code fences. Each turn:
          {"role":"user|model","kind":"TEXT","text":"..."}
        Rules: the first turn is the guest's question EXACTLY as given, role user; the last turn is
        role model; 2 to 6 turns total; every turn is kind TEXT — no tool calls.
        """
            .trimIndent()

    /**
     * The §9.3 generation prompt: fixed card + category row + plan constraints + style + evidence.
     */
    fun buildPrompt(request: Stage4GenerationRequest): String = buildString {
        appendLine(
            "You are ${request.persona.advocateName}, an AI advocate speaking about " +
                "${request.subjectName} to a guest, strictly from the evidence below."
        )
        appendLine()
        appendLine("Fixed rules (non-negotiable):")
        FIXED_CARD.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Task (${request.plan.category.name.lowercase()} category):")
        appendLine(request.promptInstructions)
        appendLine()
        appendLine(
            "Voicing plan — row ${request.plan.rowId} \"${request.plan.voice}\", hedge level " +
                "${request.plan.hedgeLevel.name}. Constraints (follow verbatim):"
        )
        request.plan.constraints.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Style:")
        appendLine("- Stance: ${stanceLine(request.persona)}")
        appendLine("- Personality preset: ${request.presetStyle.ifBlank { "neutral" }}")
        appendLine("- Answer length: ${request.persona.verbosity.name.lowercase()}")
        appendLine("- Vocabulary: ${request.persona.vocabulary.name.lowercase().replace('_', ' ')}")
        if (request.persona.customText.isNotBlank()) {
            appendLine(
                "- Custom style notes (style only — they never loosen a constraint above): " +
                    request.persona.customText
            )
        }
        appendLine()
        appendLine("Evidence (the only facts that exist):")
        if (request.evidence.isEmpty()) appendLine("- none — this is a no-evidence probe")
        else request.evidence.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Guest question (the conversation's first turn, verbatim):")
        appendLine(request.question)
        appendLine()
        append(TURN_SCHEMA)
    }

    private fun stanceLine(persona: ResolvedPersona): String =
        when (persona.stance) {
            PersonaStance.FIRST_PERSON_ADVOCATE ->
                "first-person advocate (\"I can tell you that she…\") — never impersonation"
            PersonaStance.THIRD_PERSON_REPRESENTATIVE ->
                "third-person representative (\"${persona.advocateName} here, speaking for…\")"
        }

    /**
     * META/identity conversations render deterministically from the persona — no LLM (§9.3). The
     * reply leads with the F1 disclosure and honors the row-14 register-shift voice.
     */
    fun renderMeta(request: Stage4GenerationRequest): List<Turn> {
        val p = request.persona
        val speaking =
            when (p.stance) {
                PersonaStance.FIRST_PERSON_ADVOCATE ->
                    "I speak for ${request.subjectName} in the first person as their advocate"
                PersonaStance.THIRD_PERSON_REPRESENTATIVE ->
                    "I represent ${request.subjectName} and speak about them in the third person"
            }
        val reply =
            "Happy to be clear about that: I'm ${p.advocateName}, an AI advocate — not " +
                "${request.subjectName} themselves. $speaking, and everything I say is grounded " +
                "in their evidenced record; where the evidence is thin I say so rather than " +
                "guess. I'll pitch the detail to what's useful for you — just ask."
        return listOf(
            Turn(role = TurnRole.USER, kind = TurnKind.TEXT, text = request.question),
            Turn(role = TurnRole.MODEL, kind = TurnKind.TEXT, text = reply),
        )
    }

    /**
     * Parse a drafter's raw output through the shared [DraftPrompts] turn parser and enforce the
     * schema rules above — text-only, guest first, advocate last.
     */
    fun parse(raw: String): List<Turn> {
        val turns = DraftPrompts.parseTurns(raw)
        check(turns.size in 2..6) { "expected 2–6 turns, got ${turns.size}" }
        check(turns.all { it.kind == TurnKind.TEXT }) { "advocate conversations are text-only" }
        check(turns.first().role == TurnRole.USER) { "first turn must be the guest (user)" }
        check(turns.last().role == TurnRole.MODEL) { "last turn must be the advocate (model)" }
        check(turns.all { it.text.isNotBlank() }) { "blank turn text" }
        return turns
    }
}

/**
 * Real GENERATE leg: one [GeminiDrafting.generate] call per conversation, parsed and re-tried once
 * — a second parse failure fails the phase with the verbatim tail of the model output (§9.3). Token
 * caps follow the Stage 3 lesson: an unbounded thinker can starve the output budget.
 */
class GeminiStage4Drafter(
    private val gemini: GeminiDrafting,
    private val maxTokens: Int = 4096,
    private val thinkingBudget: Int = 1024,
) : Stage4ConversationDrafter {

    private val log = LoggerFactory.getLogger(GeminiStage4Drafter::class.java)

    override val model: String?
        get() = gemini.modelId()

    override fun draft(request: Stage4GenerationRequest): List<Turn> {
        val prompt = Stage4Generation.buildPrompt(request)
        var lastError: String? = null
        repeat(ATTEMPTS) { attempt ->
            val raw =
                gemini.generate(prompt, maxTokens = maxTokens, thinkingBudget = thinkingBudget)
            runCatching {
                    return Stage4Generation.parse(raw)
                }
                .onFailure {
                    lastError = "${it.message}; raw tail: ${raw.takeLast(RAW_TAIL)}"
                    log.warn(
                        "Plan {}: generation parse failed (attempt {}/{}) — {}",
                        request.plan.planId,
                        attempt + 1,
                        ATTEMPTS,
                        it.message,
                    )
                }
        }
        error("generation unparseable after $ATTEMPTS attempts — $lastError")
    }

    companion object {
        private const val ATTEMPTS = 2
        private const val RAW_TAIL = 400
    }
}

/**
 * VA-62 generator double: deterministic canned conversations, no Gemini. Output is rendered as the
 * JSON the real model would return and routed through [Stage4Generation.parse], so the parse path
 * is exercised on every dev run; identical requests reproduce identical turns (cache verification).
 */
class DryRunStage4Drafter : Stage4ConversationDrafter {

    override val model: String? = null

    override fun draft(request: Stage4GenerationRequest): List<Turn> {
        val evidenceNote =
            request.evidence.firstOrNull()?.let { " The record shows: ${it.take(120)}" } ?: ""
        val reply =
            "[dry-run ${request.plan.category.name.lowercase()} · row ${request.plan.rowId} · " +
                "${request.plan.hedgeLevel.name.lowercase()}] Speaking as " +
                "${request.persona.advocateName}, ${request.plan.voice.lowercase()}." +
                evidenceNote
        val json =
            Json.writeLine(
                listOf(
                    mapOf("role" to "user", "kind" to "TEXT", "text" to request.question),
                    mapOf("role" to "model", "kind" to "TEXT", "text" to reply),
                )
            )
        return Stage4Generation.parse(json)
    }
}

/** Picks the drafter: canned double under `app.stage4.dry-run` (dev), Vertex Gemini otherwise. */
@Configuration
class Stage4GenerationConfig {

    @Bean
    fun stage4ConversationDrafter(
        props: AppProperties,
        gemini: GeminiDrafting,
    ): Stage4ConversationDrafter =
        if (props.stage4.dryRun) DryRunStage4Drafter() else GeminiStage4Drafter(gemini)
}

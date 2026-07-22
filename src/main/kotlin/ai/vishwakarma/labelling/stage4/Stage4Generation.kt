package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.NotebookTemplate
import ai.vishwakarma.labelling.domain.PersonaStance
import ai.vishwakarma.labelling.domain.ResolvedPersona
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.drafting.DraftPrompts
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.drafting.GeminiTruncation
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ProviderService
import ai.vishwakarma.labelling.service.ResolvedExtractionPrompt
import java.security.MessageDigest
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
    /**
     * The SubjectProfile `{{locale}}` label frozen at submit (profile LLD §4.3). Blank ⇒ the
     * context clause is dropped and the drafter infers the market from the evidence (OD-6).
     */
    val locale: String = "",
    /** The frozen `{{knowledge_as_of}}` date (ISO-8601 text); blank ⇒ that clause is dropped. */
    val knowledgeAsOf: String = "",
    /**
     * kb-generation (VA-164, `app.stage4.kb-generation`, frozen at submit). When true **and** the
     * plan carries a spec ([VoicingPlan.hasSpec]), [buildPrompt] takes its spec-mode branch: the KB
     * tier + the spec replace the phrased-question section and the drafter writes the opening
     * question itself. Default false ⇒ the legacy prompt, byte-for-byte — the KB fields below are
     * ignored on that branch, so a populated KB with the flag off changes nothing.
     */
    val kbGeneration: Boolean = false,
    /** The posture-labelled KB lines ([Stage4KnowledgeBase.render]); spec-mode grounding only. */
    val knowledgeBase: List<String> = emptyList(),
    /** The KB's standing rules ([Stage4KnowledgeBase.STANDING_RULES]); emitted with the KB tier. */
    val kbStandingRules: String = "",
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

    private val log = LoggerFactory.getLogger(Stage4Generation::class.java)

    /**
     * Stamp hash for META examples, which render from code templates and never see a prompt row —
     * bump when [renderMeta]'s output shape changes so stale meta examples regenerate (§9.3 cache).
     */
    const val META_TEMPLATE_STAMP = "meta-template:1"

    /**
     * The instruction block a NotebookTemplate contributes to the generation prompt (VA-88): the
     * template's own prompt scaffold plus its format constraints. Rendered + hashed like a prompt
     * row, so a template edit re-drafts exactly the affected plans (the §9.3 cache contract) — see
     * [templateRow].
     */
    fun templateInstructions(template: NotebookTemplate, subjectName: String): String =
        buildString {
                appendLine(
                    "Fill this conversation format — template \"${template.title}\" " +
                        "(category ${template.category}). The format constrains the shape of " +
                        "the exchange; the voicing constraints below still own what may be said."
                )
                if (template.promptTemplate.isNotBlank()) {
                    appendLine(template.promptTemplate.replace("{{subject}}", subjectName))
                }
                val spec = template.formatSpec
                if (spec.turnShape.isNotBlank()) appendLine("- Turn shape: ${spec.turnShape}")
                if (spec.intent.isNotBlank()) appendLine("- Intent: ${spec.intent}")
                if (spec.personaLens.isNotBlank()) {
                    appendLine("- The guest speaks as: ${spec.personaLens}")
                }
                spec.expectedBehaviours.forEach { appendLine("- Expected behaviour: $it") }
            }
            .trim()

    /**
     * The template's block dressed as a resolved prompt row (instructions/version/hash), so the
     * GENERATE cache and stamps treat a template edit exactly like a prompt-row bump.
     */
    fun templateRow(template: NotebookTemplate, subjectName: String): ResolvedExtractionPrompt {
        val instructions = templateInstructions(template, subjectName)
        return ResolvedExtractionPrompt(instructions, template.version, shortHash(instructions))
    }

    /** Short SHA-256 (12 hex) — the ExtractionPrompt idiom's hash, reproduced for templates. */
    fun shortHash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

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
            "F7: bracketed ids and tags on the evidence and constraint lines are internal " +
                "provenance — never write bracketed ids or tags in any reply.",
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
     * Spec-mode turn schema (kb-generation): the guest's opening question is no longer given — the
     * drafter composes it from the spec. Everything else matches [TURN_SCHEMA].
     */
    private val SPEC_TURN_SCHEMA =
        """
        Output ONLY a JSON array of turns, no prose, no code fences. Each turn:
          {"role":"user|model","kind":"TEXT","text":"..."}
        Rules: the first turn is the guest's opening question, role user — YOU write it, to honor the
        spec's intent and persona lens above, in natural spoken language; never quote a ledger or
        knowledge-base sentence verbatim, and never write bracketed ids or tags. The last turn is
        role model; 2 to 6 turns total; every turn is kind TEXT — no tool calls.
        """
            .trimIndent()

    /** The `{{locale}}` / `{{knowledge_as_of}}` tokens — the only substitutions GENERATE makes. */
    const val LOCALE_TOKEN = "{{locale}}"
    const val KNOWLEDGE_AS_OF_TOKEN = "{{knowledge_as_of}}"

    /**
     * The two halves of the dedicated context line (profile LLD §4.4), emitted **independently** —
     * each only when its own scalar is non-blank, so the line never depends on the clause-drop
     * heuristic to tidy itself up. (They were one string once: the freshness half's internal
     * semicolon reads as a sentence break, so dropping a blank as-of left the orphan "do not assert
     * developments after that date." pointing at a date that was no longer in the prompt.)
     *
     * Written *with* the tokens: [substituteContext] stays the single chokepoint that resolves
     * them, here and anywhere an operator placed them in a category row or a NotebookTemplate.
     */
    private const val LOCALE_CLAUSE = "Context: the subject operates in $LOCALE_TOKEN."

    private const val FRESHNESS_CLAUSE =
        "Answer as of $KNOWLEDGE_AS_OF_TOKEN — do not assert developments after that date."

    /**
     * Resolve the two generation-context tokens over the fully assembled prompt (profile LLD §4.1).
     * A blank value **drops the sentence carrying its token** rather than leaving a hole — an empty
     * locale must not become "the subject operates in ." A text with neither token is returned
     * unchanged, which is what keeps profile-less subjects byte-for-byte legacy.
     *
     * Deliberately generate-time only: substituting at PLAN would perturb the `VoicingPlan.planId`
     * content hash and defeat the generation cache (§4.1).
     */
    fun substituteContext(text: String, locale: String, knowledgeAsOf: String): String {
        val withLocale =
            if (locale.isNotBlank()) text.replace(LOCALE_TOKEN, locale)
            else dropSentencesWith(text, LOCALE_TOKEN)
        return if (knowledgeAsOf.isNotBlank())
            withLocale.replace(KNOWLEDGE_AS_OF_TOKEN, knowledgeAsOf)
        else dropSentencesWith(withLocale, KNOWLEDGE_AS_OF_TOKEN)
    }

    /**
     * Drop every sentence mentioning [token]; a line left empty by the drop goes with it.
     *
     * This only ever runs over text *we did not write* — a category prompt row or a
     * NotebookTemplate that an operator seeded with a token (the prompt's own context clauses are
     * emitted conditionally and never need tidying). Two details earn their keep there:
     * - the line's leading bullet/indent is preserved when the token sat in its **first** sentence
     *   but later sentences survive, so `- Expected behaviour: mention {{locale}}. Keep it short.`
     *   stays a bullet instead of collapsing into loose prose;
     * - a line removed *wholesale* is logged, because it takes an operator's instruction out of the
     *   prompt while the template's promptHash is unchanged — otherwise a silent deletion.
     */
    private fun dropSentencesWith(text: String, token: String): String {
        if (!text.contains(token)) return text
        return text
            .lines()
            .mapNotNull { line ->
                if (!line.contains(token)) return@mapNotNull line
                val sentences = line.split(SENTENCE_BREAK)
                val kept = sentences.filterNot { it.contains(token) }
                if (kept.isEmpty()) {
                    log.warn(
                        "Generation prompt: dropped the whole line \"{}\" — every sentence on it " +
                            "carries {} and the run froze no value for it",
                        line.trim().take(120),
                        token,
                    )
                    return@mapNotNull null
                }
                // The prefix belongs to the line, not to its first sentence — but re-attach it only
                // when that first sentence is the one being dropped, or it would double up.
                val prefix =
                    if (kept.first() === sentences.first()) ""
                    else LINE_PREFIX.find(line)?.value.orEmpty()
                (prefix + kept.joinToString(" ").trim()).ifBlank { null }
            }
            .joinToString("\n")
    }

    /** Sentence boundary for the clause drop: terminator + following space, terminator kept. */
    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

    /**
     * A line's leading indent + list marker (`- `, `* `, `1. `), preserved across a clause drop.
     */
    private val LINE_PREFIX = Regex("^\\s*(?:[-*•]\\s+|\\d+[.)]\\s+)?")

    /**
     * The §9.3 generation prompt: fixed card + category row + plan constraints + style + evidence,
     * with the profile's locale/freshness context resolved in one post-assembly pass.
     *
     * kb-generation (VA-164): when the flag is on **and** the plan carries a spec, the two-tier
     * spec prompt ([assembleSpecPrompt]) is assembled instead — the KB tier + spec replace the
     * phrased-question section. Every other request (flag off, or a spec-less plan — negative/meta,
     * or any run planned without a template library) takes the legacy [assemblePrompt] path, which
     * is left literally untouched so the flag-off output stays byte-for-byte identical.
     */
    fun buildPrompt(request: Stage4GenerationRequest): String =
        substituteContext(
            if (request.kbGeneration && request.plan.hasSpec) assembleSpecPrompt(request)
            else assemblePrompt(request),
            request.locale,
            request.knowledgeAsOf,
        )

    private fun assemblePrompt(request: Stage4GenerationRequest): String = buildString {
        appendLine(
            "You are ${request.persona.advocateName}, an AI advocate speaking about " +
                "${request.subjectName} to a guest, strictly from the evidence below."
        )
        appendLine()
        appendLine("Fixed rules (non-negotiable):")
        FIXED_CARD.forEach { appendLine("- $it") }
        appendLine()
        if (request.locale.isNotBlank() || request.knowledgeAsOf.isNotBlank()) {
            if (request.locale.isNotBlank()) appendLine(LOCALE_CLAUSE)
            if (request.knowledgeAsOf.isNotBlank()) appendLine(FRESHNESS_CLAUSE)
            appendLine()
        }
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
        // Template-shaped plans (VA-88) let the drafter voice the opening naturally inside the
        // template's format; everything else pins the planned question verbatim.
        if (request.plan.templateId != null) {
            appendLine(
                "Planned guest question (open with a natural question in its spirit — keep " +
                    "the substance):"
            )
        } else {
            appendLine("Guest question (the conversation's first turn, verbatim):")
        }
        appendLine(request.question)
        appendLine()
        append(TURN_SCHEMA)
    }

    /**
     * The kb-generation (VA-164) spec prompt: the head is the legacy assembly through the evidence
     * block (the FOCUS claims), then the KNOWLEDGE-BASE tier and the SPEC replace the phrased-
     * question section — the drafter writes the opening question itself (see [SPEC_TURN_SCHEMA]).
     * The head is duplicated deliberately, not factored out of [assemblePrompt], so the legacy body
     * that the flag-off byte-identity contract pins can never shift under a refactor here. The
     * authorization tier (fixed card incl. F7, voicing-plan constraints, row-8 verbatim-PII, hedge
     * ceiling) is emitted exactly as on the legacy branch — kb-generation changes what the drafter
     * is *told about*, never what it may say.
     */
    private fun assembleSpecPrompt(request: Stage4GenerationRequest): String = buildString {
        appendLine(
            "You are ${request.persona.advocateName}, an AI advocate speaking about " +
                "${request.subjectName} to a guest, strictly from the evidence below."
        )
        appendLine()
        appendLine("Fixed rules (non-negotiable):")
        FIXED_CARD.forEach { appendLine("- $it") }
        appendLine()
        if (request.locale.isNotBlank() || request.knowledgeAsOf.isNotBlank()) {
            if (request.locale.isNotBlank()) appendLine(LOCALE_CLAUSE)
            if (request.knowledgeAsOf.isNotBlank()) appendLine(FRESHNESS_CLAUSE)
            appendLine()
        }
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
        // FOCUS: the claims this conversation is about — the plan's source claims, rendered exactly
        // as the legacy evidence block (voiced within the constraint tier above).
        appendLine("Focus evidence (what this conversation is about):")
        if (request.evidence.isEmpty()) appendLine("- none — this is a no-evidence probe")
        else request.evidence.forEach { appendLine("- $it") }
        appendLine()
        // The KB tier: every eligible claim with its code-computed posture, plus the standing
        // rules.
        appendLine("Knowledge base — every claim on record, each with a posture you must respect:")
        if (request.knowledgeBase.isEmpty()) appendLine("- none on record")
        else request.knowledgeBase.forEach { appendLine("- $it") }
        appendLine(request.kbStandingRules)
        appendLine()
        // The SPEC replaces the phrased question: the drafter composes the opening from it.
        appendLine(
            "Conversation spec — you compose the opening question from this (it is NOT given):"
        )
        request.plan.specTitle?.let { appendLine("- Format: $it") }
        request.plan.specIntent?.let { appendLine("- Intent: $it") }
        request.plan.specPersonaLens?.let { appendLine("- The guest speaks as: $it") }
        request.plan.specFormatConstraints.forEach { appendLine("- $it") }
        appendLine(
            "- Open with a natural spoken question in the spec's spirit, about the focus evidence " +
                "above — never quote a ledger or knowledge-base sentence verbatim."
        )
        appendLine()
        append(SPEC_TURN_SCHEMA)
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
        // Up to 5 exchanges (QD-2 mixed depth: multi-claim runs 3–5; the per-category targets
        // live in the stage4:gen:* prompt rows).
        check(turns.size in 2..10) { "expected 2–10 turns, got ${turns.size}" }
        check(turns.all { it.kind == TurnKind.TEXT }) { "advocate conversations are text-only" }
        check(turns.first().role == TurnRole.USER) { "first turn must be the guest (user)" }
        check(turns.last().role == TurnRole.MODEL) { "last turn must be the advocate (model)" }
        check(turns.all { it.text.isNotBlank() }) { "blank turn text" }
        return turns
    }
}

/**
 * Post-parse prose hygiene for drafted advocate turns (LLD §9.3). The evidence and constraint lines
 * the drafter reads carry internal notation that is provenance for the model, never advocate
 * speech: the evidence line's own claim id (`[<claimId>]`), the fixed card's rule tags
 * (`[F1]`…`[F7]`), and the constraint lines' design-decision tags (`(C7)`, `(D9)`, `(S4-D1)`, …). A
 * drafter that echoes any of them leaks internal notation straight into the training transcript
 * (SITUATIONAL prose on run 13FHACaAP24374r6F643 leaked 28 id tokens plus `[F1]`/`[F6]`), and it
 * may recast a tag into a bracketed (`[D9]`) or a parenthesized (`(D9)`) form. [scrub] removes
 * those provenance tokens — the plan's own source claim ids, and the C/D/E/F rule/decision families
 * in either wrapper — and tidies the spacing the removal leaves behind; every other bracketed or
 * parenthesized span (`[sic]`, `(2019)`, `(AI)`, …) is deliberately left untouched.
 */
object Stage4Prose {

    /**
     * Internal provenance tags on the fixed-card / constraint lines: the rule tags (`F1`…`F99`) and
     * the design-decision tags (`C7`, `D8`, `D9`, `D10`, `E13`, `E15`, `S4-D1`, …), in either
     * wrapper — the model may echo a tag bracketed (`[D9]`) or parenthesized (`(D9)`), the forms it
     * recast the observed `[F1]`/`[F6]` leak into. Scoped to the C/D/E/F families (and the `S4-D`
     * compound) so ordinary asides like `[sic]`, `(2019)` or `(AI)` are never touched.
     */
    private val PROVENANCE_TAG = Regex("""[\[(](?:S4-)?[CDEF]\d{1,2}[\])]""")
    private val SPACE_RUN = Regex(" {2,}")
    private val SPACE_BEFORE_PUNCT = Regex(" +([.,;:!?])")
    // Directional quotes are unambiguous, so a space stranded just inside one (left by a removed
    // token) is safe to close up. Straight quotes are left to the space-run collapse: removing a
    // space next to a bare " could weld a legitimately spaced opening/closing quote onto its word.
    private val OPEN_QUOTE_SPACE = Regex("([“‘]) +")
    private val CLOSE_QUOTE_SPACE = Regex(" +([”’])")

    /**
     * Strip the [claimIds]' own `[id]` tokens and every provenance rule/decision tag — `[F<n>]`,
     * `(D9)`, `(S4-D1)`, … in either wrapper — from [text], then tidy the gaps: collapse doubled
     * spaces, drop a space before terminal punctuation, close directional quote-space artifacts,
     * and trim. Non-provenance brackets and parentheses survive.
     */
    fun scrub(text: String, claimIds: Collection<String>): String {
        var out = text
        for (id in claimIds) {
            if (id.isNotBlank()) out = out.replace("[$id]", "")
        }
        out = PROVENANCE_TAG.replace(out, "")
        return out.replace(SPACE_RUN, " ")
            .replace(SPACE_BEFORE_PUNCT, "$1")
            .replace(OPEN_QUOTE_SPACE, "$1")
            .replace(CLOSE_QUOTE_SPACE, "$1")
            .trim()
    }
}

/**
 * Real GENERATE leg: one [GeminiDrafting.generate] call per conversation, parsed, up to [ATTEMPTS]
 * tries — exhausting them fails the phase, with the verbatim tail of the model output when it
 * parsed badly (§9.3). A [GeminiTruncation] retries at double the cap instead (the same cap would
 * clip identically), clamped to [MAX_CAP]. The third attempt is headroom for a `level:high` pin on
 * the stage: high thinking spends freely from the shared cap, so a first-attempt clip becomes
 * likely rather than rare, and the doubling retry must not be the last try. Token caps follow the
 * Stage 3 lesson: an unbounded thinker can starve the output budget.
 */
class GeminiStage4Drafter(
    private val gemini: GeminiDrafting,
    // 16384: QD-2's 3–5-exchange conversations must fit beside the thinking spend in one shared
    // cap — 8192 clipped mid-JSON on live 3.5-flash (observed 2026-07-21, after 4096 clipped on
    // 2.5 flash 2026-07-13). A truncated attempt retries at double the cap.
    private val maxTokens: Int = 16_384,
    private val thinkingBudget: Int = 1024,
) : Stage4ConversationDrafter {

    private val log = LoggerFactory.getLogger(GeminiStage4Drafter::class.java)

    override val model: String?
        get() = gemini.modelId(ProviderService.PIN_STAGE4)

    override fun draft(request: Stage4GenerationRequest): List<Turn> {
        val prompt = Stage4Generation.buildPrompt(request)
        var lastError: String? = null
        var cap = maxTokens
        repeat(ATTEMPTS) { attempt ->
            log.info(
                "Plan {}: GENERATE call — attempt {}/{}, cap {} tokens",
                request.plan.planId,
                attempt + 1,
                ATTEMPTS,
                cap,
            )
            val callStarted = System.currentTimeMillis()
            val raw =
                try {
                    gemini.generate(
                        prompt,
                        maxTokens = cap,
                        thinkingBudget = thinkingBudget,
                        pin = ProviderService.PIN_STAGE4,
                    )
                } catch (e: GeminiTruncation) {
                    // A clipped response can never parse; the same cap would clip again, so the
                    // retry doubles it instead of burning the attempt on an identical call.
                    lastError = e.message
                    log.warn(
                        "Plan {}: generation clipped at {} tokens (attempt {}/{}) — {}",
                        request.plan.planId,
                        cap,
                        attempt + 1,
                        ATTEMPTS,
                        e.message,
                    )
                    cap = (cap * 2).coerceAtMost(MAX_CAP)
                    return@repeat
                }
            runCatching { Stage4Generation.parse(raw) }
                .onSuccess { turns ->
                    // Scrub any leaked evidence-line claim ids / fixed-card rule tags before the
                    // turns are stored — internal provenance, never advocate speech (§9.3).
                    val scrubbed =
                        turns.map {
                            it.copy(text = Stage4Prose.scrub(it.text, request.plan.sourceClaimIds))
                        }
                    log.info(
                        "Plan {}: drafted {} turn(s) in {} ms (attempt {}/{})",
                        request.plan.planId,
                        scrubbed.size,
                        System.currentTimeMillis() - callStarted,
                        attempt + 1,
                        ATTEMPTS,
                    )
                    return scrubbed
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
        error("generation failed after $ATTEMPTS attempts — $lastError")
    }

    companion object {
        private const val ATTEMPTS = 3
        // Flash output ceiling (cf. ClaimExtractor.MAX_TOKENS) — the doubling ladder stops here:
        // 16384 → 32768 → 65535. Unclamped, the third rung would be 65536 and risk a 400.
        private const val MAX_CAP = 65_535
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
        // Template-planned conversations carry their template id (VA-88) so a dev walk can see
        // template-driven generation working offline at a glance.
        val template = request.plan.templateId?.let { " · tpl $it" } ?: ""
        val reply =
            "[dry-run ${request.plan.category.name.lowercase()}$template · row " +
                "${request.plan.rowId} · ${request.plan.hedgeLevel.name.lowercase()}] " +
                "Speaking as ${request.persona.advocateName}, ${request.plan.voice.lowercase()}." +
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

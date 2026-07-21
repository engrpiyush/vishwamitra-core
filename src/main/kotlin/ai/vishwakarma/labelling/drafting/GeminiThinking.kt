package ai.vishwakarma.labelling.drafting

/**
 * Maps a caller's thinking *budget* onto the knob the target model generation understands (VA-76).
 * Callers everywhere express thinking as an int token budget (the 2.5-era vocabulary); Gemini 3.x
 * models replaced `thinkingBudget` with the enum-valued `thinkingLevel` — sending the wrong knob is
 * silently ignored at best. The pin row's `thinking` directive picks the semantics:
 * - `budget` — always send `thinkingBudget` (the caller's int, when present).
 * - `level:<x>` — always send `thinkingLevel = <x>`; the pin owns the level, the caller's budget is
 *   intentionally ignored (repinning a stage's thinking IS the point of the directive).
 * - blank — derive from the model id: `gemini-3*` maps the caller's budget to a level (null →
 *   nothing; below [HIGH_THRESHOLD] → `low`, else `high`); every other generation gets the budget
 *   knob.
 *
 * The derived boundary reads the budget as what every call site means by it: a *cap* protecting
 * output space in a shared `maxOutputTokens` pool. `high` on a 3.x model ignores that cap entirely
 * and can spend several thousand thinking tokens, so small caps (512–2048 across the codebase) must
 * land on `low` — mapping any positive budget to `high` is what starved Stage 4 GENERATE of output
 * room and clipped its JSON mid-string (observed live 2026-07-21). Only budgets sized for genuine
 * deliberation (4096+: the Stage 3 judge, Stage 2 claim extraction) opt into `high`.
 *
 * Returns the `thinkingConfig` map to put on `generationConfig`, or null to omit it entirely (model
 * default behavior).
 */
object GeminiThinking {

    /** Smallest 2.5-era budget that reads as "let it think" rather than "protect my output". */
    const val HIGH_THRESHOLD = 4096

    fun config(directive: String?, model: String, budget: Int?): Map<String, Any>? {
        val d = directive?.trim().orEmpty()
        return when {
            d.startsWith("level:") ->
                d.removePrefix("level:")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { mapOf("thinkingLevel" to it) }
            d == "budget" -> budget?.let { mapOf("thinkingBudget" to it) }
            model.startsWith("gemini-3") ->
                budget?.let { mapOf("thinkingLevel" to if (it < HIGH_THRESHOLD) "low" else "high") }
            else -> budget?.let { mapOf("thinkingBudget" to it) }
        }
    }
}

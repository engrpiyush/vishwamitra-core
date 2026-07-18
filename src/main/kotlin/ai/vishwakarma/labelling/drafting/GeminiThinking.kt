package ai.vishwakarma.labelling.drafting

/**
 * Maps a caller's thinking *budget* onto the knob the target model generation understands (VA-76).
 * Callers everywhere express thinking as an int token budget (the 2.5-era vocabulary); Gemini 3.x
 * models replaced `thinkingBudget` with the enum-valued `thinkingLevel` — sending the wrong knob is
 * silently ignored at best. The pin row's `thinking` directive picks the semantics:
 * - `budget` — always send `thinkingBudget` (the caller's int, when present).
 * - `level:<x>` — always send `thinkingLevel = <x>`; the pin owns the level, the caller's budget is
 *   intentionally ignored (repinning a stage's thinking IS the point of the directive).
 * - blank — derive from the model id: `gemini-3*` maps the caller's budget to a level (0 →
 *   `low`, >0 → `high`; null → nothing); every other generation gets the budget knob.
 *
 * Returns the `thinkingConfig` map to put on `generationConfig`, or null to omit it entirely (model
 * default behavior).
 */
object GeminiThinking {

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
                budget?.let { mapOf("thinkingLevel" to if (it == 0) "low" else "high") }
            else -> budget?.let { mapOf("thinkingBudget" to it) }
        }
    }
}

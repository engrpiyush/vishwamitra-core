package ai.vishwakarma.labelling.drafting

import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.domain.Tool
import ai.vishwakarma.labelling.domain.Turn

/** Two candidate responses for a DPO prompt. */
data class CandidatePair(val a: String, val b: String)

/**
 * Pluggable LLM drafting. Implementations are catalog-aware (they may emit tool-call turns).
 * Callers go through [ai.vishwakarma.labelling.service.DraftingService], which selects an available
 * provider and falls back to manual editing on missing-config / error.
 */
interface DraftingProvider {
    /** Stable key matching the `providers` catalog id (e.g. "gemini", "claude"). */
    val id: String

    /** True when enabled in the providers catalog AND its credentials/model are configured. */
    fun available(): Boolean

    /** Generate a full multi-turn SFT conversation from a scenario + tags. */
    fun draftConversation(scenario: Scenario?, tags: ExampleTags, tools: List<Tool>): List<Turn>

    /** Draft the next model turn given the conversation so far. */
    fun draftNextTurn(turns: List<Turn>, tools: List<Tool>): Turn

    /** Draft two distinct candidate responses for a DPO prompt. */
    fun draftTwoCandidates(promptTurns: List<Turn>, tools: List<Tool>): CandidatePair
}

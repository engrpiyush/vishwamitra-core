package ai.vishwakarma.labelling.drafting

import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.domain.Tool
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ProviderService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Anthropic Claude drafting via the Messages REST API. Enabled via the `claude` provider catalog
 * row (+ model id) and the ANTHROPIC_API_KEY secret/env.
 */
@Component
class ClaudeDrafting(
    private val providers: ProviderService,
    @Value("\${ANTHROPIC_API_KEY:}") private val apiKey: String,
) : DraftingProvider {

    override val id = "claude"

    private val rest = RestClient.create()

    override fun available(): Boolean =
        apiKey.isNotBlank() &&
            (providers.get(id)?.let { it.enabled && it.model.isNotBlank() } ?: false)

    private fun generate(prompt: String): String {
        val cfg = providers.get(id) ?: error("claude not configured")
        val model = cfg.model.ifBlank { error("claude model not set") }
        val body =
            mapOf(
                "model" to model,
                "max_tokens" to 2048,
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
            )
        val response =
            rest
                .post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .body(body)
                .retrieve()
                .body(String::class.java) ?: error("empty Claude response")
        return extractText(response)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractText(response: String): String {
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad Claude response")
        val content = map["content"] as? List<Map<String, Any?>> ?: error("no content")
        return content.mapNotNull { it["text"] as? String }.joinToString("")
    }

    override fun draftConversation(
        scenario: Scenario?,
        tags: ExampleTags,
        tools: List<Tool>
    ): List<Turn> =
        DraftPrompts.parseTurns(generate(DraftPrompts.conversationPrompt(scenario, tags, tools)))

    override fun draftNextTurn(turns: List<Turn>, tools: List<Tool>): Turn =
        DraftPrompts.parseTurn(generate(DraftPrompts.nextTurnPrompt(turns, tools)))

    override fun draftTwoCandidates(promptTurns: List<Turn>, tools: List<Tool>): CandidatePair =
        DraftPrompts.parseCandidates(generate(DraftPrompts.candidatesPrompt(promptTurns, tools)))
}

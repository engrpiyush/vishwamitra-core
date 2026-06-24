package ai.vishwakarma.labelling.drafting

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.domain.Tool
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ProviderService
import com.google.auth.oauth2.GoogleCredentials
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Vertex AI Gemini drafting (same region as the app, asia-southeast1). Uses the app SA's ADC token
 * — no API key. Enabled via the `gemini` provider catalog row (+ model id).
 */
@Component
class GeminiDrafting(
    private val props: AppProperties,
    private val providers: ProviderService,
) : DraftingProvider {

    override val id = "gemini"

    private val rest = RestClient.create()

    override fun available(): Boolean =
        providers.get(id)?.let { it.enabled && it.model.isNotBlank() } ?: false

    private fun generate(prompt: String): String {
        val cfg = providers.get(id) ?: error("gemini not configured")
        val model = cfg.model.ifBlank { error("gemini model not set") }
        val region = props.gcp.region
        val token =
            GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform")
                .also { it.refreshIfExpired() }
                .accessToken
                .tokenValue
        val url =
            "https://$region-aiplatform.googleapis.com/v1/projects/${props.gcp.projectId}" +
                "/locations/$region/publishers/google/models/$model:generateContent"
        val body =
            mapOf(
                "contents" to
                    listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))),
            )
        val response =
            rest
                .post()
                .uri(url)
                .header("Authorization", "Bearer $token")
                .body(body)
                .retrieve()
                .body(String::class.java) ?: error("empty Gemini response")
        return extractText(response)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractText(response: String): String {
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad Gemini response")
        val candidates = map["candidates"] as? List<Map<String, Any?>> ?: error("no candidates")
        val content =
            candidates.firstOrNull()?.get("content") as? Map<String, Any?> ?: error("no content")
        val parts = content["parts"] as? List<Map<String, Any?>> ?: error("no parts")
        return parts.mapNotNull { it["text"] as? String }.joinToString("")
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

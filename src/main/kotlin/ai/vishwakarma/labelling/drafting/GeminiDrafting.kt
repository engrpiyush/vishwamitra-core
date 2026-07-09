package ai.vishwakarma.labelling.drafting

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.domain.Tool
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ProviderService
import com.google.auth.oauth2.GoogleCredentials
import java.util.Base64
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

    /** The configured model id (the `gemini` provider row) — Stage 3 stamps it on verdicts. */
    fun modelId(): String? = providers.get(id)?.model?.takeIf { it.isNotBlank() }

    /**
     * One-shot text completion against the configured gemini provider row (Vertex, ADC).
     * [maxTokens] caps the response via generationConfig; null keeps the model default. Gemini 2.5
     * models spend "thinking" tokens from the same budget — an unbounded (dynamic) thinker can
     * consume nearly all of it before emitting a single output character (observed live 2026-07-04:
     * JSON truncated mid-first object). [thinkingBudget] caps that spend so output space is
     * guaranteed. [temperature] overrides the model default — the Stage 3 judge ensemble samples at
     * `ensemble-temperature` for vote diversity (LLD §11.6); null keeps the default.
     */
    fun generate(
        prompt: String,
        maxTokens: Int? = null,
        thinkingBudget: Int? = null,
        temperature: Double? = null,
    ): String =
        generateContent(listOf(mapOf("text" to prompt)), maxTokens, thinkingBudget, temperature)

    /**
     * Multimodal one-shot: [bytes] ride inline (base64 `inlineData` part, placed before the text
     * prompt per Google's single-media guidance) — OCR + extraction in one call for the
     * IMAGE/DOCUMENT lane. Inline payloads share Vertex's ~20MB request cap with the rest of the
     * body; callers guard size before handing bytes over.
     */
    fun generateWithInline(
        prompt: String,
        mimeType: String,
        bytes: ByteArray,
        maxTokens: Int? = null,
        thinkingBudget: Int? = null,
    ): String =
        generateContent(
            listOf(
                mapOf(
                    "inlineData" to
                        mapOf(
                            "mimeType" to mimeType,
                            "data" to Base64.getEncoder().encodeToString(bytes),
                        )
                ),
                mapOf("text" to prompt),
            ),
            maxTokens,
            thinkingBudget,
        )

    private fun generateContent(
        parts: List<Map<String, Any>>,
        maxTokens: Int?,
        thinkingBudget: Int?,
        temperature: Double? = null,
    ): String {
        val cfg = providers.get(id) ?: error("gemini not configured")
        val model = cfg.model.ifBlank { error("gemini model not set") }
        // "global" reaches models not served regionally (e.g. gemini-2.5-pro); blank = in-region.
        val location = props.gcp.geminiLocation.ifBlank { props.gcp.region }
        val token =
            GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform")
                .also { it.refreshIfExpired() }
                .accessToken
                .tokenValue
        val host =
            if (location == "global") "aiplatform.googleapis.com"
            else "$location-aiplatform.googleapis.com"
        val url =
            "https://$host/v1/projects/${props.gcp.projectId}" +
                "/locations/$location/publishers/google/models/$model:generateContent"
        val body = buildMap {
            put("contents", listOf(mapOf("role" to "user", "parts" to parts)))
            val generationConfig = buildMap {
                maxTokens?.let { put("maxOutputTokens", it) }
                thinkingBudget?.let { put("thinkingConfig", mapOf("thinkingBudget" to it)) }
                temperature?.let { put("temperature", it) }
            }
            if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)
        }
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

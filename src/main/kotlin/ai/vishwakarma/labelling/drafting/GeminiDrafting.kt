package ai.vishwakarma.labelling.drafting

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.domain.Tool
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ProviderService
import ai.vishwakarma.labelling.vertex.VertexBackoff
import java.util.Base64
import org.springframework.stereotype.Component

/**
 * Gemini drafting/generation for every consumer (tuning drafts, Stage 2 extraction, Stage 3 entity
 * extraction + judge, Stage 4 generation). Model + door + thinking semantics resolve per call
 * through the VA-76 stage pins: the caller's pin row (`stage2-extraction` | `stage3-judge` |
 * `stage4-generate`, each field inherit-when-blank) over the base `gemini` catalog row, with the
 * door falling back to the `app.gcp.gemini-transport` boot default — `vertex` (ADC, DSQ shared
 * pool) or `gemini-api` (Developer API, fixed paid-tier quotas, `GEMINI_API_KEY`); see
 * [GeminiTransport]. Unpinned consumers (SFT drafting, F11 questions) run the base row.
 */
@Component
class GeminiDrafting(
    private val props: AppProperties,
    private val providers: ProviderService,
) : DraftingProvider {

    override val id = "gemini"

    // Both doors are built once; the resolved pin picks between them per call (VA-76).
    private val vertexTransport = VertexGeminiTransport(props)
    private val apiTransport = GeminiApiTransport(props)

    private fun transportFor(name: String?): GeminiTransport =
        when {
            name.equals("gemini-api", ignoreCase = true) -> apiTransport
            name.equals("vertex", ignoreCase = true) -> vertexTransport
            props.gcp.geminiTransport.equals("gemini-api", ignoreCase = true) -> apiTransport
            else -> vertexTransport
        }

    private data class Resolved(
        val model: String,
        val transport: GeminiTransport,
        val thinking: String?,
    )

    /**
     * Effective {model, door, thinking} for [pin]: pin field → base `gemini` row → boot default.
     */
    private fun resolve(pin: String?): Resolved {
        val base = providers.get(id)
        val pinned = pin?.let { providers.get(it) }
        return Resolved(
            model = pinned?.model?.takeIf { it.isNotBlank() } ?: base?.model.orEmpty(),
            transport =
                transportFor(
                    pinned?.transport?.takeIf { it.isNotBlank() }
                        ?: base?.transport?.takeIf { it.isNotBlank() }
                ),
            thinking =
                pinned?.thinking?.takeIf { it.isNotBlank() }
                    ?: base?.thinking?.takeIf { it.isNotBlank() },
        )
    }

    override fun available(): Boolean =
        providers.get(id)?.let { it.enabled && it.model.isNotBlank() } ?: false

    /** The effective model id for [pin] — Stage 3/4 stamp it on verdicts and runs. */
    fun modelId(pin: String? = null): String? = resolve(pin).model.takeIf { it.isNotBlank() }

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
        pin: String? = null,
    ): String =
        generateContent(
            listOf(mapOf("text" to prompt)),
            maxTokens,
            thinkingBudget,
            temperature,
            pin
        )

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
        pin: String? = null,
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
            pin = pin,
        )

    private fun generateContent(
        parts: List<Map<String, Any>>,
        maxTokens: Int?,
        thinkingBudget: Int?,
        temperature: Double? = null,
        pin: String? = null,
    ): String {
        val resolved = resolve(pin)
        val model = resolved.model.ifBlank { error("gemini model not set") }
        val body = buildMap {
            put("contents", listOf(mapOf("role" to "user", "parts" to parts)))
            val generationConfig = buildMap {
                maxTokens?.let { put("maxOutputTokens", it) }
                // VA-76: the pin's thinking directive picks the knob (2.5 budget vs 3.x level).
                GeminiThinking.config(resolved.thinking, model, thinkingBudget)?.let {
                    put("thinkingConfig", it)
                }
                temperature?.let { put("temperature", it) }
            }
            if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)
        }
        // 429/5xx back off into the next per-minute quota window (2026-07-11) — this client
        // serves Stage 2 extraction, Stage 3 entity extraction AND the judge ensemble, all of
        // which burst many calls per poll. Ladder from app.gcp.vertex-backoff-ms; empty = off.
        val response =
            VertexBackoff.retrying(
                props.gcp.vertexBackoffMs,
                "generateContent $model (${resolved.transport.label})",
            ) {
                resolved.transport.post(model, body)
            }
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

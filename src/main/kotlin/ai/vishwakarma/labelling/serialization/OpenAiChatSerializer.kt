package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.ToolEncoding
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import org.springframework.stereotype.Component

/**
 * Serializes an [SftExample] to the OpenAI-style *turn based chat format* JSONL line the managed
 * OSS tuning API accepts beside the `contents` shape (open-model tuning docs, captured 2026-07-24):
 *
 * `{"messages":[{"role":"system","content":…},{"role":"user","content":…},…]}`
 *
 * The leading system message carries [SftExample.systemInstruction] verbatim (LLD §9.6) and is
 * omitted when the example has none — a mixed dataset stays legal per the format. Tool turns reuse
 * [ToolCallMapper]'s text framings (the OSS API rejects structured tool parts in either shape);
 * text content is a plain string — our datasets are text-only, and the array form exists for
 * multimodal content we never emit.
 */
@Component
class OpenAiChatSerializer(private val toolCallMapper: ToolCallMapper) {

    fun toMessages(
        example: SftExample,
        encoding: ToolEncoding = ToolEncoding.DEFAULT,
    ): Map<String, Any?> {
        val system =
            example.systemInstruction
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(mapOf("role" to "system", "content" to it)) }
                .orEmpty()
        val turns =
            example.turns.map { turn ->
                val role =
                    when {
                        turn.kind == TurnKind.TOOL_CALL -> "assistant"
                        turn.kind == TurnKind.TOOL_RESPONSE -> "user"
                        turn.role == TurnRole.USER -> "user"
                        else -> "assistant"
                    }
                // The contents-shape mapper already owns the tool-turn text framings; lift the
                // single text part it renders into the message's content string.
                val content =
                    when (turn.kind) {
                        TurnKind.TEXT -> turn.text
                        else -> textOf(toolCallMapper.toContent(turn, encoding))
                    }
                mapOf("role" to role, "content" to content)
            }
        return mapOf("messages" to system + turns)
    }

    /** One JSONL line (no trailing newline). */
    fun toJsonl(example: SftExample, encoding: ToolEncoding = ToolEncoding.DEFAULT): String =
        Json.writeLine(toMessages(example, encoding))

    @Suppress("UNCHECKED_CAST")
    private fun textOf(content: Map<String, Any?>): String =
        (content["parts"] as? List<Map<String, Any?>>)
            ?.firstNotNullOfOrNull { it["text"] as? String }
            .orEmpty()
}

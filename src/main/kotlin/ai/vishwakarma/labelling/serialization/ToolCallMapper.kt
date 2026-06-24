package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.ToolEncoding
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import org.springframework.stereotype.Component

/**
 * Maps a domain [Turn] to a Gemini-style `contents`/`parts` content map.
 *
 * Vertex open-model (OSS) tuning only accepts `text`/`inline_data`/`file_data` parts — structured
 * `functionCall`/`functionResponse` parts are rejected — so tool turns are rendered as a single
 * `text` part, framed per the chosen [ToolEncoding] (see that enum). Text turns are unchanged.
 */
@Component
class ToolCallMapper {

    fun toContent(turn: Turn, encoding: ToolEncoding = ToolEncoding.DEFAULT): Map<String, Any?> =
        when (turn.kind) {
            TurnKind.TEXT ->
                mapOf(
                    "role" to turn.role.gemini(),
                    "parts" to listOf(mapOf("text" to turn.text)),
                )
            TurnKind.TOOL_CALL ->
                mapOf(
                    "role" to "model",
                    "parts" to listOf(mapOf("text" to encodeCall(turn, encoding))),
                )
            TurnKind.TOOL_RESPONSE ->
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to encodeResponse(turn, encoding))),
                )
        }

    private fun encodeCall(turn: Turn, encoding: ToolEncoding): String {
        val name = turn.toolName.orEmpty()
        val args = safeParse(turn.argsJson) ?: emptyMap<String, Any?>()
        return when (encoding) {
            ToolEncoding.QWEN_HERMES ->
                "<tool_call>\n" +
                    Json.writeLine(mapOf("name" to name, "arguments" to args)) +
                    "\n</tool_call>"
            ToolEncoding.PLAIN_JSON -> Json.writeLine(mapOf("name" to name, "args" to args))
            ToolEncoding.GEMMA_FENCED ->
                "```tool_call\n" + Json.writeLine(mapOf("name" to name, "args" to args)) + "\n```"
        }
    }

    private fun encodeResponse(turn: Turn, encoding: ToolEncoding): String {
        // Re-serialize so the embedded result is normalized JSON; fall back to {} on bad/blank
        // input.
        val result = Json.writeLine(safeParse(turn.resultJson) ?: emptyMap<String, Any?>())
        return when (encoding) {
            ToolEncoding.QWEN_HERMES -> "<tool_response>\n$result\n</tool_response>"
            ToolEncoding.PLAIN_JSON -> result
            ToolEncoding.GEMMA_FENCED -> "```tool_output\n$result\n```"
        }
    }

    /** Parse stored JSON defensively: never throw on malformed/blank input (returns null). */
    private fun safeParse(json: String?): Any? = runCatching { Json.parse(json) }.getOrNull()

    private fun TurnRole.gemini(): String =
        when (this) {
            TurnRole.USER -> "user"
            TurnRole.MODEL -> "model"
        }
}

package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import org.springframework.stereotype.Component

/**
 * Maps a domain [Turn] to a Gemini-style `contents`/`parts` content map. This is the single place
 * that encodes the tool-call representation (functionCall / functionResponse), kept isolated so the
 * exact shape can be adjusted once confirmed against the managed-tuning API (see plan open item).
 *
 * Current encoding:
 *  - text          → {role, parts:[{text}]}
 *  - tool call      → {role:"model", parts:[{functionCall:{name, args}}]}
 *  - tool response  → {role:"user",  parts:[{functionResponse:{name, response}}]}
 */
@Component
class ToolCallMapper {

    fun toContent(turn: Turn): Map<String, Any?> = when (turn.kind) {
        TurnKind.TEXT -> mapOf(
            "role" to turn.role.gemini(),
            "parts" to listOf(mapOf("text" to turn.text)),
        )

        TurnKind.TOOL_CALL -> mapOf(
            "role" to "model",
            "parts" to listOf(
                mapOf(
                    "functionCall" to mapOf(
                        "name" to turn.toolName,
                        "args" to (Json.parse(turn.argsJson) ?: emptyMap<String, Any?>()),
                    ),
                ),
            ),
        )

        TurnKind.TOOL_RESPONSE -> mapOf(
            "role" to "user",
            "parts" to listOf(
                mapOf(
                    "functionResponse" to mapOf(
                        "name" to turn.toolName,
                        "response" to (Json.parse(turn.resultJson) ?: emptyMap<String, Any?>()),
                    ),
                ),
            ),
        )
    }

    private fun TurnRole.gemini(): String = when (this) {
        TurnRole.USER -> "user"
        TurnRole.MODEL -> "model"
    }
}

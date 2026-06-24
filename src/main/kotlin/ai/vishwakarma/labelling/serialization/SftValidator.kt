package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import org.springframework.stereotype.Component

/** Structural validation of an SFT example against the contents/parts conventions. */
@Component
class SftValidator {

    fun validate(example: SftExample): List<String> {
        val errors = mutableListOf<String>()
        val turns = example.turns

        if (turns.isEmpty()) {
            errors += "Conversation has no turns"
            return errors
        }

        val first = turns.first()
        if (!(first.role == TurnRole.USER && first.kind == TurnKind.TEXT)) {
            errors += "First turn must be a user text turn"
        }
        val last = turns.last()
        if (!(last.role == TurnRole.MODEL && last.kind == TurnKind.TEXT)) {
            errors += "Last turn must be a model text turn"
        }

        turns.forEachIndexed { i, t ->
            val n = i + 1
            when (t.kind) {
                TurnKind.TEXT -> if (t.text.isBlank()) errors += "Turn $n (${t.role}) text is empty"
                TurnKind.TOOL_CALL -> {
                    if (t.role != TurnRole.MODEL) errors += "Turn $n tool-call must be a model turn"
                    if (t.toolName.isNullOrBlank()) errors += "Turn $n tool-call has no tool name"
                    if (!Json.isValidObject(t.argsJson))
                        errors += "Turn $n tool-call args must be a JSON object"
                    val next = turns.getOrNull(i + 1)
                    if (next?.kind != TurnKind.TOOL_RESPONSE)
                        errors += "Turn $n tool-call must be followed by a tool response"
                }
                TurnKind.TOOL_RESPONSE -> {
                    if (t.toolName.isNullOrBlank())
                        errors += "Turn $n tool-response has no tool name"
                    if (!Json.isValid(t.resultJson))
                        errors += "Turn $n tool-response result must be valid JSON"
                    val prev = turns.getOrNull(i - 1)
                    if (prev?.kind != TurnKind.TOOL_CALL)
                        errors += "Turn $n tool-response must follow a tool call"
                }
            }
        }
        return errors
    }
}

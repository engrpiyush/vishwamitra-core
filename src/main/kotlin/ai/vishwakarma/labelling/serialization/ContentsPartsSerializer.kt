package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.SftExample
import org.springframework.stereotype.Component

/** Serializes an [SftExample] to the managed-tuning `contents`/`parts` JSONL line. */
@Component
class ContentsPartsSerializer(private val toolCallMapper: ToolCallMapper) {

    fun toContents(example: SftExample): Map<String, Any?> =
        mapOf("contents" to example.turns.map { toolCallMapper.toContent(it) })

    /** One JSONL line (no trailing newline). */
    fun toJsonl(example: SftExample): String = Json.writeLine(toContents(example))
}

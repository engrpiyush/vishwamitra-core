package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.ToolEncoding
import org.springframework.stereotype.Component

/** Serializes an [SftExample] to the managed-tuning `contents`/`parts` JSONL line. */
@Component
class ContentsPartsSerializer(private val toolCallMapper: ToolCallMapper) {

    fun toContents(
        example: SftExample,
        encoding: ToolEncoding = ToolEncoding.DEFAULT,
    ): Map<String, Any?> =
        mapOf("contents" to example.turns.map { toolCallMapper.toContent(it, encoding) })

    /** One JSONL line (no trailing newline). */
    fun toJsonl(example: SftExample, encoding: ToolEncoding = ToolEncoding.DEFAULT): String =
        Json.writeLine(toContents(example, encoding))
}

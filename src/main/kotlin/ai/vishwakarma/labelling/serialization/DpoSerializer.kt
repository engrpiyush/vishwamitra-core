package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.DpoPair
import ai.vishwakarma.labelling.domain.ToolEncoding
import org.springframework.stereotype.Component

/**
 * Serializes a [DpoPair] to the managed preference (DPO) dataset shape. Kept isolated like
 * [ToolCallMapper] — the exact `preferenceOptimizationSpec` schema is an open item to confirm
 * against the API; current encoding is prompt `contents` + `chosen`/`rejected` model responses.
 */
@Component
class DpoSerializer(private val toolCallMapper: ToolCallMapper) {

    fun toPreference(
        pair: DpoPair,
        encoding: ToolEncoding = ToolEncoding.DEFAULT,
    ): Map<String, Any?> =
        mapOf(
            "contents" to pair.promptTurns.map { toolCallMapper.toContent(it, encoding) },
            "chosen" to modelResponse(pair.chosenText),
            "rejected" to modelResponse(pair.rejectedText),
        )

    fun toJsonl(pair: DpoPair, encoding: ToolEncoding = ToolEncoding.DEFAULT): String =
        Json.writeLine(toPreference(pair, encoding))

    private fun modelResponse(text: String): Map<String, Any?> =
        mapOf("role" to "model", "parts" to listOf(mapOf("text" to text)))
}

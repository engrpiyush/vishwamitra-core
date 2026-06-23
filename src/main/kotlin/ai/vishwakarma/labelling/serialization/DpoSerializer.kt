package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.DpoPair
import org.springframework.stereotype.Component

/**
 * Serializes a [DpoPair] to the managed preference (DPO) dataset shape. Kept isolated like
 * [ToolCallMapper] — the exact `preferenceOptimizationSpec` schema is an open item to confirm against
 * the API; current encoding is prompt `contents` + `chosen`/`rejected` model responses.
 */
@Component
class DpoSerializer(private val toolCallMapper: ToolCallMapper) {

    fun toPreference(pair: DpoPair): Map<String, Any?> = mapOf(
        "contents" to pair.promptTurns.map { toolCallMapper.toContent(it) },
        "chosen" to modelResponse(pair.chosenText),
        "rejected" to modelResponse(pair.rejectedText),
    )

    fun toJsonl(pair: DpoPair): String = Json.writeLine(toPreference(pair))

    private fun modelResponse(text: String): Map<String, Any?> =
        mapOf("role" to "model", "parts" to listOf(mapOf("text" to text)))
}

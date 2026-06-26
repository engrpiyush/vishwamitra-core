package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.domain.ImportError
import org.springframework.stereotype.Component

/**
 * Validates the raw JSONL of an imported dataset against the Vertex open-model (OSS) tuning shape —
 * line by line, so errors carry a line number the user can act on. This is intentionally separate
 * from [SftValidator]/[DpoValidator], which validate in-memory domain objects rather than
 * serialized lines.
 *
 * Enforced per the OSS dataset contract:
 * - each line is one JSON object with a non-empty `contents` array;
 * - roles are `user`/`model` (`assistant` tolerated as `model`);
 * - parts only carry `text`/`inline_data`/`file_data` — structured
 *   `functionCall`/`functionResponse` parts are rejected (they must be encoded as text);
 * - SFT begins with a user text turn and ends with a model text turn;
 * - DPO carries top-level `chosen`/`rejected` model responses;
 * - each example fits the per-example token budget (rough char/4 estimate).
 */
@Component
class DatasetLineValidator(private val props: AppProperties) {

    private val allowedPartKeys = setOf("text", "inline_data", "file_data")

    fun validate(content: String, kind: ExportKind): List<ImportError> {
        val lines = content.split("\n").dropLastWhile { it.isBlank() }
        if (lines.isEmpty() || lines.all { it.isBlank() }) {
            return listOf(ImportError(0, "File is empty — expected one JSON object per line"))
        }

        val errors = mutableListOf<ImportError>()
        lines.forEachIndexed { idx, raw ->
            val n = idx + 1
            if (raw.isBlank()) {
                errors += ImportError(n, "Blank line (every line must be one JSON object)")
                return@forEachIndexed
            }

            val parsed =
                runCatching { Json.parse(raw) }
                    .getOrElse {
                        errors +=
                            ImportError(n, "Invalid JSON: ${it.message?.substringBefore('\n')}")
                        return@forEachIndexed
                    }
            val obj =
                parsed as? Map<*, *>
                    ?: run {
                        errors += ImportError(n, "Line must be a JSON object")
                        return@forEachIndexed
                    }

            when (kind) {
                ExportKind.SFT -> validateSft(n, obj, errors)
                ExportKind.DPO -> validateDpo(n, obj, errors)
            }

            estimateTokens(raw).let { est ->
                if (est > props.tuning.maxTokensPerExample) {
                    errors +=
                        ImportError(
                            n,
                            "≈$est tokens exceeds ${props.tuning.maxTokensPerExample} cap (estimate)",
                        )
                }
            }
        }
        return errors
    }

    private fun validateSft(n: Int, obj: Map<*, *>, errors: MutableList<ImportError>) {
        val contents = contentsOf(n, obj, errors) ?: return
        contents.forEachIndexed { i, c -> validateContent(n, i + 1, c, errors) }

        val first = contents.firstOrNull() as? Map<*, *>
        if (first != null && !(roleOf(first) == "user" && hasText(first))) {
            errors += ImportError(n, "First turn must be a user text turn")
        }
        val last = contents.lastOrNull() as? Map<*, *>
        if (last != null && !(roleOf(last) == "model" && hasText(last))) {
            errors += ImportError(n, "Last turn must be a model text turn")
        }
    }

    private fun validateDpo(n: Int, obj: Map<*, *>, errors: MutableList<ImportError>) {
        val contents = contentsOf(n, obj, errors)
        contents?.forEachIndexed { i, c -> validateContent(n, i + 1, c, errors) }

        listOf("chosen", "rejected").forEach { field ->
            val resp = obj[field]
            if (resp == null) {
                errors += ImportError(n, "DPO line missing top-level '$field' response")
            } else if (resp !is Map<*, *> || !hasText(resp)) {
                errors += ImportError(n, "DPO '$field' must be a response object with a text part")
            }
        }
    }

    private fun contentsOf(
        n: Int,
        obj: Map<*, *>,
        errors: MutableList<ImportError>,
    ): List<*>? {
        val contents = obj["contents"]
        if (contents !is List<*> || contents.isEmpty()) {
            errors += ImportError(n, "Missing or empty 'contents' array")
            return null
        }
        return contents
    }

    private fun validateContent(
        n: Int,
        pos: Int,
        content: Any?,
        errors: MutableList<ImportError>,
    ) {
        val c =
            content as? Map<*, *>
                ?: run {
                    errors += ImportError(n, "contents[$pos] must be an object")
                    return
                }
        val role = roleOf(c)
        if (role != "user" && role != "model") {
            errors += ImportError(n, "contents[$pos] role must be user or model (was '$role')")
        }
        val parts = c["parts"]
        if (parts !is List<*> || parts.isEmpty()) {
            errors += ImportError(n, "contents[$pos] must have a non-empty 'parts' array")
            return
        }
        parts.forEachIndexed { j, part ->
            val p = part as? Map<*, *>
            if (p == null) {
                errors += ImportError(n, "contents[$pos].parts[${j + 1}] must be an object")
                return@forEachIndexed
            }
            val keys = p.keys.map { it.toString() }
            if (keys.any { it == "functionCall" || it == "functionResponse" }) {
                errors +=
                    ImportError(
                        n,
                        "contents[$pos].parts[${j + 1}] has structured tool parts — not allowed; encode tool calls as text",
                    )
            }
            val unknown = keys.filterNot { it in allowedPartKeys }
            if (unknown.isNotEmpty()) {
                errors +=
                    ImportError(
                        n,
                        "contents[$pos].parts[${j + 1}] has unsupported key(s) ${unknown}; only text/inline_data/file_data allowed",
                    )
            }
        }
    }

    /** `assistant` is tolerated and treated as `model`. */
    private fun roleOf(content: Map<*, *>): String? =
        (content["role"] as? String)?.let { if (it == "assistant") "model" else it }

    private fun hasText(content: Map<*, *>): Boolean {
        val parts = content["parts"] as? List<*> ?: return false
        return parts.any { (it as? Map<*, *>)?.get("text")?.toString()?.isNotBlank() == true }
    }

    /** Rough token estimate (~4 chars/token); not a real tokenizer. */
    private fun estimateTokens(line: String): Int = Math.ceil(line.length / 4.0).toInt()
}

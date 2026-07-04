package ai.vishwakarma.labelling.domain

import java.time.Instant
import org.yaml.snakeyaml.Yaml

/**
 * Admin-managed per-[ContentType] extraction instruction block (Firestore `extraction_prompts`,
 * document id = the [ContentType] name). Appended to the extractor's base contract at run time;
 * when no row exists the code default from `extraction-prompts.yaml` applies ([builtinFor]) — the
 * file is the circulated latest copy, an admin row always overrides it, and "Reset to default"
 * deletes the row to fall back. [version] increments on every save and is stamped onto extracted
 * claims (with a hash) so Stage 3 can compare like with like (version 0 = the code default).
 */
data class ExtractionPrompt(
    val id: String,
    val instructions: String = "",
    /** Incremented on every save; 0 is reserved for the code default. */
    val version: Int = 0,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
) {
    companion object {
        /** Classpath source of the per-content-type default blocks. */
        const val DEFAULTS_RESOURCE = "/extraction-prompts.yaml"

        private val defaults: Map<ContentType, String> by lazy {
            val stream =
                ExtractionPrompt::class.java.getResourceAsStream(DEFAULTS_RESOURCE)
                    ?: error("Missing classpath resource $DEFAULTS_RESOURCE")
            val raw: Map<String, String> = stream.use { Yaml().load(it) } ?: emptyMap()
            raw.entries
                .mapNotNull { (key, text) ->
                    ContentType.fromOrNull(key)?.let { it to text.trim() }
                }
                .toMap()
        }

        /** The code-default instruction block for [contentType] (empty = base contract only). */
        fun builtinFor(contentType: ContentType): String = defaults[contentType] ?: ""
    }
}

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import java.security.MessageDigest
import java.time.Instant
import org.springframework.stereotype.Service

/** One admin-page row: a content type with its stored override (if any) and built-in fallback. */
data class ExtractionPromptRow(
    val contentType: ContentType,
    val prompt: ExtractionPrompt?,
    val builtin: String,
)

/** The instruction block an extraction run actually used, with its provenance stamp. */
data class ResolvedExtractionPrompt(
    val instructions: String,
    /** Stored row version, or 0 for the built-in default. */
    val version: Int,
    /** Short SHA-256 of [instructions] — identifies the exact block across version resets. */
    val hash: String,
)

/**
 * Admin-managed per-content-type extraction instruction blocks. Rows are read fresh on every
 * [resolve] — an admin edit changes the very next extraction run, no deploy, no cache.
 */
@Service
class ExtractionPromptService(private val prompts: ExtractionPromptRepository) {

    /** Every content type (stored row or built-in), grouped by source class for the admin page. */
    fun list(): Map<SourceClass, List<ExtractionPromptRow>> {
        val existing = prompts.findAll().associateBy { it.id }
        return ContentType.entries
            .map { ExtractionPromptRow(it, existing[it.name], ExtractionPrompt.builtinFor(it)) }
            .groupBy { it.contentType.sourceClass }
    }

    /** Save the admin override (version+1). Blank text is refused — reverting is [reset]'s job. */
    fun update(contentType: ContentType, instructions: String, actor: String?): ExtractionPrompt {
        val text = instructions.trim()
        require(text.isNotEmpty()) {
            "Instructions cannot be blank — use Reset to revert to the code default"
        }
        val row =
            ExtractionPrompt(
                id = contentType.name,
                instructions = text,
                version = (prompts.findById(contentType.name)?.version ?: 0) + 1,
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        prompts.save(row)
        return row
    }

    /** Delete the admin override so [resolve] falls back to the code default. */
    fun reset(contentType: ContentType) {
        prompts.delete(contentType.name)
    }

    /** The block extraction uses for [contentType]: the stored row when present, else built-in. */
    fun resolve(contentType: ContentType): ResolvedExtractionPrompt {
        val row = prompts.findById(contentType.name)?.takeIf { it.instructions.isNotBlank() }
        val instructions = row?.instructions ?: ExtractionPrompt.builtinFor(contentType)
        return ResolvedExtractionPrompt(instructions, row?.version ?: 0, hash(instructions))
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
}

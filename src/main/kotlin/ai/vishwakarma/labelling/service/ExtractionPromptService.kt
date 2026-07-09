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

/** One admin-page row for the reserved Stage 3 keys (no [ContentType] behind them). */
data class ReservedPromptRow(
    val key: String,
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

    /** The reserved Stage 3 rows (LLD §11.3/§11.6), same override mechanics as content types. */
    fun listStage3(): List<ReservedPromptRow> {
        val existing = prompts.findAll().associateBy { it.id }
        return STAGE3_KEYS.map {
            ReservedPromptRow(it, existing[it], ExtractionPrompt.builtinForKey(it))
        }
    }

    /** Save the admin override (version+1). Blank text is refused — reverting is [reset]'s job. */
    fun update(contentType: ContentType, instructions: String, actor: String?): ExtractionPrompt =
        updateKey(contentType.name, instructions, actor)

    /** [update] for the reserved Stage 3 keys — the admin page routes them here by name. */
    fun updateKey(key: String, instructions: String, actor: String?): ExtractionPrompt {
        val text = instructions.trim()
        require(text.isNotEmpty()) {
            "Instructions cannot be blank — use Reset to revert to the code default"
        }
        val row =
            ExtractionPrompt(
                id = key,
                instructions = text,
                version = (prompts.findById(key)?.version ?: 0) + 1,
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

    /** [reset] for the reserved Stage 3 keys. */
    fun resetKey(key: String) {
        prompts.delete(key)
    }

    /** True when [id] is a reserved Stage 3 row the admin page may edit alongside content types. */
    fun isStage3Key(id: String): Boolean = id in STAGE3_KEYS

    /** The block extraction uses for [contentType]: the stored row when present, else built-in. */
    fun resolve(contentType: ContentType): ResolvedExtractionPrompt = resolveKey(contentType.name)

    /**
     * Same resolution for the reserved non-ContentType rows Stage 3 rides on the collection
     * (`STAGE3_ENTITY` now, `STAGE3_JUDGE` with VA-15). Not listed on the admin page yet — that
     * surface arrives with the judge prompt; until then an override row created directly in
     * Firestore is picked up by the very next run, same as any admin edit.
     */
    fun resolveKey(key: String): ResolvedExtractionPrompt {
        val row = prompts.findById(key)?.takeIf { it.instructions.isNotBlank() }
        val instructions = row?.instructions ?: ExtractionPrompt.builtinForKey(key)
        return ResolvedExtractionPrompt(instructions, row?.version ?: 0, hash(instructions))
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    companion object {
        /** The reserved non-ContentType rows, in admin-page order. */
        val STAGE3_KEYS = listOf("STAGE3_ENTITY", "STAGE3_JUDGE")
    }
}

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.Stage4Category
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

/** The Stage 4 slice of the extraction-prompts admin page (VA-66, LLD §6/§15). */
data class Stage4PromptRows(
    /** The admin-designated default preset id (the `stage4:preset-default` pointer). */
    val defaultPresetId: String,
    /** Every offerable preset id, built-in and admin-added. */
    val presetIds: List<String>,
    /** The preset rows, keyed `stage4:preset:<id>` — admin-added ones have no builtin. */
    val presets: List<ReservedPromptRow>,
    /** The four LLM-backed per-category generator rows (META is template-rendered, no row). */
    val generators: List<ReservedPromptRow>,
    /** The §11 judge rubric row. */
    val judge: ReservedPromptRow,
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

    /** True when [id] is a reserved product row (advocate chat system, F11 generator). */
    fun isProductKey(id: String): Boolean = id in PRODUCT_KEYS

    /**
     * The F11 generator row for the Stage 2 Prompts tab (VA-34 — the review flow it frames lives
     * there; `advocate_system` stays POST-only as VA-42 built it).
     */
    fun listF11(): ReservedPromptRow {
        val existing = prompts.findById(F11_QUESTION_GENERATOR_KEY)
        return ReservedPromptRow(
            F11_QUESTION_GENERATOR_KEY,
            existing,
            ExtractionPrompt.builtinForKey(F11_QUESTION_GENERATOR_KEY),
        )
    }

    /** True when [id] is a reserved Stage 4 row (presets incl. admin-added, generators, judge). */
    fun isStage4Key(id: String): Boolean =
        id == STAGE4_JUDGE_KEY ||
            id == STAGE4_PRESET_DEFAULT_KEY ||
            id.startsWith(STAGE4_PRESET_PREFIX) ||
            id in STAGE4_GENERATOR_KEYS

    /** The VA-66 admin-page slice: presets (+ default pointer), generator rows, judge rubric. */
    fun listStage4(): Stage4PromptRows {
        val existing = prompts.findAll().associateBy { it.id }
        fun row(key: String) =
            ReservedPromptRow(key, existing[key], ExtractionPrompt.builtinForKey(key))
        val presetIds = stage4PresetIds()
        return Stage4PromptRows(
            defaultPresetId = defaultStage4PresetId(),
            presetIds = presetIds,
            presets = presetIds.map { row(STAGE4_PRESET_PREFIX + it) },
            generators = STAGE4_GENERATOR_KEYS.map { row(it) },
            judge = row(STAGE4_JUDGE_KEY),
        )
    }

    /**
     * Register a new admin preset (`stage4:preset:<slug>`, VA-66) — [stage4PresetIds] picks stored
     * rows up by prefix, so saving the row IS the registration. Slugged, collision-refused.
     */
    fun createStage4Preset(name: String, instructions: String, actor: String?): ExtractionPrompt {
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
        require(slug.isNotEmpty()) { "Preset name must contain letters or digits" }
        val key = STAGE4_PRESET_PREFIX + slug
        require(slug !in stage4PresetIds()) { "Preset '$slug' already exists" }
        require(instructions.isNotBlank()) { "Preset style text cannot be blank" }
        return updateKey(key, instructions, actor)
    }

    /**
     * Persona-preset ids the wizard offers (B3): the built-in trio plus any admin-added
     * `stage4:preset:<id>` rows (the LLD §6 reserved-key idiom).
     */
    fun stage4PresetIds(): List<String> {
        val stored = prompts.findAll().map { it.id }.filter { it.startsWith(STAGE4_PRESET_PREFIX) }
        return (STAGE4_PRESET_KEYS + stored)
            .map { it.removePrefix(STAGE4_PRESET_PREFIX) }
            .distinct()
    }

    /** The admin-designated default preset — the `stage4:preset-default` pointer row. */
    fun defaultStage4PresetId(): String {
        val pointed = resolveKey(STAGE4_PRESET_DEFAULT_KEY).instructions.trim()
        val ids = stage4PresetIds()
        return if (pointed in ids) pointed else ids.first()
    }

    /** The style block behind one preset id — the generation prompt's B3 ingredient. */
    fun resolveStage4Preset(presetId: String): ResolvedExtractionPrompt =
        resolveKey(STAGE4_PRESET_PREFIX + presetId)

    /**
     * The per-category generator instruction row (LLD §9.3) — versioned + hashed like every prompt
     * row, so a rubric edit invalidates exactly the affected generation-cache keys. META renders
     * LLM-free and deliberately has no row (its stamp is a code constant).
     */
    fun resolveStage4Generator(category: Stage4Category): ResolvedExtractionPrompt =
        resolveKey(stage4GeneratorKey(category))

    /** The reserved `stage4:gen:*` key for one LLM-backed category. */
    fun stage4GeneratorKey(category: Stage4Category): String =
        when (category) {
            Stage4Category.QA -> "stage4:gen:qa"
            Stage4Category.SITUATIONAL -> "stage4:gen:situational"
            Stage4Category.MULTI_CLAIM -> "stage4:gen:multi-claim"
            Stage4Category.NEGATIVE -> "stage4:gen:negative"
            Stage4Category.META ->
                error("META renders from code templates — no generator prompt row (§9.3)")
        }

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

        /**
         * The reserved product row (VA-42, product LLD §7.5): the advocate chat system prompt,
         * resolved fresh per send by `AdvocateChatService` ({{subject}} substituted at call time).
         */
        const val ADVOCATE_SYSTEM_KEY = "advocate_system"

        /**
         * The F11 clarification-question generator row (VA-34, product LLD §9.2) — resolved fresh
         * per generation batch by `QuestionService`.
         */
        const val F11_QUESTION_GENERATOR_KEY = "f11_question_generator"

        /** The reserved product rows the admin prompt POSTs accept (§12.1 idiom). */
        val PRODUCT_KEYS = listOf(ADVOCATE_SYSTEM_KEY, F11_QUESTION_GENERATOR_KEY)

        /** The reserved §11 judge-rubric row (VA-57) — resolved by [GeminiStage4Judge]. */
        const val STAGE4_JUDGE_KEY = "stage4:judge"

        /** Reserved Stage 4 persona-preset rows (LLD §6) + the default-preset pointer row. */
        const val STAGE4_PRESET_PREFIX = "stage4:preset:"
        const val STAGE4_PRESET_DEFAULT_KEY = "stage4:preset-default"
        val STAGE4_PRESET_KEYS =
            listOf(
                "stage4:preset:warm-storyteller",
                "stage4:preset:crisp-professional",
                "stage4:preset:grounded-mentor",
            )

        /**
         * The reserved `stage4:gen:*` rows (§9.3), in admin-page order — see [stage4GeneratorKey].
         */
        val STAGE4_GENERATOR_KEYS =
            listOf(
                "stage4:gen:qa",
                "stage4:gen:situational",
                "stage4:gen:multi-claim",
                "stage4:gen:negative",
            )
    }
}

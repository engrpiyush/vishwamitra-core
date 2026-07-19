package ai.vishwakarma.labelling.domain

import java.time.Instant

/** The seven fixed grouping axes of the notebook-template taxonomy (LLD §14A.6). */
enum class TemplateCategoryGroup(val label: String) {
    FOUNDATIONAL("Foundational"),
    BEHAVIOURAL_VALUES("Behavioural / values"),
    PERSPECTIVES("Perspectives"),
    EVALUATIVE("Evaluative"),
    HARD_BOUNDARY("Hard / boundary"),
    MULTI_TURN("Multi-turn"),
    STYLE_OTHER("Style / other");

    companion object {
        fun fromOrNull(raw: String?): TemplateCategoryGroup? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * One evaluation-trait category. The [slug] is the stable id templates reference and doubles as the
 * VA-60 behavioural-eval axis id, so renames keep the slug.
 */
data class TemplateCategory(
    val slug: String,
    val name: String,
    val group: TemplateCategoryGroup,
)

/**
 * Single-document editable category vocabulary (the [Taxonomy] idiom): one doc in the `taxonomy`
 * collection, id = [DOC_ID]. Groups are the fixed [TemplateCategoryGroup] enum; the ~30 categories
 * inside them are admin-editable.
 */
data class NotebookTemplateTaxonomy(val categories: List<TemplateCategory> = emptyList()) {

    fun bySlug(slug: String): TemplateCategory? = categories.find { it.slug == slug }

    /** Categories grouped for display, in enum (LLD) order. */
    fun grouped(): Map<TemplateCategoryGroup, List<TemplateCategory>> =
        TemplateCategoryGroup.entries.associateWith { g -> categories.filter { it.group == g } }

    companion object {
        const val DOC_ID = "notebook-template-categories"
    }
}

/**
 * The conversation-format scaffold a template pins down (LLD §14A.6) — what Stage 4 planning fills
 * with subject facts instead of generating blind.
 */
data class FormatSpec(
    /** Turn structure, e.g. "single question + answer" or "4–6 turn probing exchange". */
    val turnShape: String = "",
    /** What the conversation is testing or eliciting. */
    val intent: String = "",
    /** Who is asking — the interlocutor persona the advocate faces (peer, recruiter, client…). */
    val personaLens: String = "",
    /** Expected advocate behaviours, including refusals/boundary handling. One item per rule. */
    val expectedBehaviours: List<String> = emptyList(),
    /**
     * A realistic verbatim first message from the interlocutor — the drafter voices in its spirit.
     */
    val openingProbe: String = "",
    /** What a badly-tuned model does here instead. One item per failure. Authoring/eval aid. */
    val failureModes: List<String> = emptyList(),
)

/**
 * A pre-defined conversation format for Stage 4 notebook generation, tagged to an evaluation-trait
 * [category] (a [TemplateCategory] slug). Stored in `notebook_templates`; versioned like the other
 * admin rows ([version] increments on every save). Migrates [Scenario] (VA-87); consumed by
 * `Stage4Planning` once VA-88 wires it in.
 */
data class NotebookTemplate(
    val id: String,
    val category: String,
    val title: String,
    val formatSpec: FormatSpec = FormatSpec(),
    /** Prompt scaffold handed to the Stage 4 generator ({{subject}} placeholders allowed). */
    val promptTemplate: String = "",
    /** How many notebooks per subject the planner should aim to draw from this template. */
    val coverageTarget: Int = 1,
    /**
     * Subject-side applicability tags (role-track / seniority / craft…) or `role-agnostic`.
     * Advisory context for the planner and generator; not hard-enforced (kept as authored free
     * text).
     */
    val facets: String = "",
    /**
     * The authoritative, human-readable evidence gate: what the subject's ledger must contain for
     * this template to be drawn at all. Blank ⇒ no gate. The prose is the source of truth;
     * [requiredClaimTypes] is its machine-enforceable projection.
     */
    val evidenceGate: String = "",
    /**
     * The enforceable projection of [evidenceGate] onto claim types. `Stage4Planning` only draws
     * this template against a subject fact whose member claims cover every listed type. Empty ⇒ the
     * template fires for every subject (legacy behaviour), so a blank gate is byte-compatible.
     */
    val requiredClaimTypes: List<ClaimType> = emptyList(),
    /** The O1–O9 training outcomes this template targets; doubles as a VA-60 eval handle. */
    val outcomes: List<String> = emptyList(),
    /** Incremented on every save (create = 1), the ExtractionPrompt idiom. */
    val version: Int = 1,
    /** Provenance when the row was migrated from a legacy `scenarios` row. */
    val migratedFrom: String? = null,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

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
    /**
     * The *fine-grained* half of [evidenceGate], as [DeclaredType] keys (SubjectProfile LLD §3.4,
     * OD-3). `Stage4Planning` draws this template only against a subject fact one of whose member
     * claims carries a `declaredType` listed here. Empty ⇒ no fine gate, so every existing template
     * behaves exactly as before.
     *
     * **ANY-of, unlike [requiredClaimTypes]'s all-of** — and that asymmetry is the gate prose, not
     * a shortcut. The authored gates read "a ledger item of type {stated-aspiration |
     * stated-track-preference | self-disclosed-driver}": alternatives, one of which suffices. The
     * coarse list reads "member claims cover every listed type": a conjunction. Each is enforced
     * the way it is written.
     *
     * This is what closes the O5 gap the coarse projection leaves open: a `VALUE`-gated `cat-27`
     * row also fires on any ordinary extracted VALUE claim, so the decline could rest on something
     * that is not a stated direction at all. With the fine gate the row draws the declared
     * aspiration or it draws nothing and reports `missed` — which is the row's own designed
     * behaviour.
     */
    val requiredDeclaredTypes: List<String> = emptyList(),
    /** The O1–O9 training outcomes this template targets; doubles as a VA-60 eval handle. */
    val outcomes: List<String> = emptyList(),
    /**
     * The sysgen-written "conversation rules" block of this template's system prompt (LLD §9.6) —
     * subject-neutral, generated once per template by the `stage4-sysgen` pin and cached here.
     * Blank = not generated yet (the next system-prompt run fills it).
     */
    val systemRules: String = "",
    /**
     * The [version] the rules were generated from — a template edit bumps [version] past this and
     * the stale rules regenerate. Saving the rules themselves must NOT bump [version] (see
     * `NotebookTemplateService.saveSystemRules`), or generation would loop forever.
     */
    val systemRulesSourceVersion: Int = 0,
    /** Short hash of the `stage4:sysgen` row the rules were generated under (provenance/cache). */
    val systemRulesPromptHash: String = "",
    /** The model that wrote the rules; null = dry-run double or hand-authored. */
    val systemRulesModel: String? = null,
    /** Incremented on every save (create = 1), the ExtractionPrompt idiom. */
    val version: Int = 1,
    /** Provenance when the row was migrated from a legacy `scenarios` row. */
    val migratedFrom: String? = null,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

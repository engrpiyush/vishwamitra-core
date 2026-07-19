package ai.vishwakarma.labelling.domain

import java.time.Instant

/** The four pipeline stages as admin-console sections (the 4Cs, LLD §14A.4(2)). */
enum class StageKey(val num: Int, val title: String, val blurb: String) {
    STAGE1(1, "Collection", "Evidence intake — uploads, validation, sealing."),
    STAGE2(2, "Claim extraction", "A/V + documents → transcribed, attributed, extracted claims."),
    STAGE3(3, "Calibration", "Claims → authenticity graph, judged edges, scores."),
    STAGE4(4, "Construction", "Scored facts → planned, generated, judged training conversations.");

    val id: String
        get() = "stage$num"

    companion object {
        fun fromNum(num: Int): StageKey? = entries.find { it.num == num }
    }
}

/** Form/render type of one stage-config field. */
enum class ConfigFieldKind {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    /** java.time.Duration — rendered/parsed as `15m`, `2h`, `300s` (or ISO-8601). */
    DURATION,
    /** List<String> — rendered/parsed comma-separated. */
    STRING_LIST,
    /** Map<String, Double> — rendered/parsed as `KEY=1.0, KEY2=2.5` lines. */
    DOUBLE_MAP,
}

/**
 * Catalog entry for one editable (or surfaced read-only) property of a stage's `app.*` block.
 * [name] must match the AppProperties constructor parameter (nested Stage-4 mix fields use
 * `mix.<dial>`). Non-[editable] fields render read-only with [readOnlyReason] as the tooltip;
 * [secret] fields render masked and are never stored (LLD §14A.5 guardrail a).
 */
data class ConfigField(
    val name: String,
    val label: String,
    val kind: ConfigFieldKind,
    val group: String,
    val help: String,
    val editable: Boolean = true,
    val secret: Boolean = false,
    val readOnlyReason: String = "",
    /**
     * Closed value set for a STRING field (VA-106). Non-empty renders a `<select>` instead of a
     * free-text input and is enforced on save, so a dial that selects *which subsystem runs* cannot
     * be typo'd into silently meaning its default — the failure mode the older enum-ish STRING
     * fields (`matchingMode`, `ensembleOrderings`) still carry, where the allowed values live only
     * in the help text.
     */
    val options: List<String> = emptyList(),
)

/**
 * The per-stage override document (Firestore `stage_config/{stageId}`, LLD §14A.5): only values
 * that deviate from the bootstrap (yml/env/code) default are stored. [version] increments on every
 * save — the ExtractionPrompt idiom — so a bad knob is attributable and revertible.
 */
data class StageConfigDoc(
    val stage: String,
    val overrides: Map<String, Any?> = emptyMap(),
    val version: Int = 0,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

/** One row of the Configuration tab: catalog field + effective/default rendering. */
data class ConfigFieldView(
    val field: ConfigField,
    /** Effective value as the form string (masked when secret). */
    val effective: String,
    /** Bootstrap default as the form string (masked when secret). */
    val default: String,
    /** True when a stored override is in effect for this field. */
    val overridden: Boolean,
)

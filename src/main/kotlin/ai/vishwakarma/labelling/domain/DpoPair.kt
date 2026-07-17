package ai.vishwakarma.labelling.domain

import java.time.Instant

/** How a preference pair was produced. */
enum class DpoSource {
    LLM2,
    MANUAL
}

/**
 * The six §12 rejected-side violation classes (VA-61): what the generated dispreferred response
 * deliberately gets wrong relative to the compliant chosen turn.
 */
enum class DpoViolationClass {
    /** Voices above the band ceiling — row 1 confidence on a row 3 claim. */
    OVERCLAIM,
    /** Denies an evidenced weakness or contradiction (F2). */
    DENIAL,
    /** A derived conclusion voiced without the §10.3 hedging rules. */
    UNHEDGED_SPECULATION,
    /** Answers a row 13 banned question instead of refusing; volunteers PII without opt-in. */
    BOUNDARY_BREACH,
    /** The banned A1 stance — speaks as the subject ("I led…"). */
    IMPERSONATION,
    /** A row 6 claim voiced without its mandatory sidecar framing. */
    CONTEXT_STRIPPED;

    /** The `stage4:dpo:*` prompt-row slug. */
    val slug: String
        get() = name.lowercase().replace('_', '-')

    companion object {
        fun fromOrNull(raw: String?): DpoViolationClass? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * A DPO preference pair: a prompt (one or more leading turns, ending on the user side) plus a
 * preferred [chosenText] and a dispreferred [rejectedText] model response. Exported via the
 * preference dataset format when APPROVED. Shares the [ExampleStatus] lifecycle with SFT.
 */
data class DpoPair(
    val id: String,
    val promptTurns: List<Turn> = emptyList(),
    val chosenText: String = "",
    val rejectedText: String = "",
    val tags: ExampleTags = ExampleTags(),
    val status: ExampleStatus = ExampleStatus.DRAFT,
    val source: DpoSource = DpoSource.MANUAL,
    val fromSftId: String? = null,
    /** The §12 violation class the rejected side renders (VA-61); null on manual pairs. */
    val violationClass: DpoViolationClass? = null,
    /** Stage 4 traceability stamp (§6); null on legacy labelling-era pairs. */
    val stamp: Stage4Stamp? = null,
    /** Why the pair was ARCHIVED, e.g. "superseded by <scoreRunId|personaHash>" (QA-6). */
    val archivedReason: String? = null,
    val reviewComments: List<ReviewComment> = emptyList(),
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val exportedIn: List<String> = emptyList(),
)

package ai.vishwakarma.labelling.domain

import java.time.Instant

/** How a preference pair was produced. */
enum class DpoSource { LLM2, MANUAL }

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
    val reviewComments: List<ReviewComment> = emptyList(),
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val exportedIn: List<String> = emptyList(),
)

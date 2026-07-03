package ai.vishwakarma.labelling.domain

import java.time.Instant

/** Lifecycle states shared by SFT examples and DPO pairs. */
enum class ExampleStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    NEEDS_CHANGES,
    ARCHIVED
}

/** How the example was produced. */
enum class ExampleSource {
    MANUAL,
    LLM
}

enum class TurnRole {
    USER,
    MODEL
}

/** A turn is either plain text, a model function-call, or a tool (function) response. */
enum class TurnKind {
    TEXT,
    TOOL_CALL,
    TOOL_RESPONSE
}

/**
 * One conversation turn. For TEXT: [role] + [text]. For TOOL_CALL (always model):
 * [toolName] + [argsJson] (a JSON object). For TOOL_RESPONSE (serialized on the user side):
 * [toolName] + [resultJson]. JSON is kept as strings here and parsed by the serializer.
 */
data class Turn(
    val role: TurnRole,
    val kind: TurnKind = TurnKind.TEXT,
    val text: String = "",
    val toolName: String? = null,
    val argsJson: String? = null,
    val resultJson: String? = null,
)

/** Split a comma- (or newline-) separated label string into clean, de-duplicated labels. */
fun splitLabels(raw: String?): List<String> =
    raw?.split(',', '\n')?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()

data class ExampleTags(
    val claimType: ClaimType? = null,
    val authenticityTier: AuthenticityTier? = null,
    val labels: List<String> = emptyList(),
    val hasToolCall: Boolean = false,
)

data class ReviewComment(
    val by: String?,
    val text: String,
    val at: Instant = Instant.now(),
)

/**
 * A supervised fine-tuning example: an ordered multi-turn conversation plus tags, lifecycle and
 * review history. Exported as one `contents`/`parts` JSONL line when APPROVED.
 */
data class SftExample(
    val id: String,
    val tags: ExampleTags = ExampleTags(),
    val turns: List<Turn> = emptyList(),
    val status: ExampleStatus = ExampleStatus.DRAFT,
    val source: ExampleSource = ExampleSource.MANUAL,
    val llmModel: String? = null,
    val scenarioId: String? = null,
    val reviewComments: List<ReviewComment> = emptyList(),
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val exportedIn: List<String> = emptyList(),
)

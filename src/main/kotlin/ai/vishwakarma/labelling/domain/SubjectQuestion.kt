package ai.vishwakarma.labelling.domain

import java.time.Instant

/** What triggered an F11 clarification question (LLD §9.1). */
enum class QuestionTrigger {
    UNFAVORABLE,
    CONTRADICTION;

    companion object {
        fun fromOrNull(raw: String?): QuestionTrigger? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** F11 question lifecycle. */
enum class QuestionStatus {
    OPEN,
    ANSWERED,
    SKIPPED;

    companion object {
        fun fromOrNull(raw: String?): QuestionStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * `subject_questions/{id}` (LLD §5.1, §9) — the F11 clarification inbox, indexed `(subjectId,
 * status)`.
 */
data class SubjectQuestion(
    val id: String,
    val subjectId: String,
    val trigger: QuestionTrigger,
    val claimIds: List<String> = emptyList(),
    /** The contradiction edge that raised it (CONTRADICTION trigger only). */
    val edgeId: String? = null,
    val questionText: String,
    val status: QuestionStatus = QuestionStatus.OPEN,
    val answer: String? = null,
    val askedAt: Instant? = null,
    val answeredAt: Instant? = null,
    /** Which generator produced it (model id / dry-run double) — reproducibility trail. */
    val generatedBy: String? = null,
)

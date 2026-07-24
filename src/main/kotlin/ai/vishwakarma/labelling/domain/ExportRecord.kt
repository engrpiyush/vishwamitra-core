package ai.vishwakarma.labelling.domain

import java.time.Instant

enum class ExportKind(val dir: String) {
    SFT("sft"),
    DPO("dpo")
}

/** A point-in-time export snapshot written to the training bucket. */
data class ExportRecord(
    val id: String,
    val kind: ExportKind,
    val gcsUri: String,
    val exampleIds: List<String> = emptyList(),
    val count: Int = 0,
    /**
     * Who's-who: the subject's handle (or id stem) on stage-4 exports — carried into the tuned
     * model's display name + weights path. Null on tag-filtered labelling exports.
     */
    val subjectTag: String? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
)

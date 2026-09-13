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
    /** The JSONL family the blob was written in; null = pre-format record ([GENERATE_CONTENT]). */
    val datasetFormat: DatasetFormat? = null,
    /**
     * How many exported lines carried a leading system message (LLD §9.6 provenance) — 0 on
     * legacy/contents exports.
     */
    val systemPromptCount: Int = 0,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
)

package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * Where a per-asset Stage 2 job is in its lifecycle.
 * - [PENDING] — created; transcription not yet submitted.
 * - [TRANSCRIBING] — ASR long-running operation submitted; awaiting completion via poll.
 * - [EXTRACTING] — transcript ready; Claude claim extraction in progress.
 * - [COMPLETED] — claims written to the ledger (terminal).
 * - [FAILED] — transcription or extraction failed; see [Stage2Job.error] (terminal).
 */
enum class Stage2JobStatus {
    PENDING,
    TRANSCRIBING,
    EXTRACTING,
    COMPLETED,
    FAILED;

    companion object {
        fun fromOrNull(raw: String?): Stage2JobStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * One per-asset Stage 2 processing record — the submit-then-poll job mirroring [TuningJob]. Created
 * by `process` for every eligible AUDIO/VIDEO asset of a sealed manifest; driven to a terminal
 * state by `poll`.
 */
data class Stage2Job(
    val id: String,
    val subjectId: String,
    val assetId: String,
    val modality: AssetModality,
    val status: Stage2JobStatus = Stage2JobStatus.PENDING,
    /** The ASR long-running operation name (polled), e.g. an STT batchRecognize LRO. */
    val externalOperationId: String? = null,
    /** Which decoding candidate produced the current operation (advanced on encoding rejects). */
    val decodingAttempt: Int = 0,
    /** gs:// of the stored transcript once transcription completes. */
    val transcriptUri: String? = null,
    /** Claims written to the ledger on completion. */
    val claimCount: Int? = null,
    /** Failure detail for FAILED jobs. */
    val error: String? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    /** When transcription was submitted. */
    val startedAt: Instant? = null,
    /** When the job reached a terminal state. */
    val finishedAt: Instant? = null,
    /** When the job entered EXTRACTING — the clock for stuck-job reclaim (§12.7 hardening). */
    val extractingSince: Instant? = null,
)

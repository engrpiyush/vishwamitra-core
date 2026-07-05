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
    /**
     * Multi-speaker A/V whose transcript landed but whose speaker→role attribution wasn't confident
     * enough to auto-run (§12.4): the job waits here for the operator to tag which diarized
     * speaker(s) are the subject before extraction proceeds. Single-speaker and high-confidence
     * jobs skip this state.
     */
    AWAITING_SPEAKER_SELECTION,
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
 * The role a diarized speaker plays in a multi-speaker A/V asset (§12.4). Resolved per speaker
 * label from the transcript + asset context (LLM first-pass, operator-overridable), it decides how
 * each of that speaker's claims is weighted:
 * - [SUBJECT] — the subject speaking about themselves → self-report (SELF / LOW prior).
 * - [ENDORSER] — a third party speaking about the subject → ENDORSEMENT, refined by [Relationship].
 * - [INTERVIEWER] — asks questions / facilitates → produces no claims (their spans are dropped).
 * - [OTHER] — bystander / unresolvable → dropped, same as interviewer.
 */
enum class SpeakerRole {
    SUBJECT,
    ENDORSER,
    INTERVIEWER,
    OTHER;

    companion object {
        fun fromOrNull(raw: String?): SpeakerRole? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * One diarization label's resolved identity (§12.4) — the value side of a [Stage2Job.speakerRoles]
 * binding. [relationship] is only meaningful for [SpeakerRole.ENDORSER] (MANAGER vs PEER vs EXPERT
 * … — it refines the endorsement prior); [name] is an optional operator/LLM-supplied display name.
 */
data class SpeakerAssignment(
    val role: SpeakerRole,
    val relationship: Relationship? = null,
    val name: String? = null,
)

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
    /**
     * Speaker→role binding for a multi-speaker asset (§12.4), keyed by the diarization label
     * ("Speaker 1"). Populated after transcription (LLM first-pass, operator-overridable); null for
     * single-speaker / non-diarized jobs, which keep asset-level claim provenance. Extraction reads
     * it to re-weight each claim by its speaker's role and to drop interviewer spans; it survives
     * Retry/Re-run so an operator override is reused on re-extraction.
     */
    val speakerRoles: Map<String, SpeakerAssignment>? = null,
    /**
     * Per-diarization-label sample text (§12.4), captured when a multi-speaker job parks in
     * [Stage2JobStatus.AWAITING_SPEAKER_SELECTION] so the operator can tell who's who without
     * re-fetching the transcript. Null outside the selection gate.
     */
    val speakerSamples: Map<String, String>? = null,
)

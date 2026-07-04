package ai.vishwakarma.labelling.stage2

/** One diarized speech span. Times are seconds into the source media. */
data class TranscriptSegment(
    /** Diarization label (e.g. "Speaker 1"); null when the model returned no speaker info. */
    val speaker: String?,
    val start: Double?,
    val end: Double?,
    val text: String,
)

/** A normalized, speaker-labelled transcript — the provider-independent ASR output. */
data class Transcript(
    val segments: List<TranscriptSegment>,
    val language: String? = null,
)

/** Outcome of polling a transcription operation. */
sealed interface TranscriptionPoll {
    /** The operation is still running — poll again later. */
    data object Running : TranscriptionPoll

    /** Transcription finished; [transcriptUri] is the stored raw output (null in dry-run). */
    data class Done(val transcript: Transcript, val transcriptUri: String?) : TranscriptionPoll

    /**
     * The operation itself reported a terminal error. Audio decoding happens while the operation
     * runs (not at submit), so encoding rejections surface here; the provider flags them
     * [retryableWithNextDecoding] and the caller may resubmit with the next decoding attempt.
     */
    data class Failed(val error: String, val retryableWithNextDecoding: Boolean = false) :
        TranscriptionPoll
}

/**
 * The ASR seam: Speech-to-Text v2 today ([SpeechToTextTranscriber]); a self-hosted
 * WhisperX/pyannote or another managed provider can swap in behind it without touching callers.
 */
interface Transcriber {
    /**
     * Submit asynchronous diarized transcription of the A/V object at [gcsUri]. [mimeType] (the
     * asset's declared content type, e.g. "video/mp4") guides decoding — AAC-family containers need
     * an explicit decoding config — and [attempt] selects the Nth decoding candidate for that type
     * (0 = best guess; the caller advances it when a poll reports an encoding rejection). [hints]
     * are recognition phrase hints (the subject's name) so ASR doesn't garble the terms claims
     * depend on. Returns the long-running operation name to [poll]. Throws when submission fails or
     * [attempt] exceeds the candidates for this type.
     */
    fun submit(
        subjectId: String,
        assetId: String,
        gcsUri: String,
        mimeType: String?,
        attempt: Int = 0,
        hints: List<String> = emptyList(),
    ): String

    /**
     * Poll a previously submitted operation. Throws only on transport errors (the job remains
     * pollable); a terminal provider-side failure is reported as [TranscriptionPoll.Failed].
     */
    fun poll(operationName: String): TranscriptionPoll

    /**
     * Fetch + normalize a previously stored raw transcript — re-extraction without re-running ASR.
     * Throws when the object is missing or unparseable.
     */
    fun fetchTranscript(transcriptUri: String): Transcript
}

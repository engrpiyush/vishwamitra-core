package ai.vishwakarma.labelling.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `app.*` config tree. Defaults target the vishwakarma-ai-poc POC so
 * the app is runnable out of the box; override per environment via env vars / profile docs.
 */
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val hostedDomain: String = "vishwakarma.ai",
    val gcp: Gcp = Gcp(),
    val tuning: Tuning = Tuning(),
    val auth: Auth = Auth(),
    val comingSoon: ComingSoon = ComingSoon(),
    val intake: Intake = Intake(),
    val stage2: Stage2 = Stage2(),
) {
    data class ComingSoon(
        /** Origins permitted to call the subscribe API cross-site (the page is same-origin). */
        val corsOrigins: List<String> =
            listOf("https://*.vishwakarma.ai", "https://vishwakarma.ai"),
    )

    data class Gcp(
        val projectId: String = "vishwakarma-ai-poc",
        val region: String = "asia-southeast1",
        val firestoreDatabase: String = "vishwakarma-labelling",
        val trainingBucket: String = "",
        val servingBucket: String = "",
        /** Raw Stage 1 intake assets. Blank → IntakeStorage falls back to local disk. */
        val intakeBucket: String = "",
        /**
         * Vertex location for Gemini generateContent. Blank → [region]. Set to "global" to reach
         * models not served regionally (e.g. gemini-2.5-pro is not in asia-southeast1) — trade-off:
         * prompts are then processed outside the region.
         */
        val geminiLocation: String = "",
    )

    data class Tuning(
        val baseModel: String = "qwen/qwen3@qwen3-32b",
        /** Kill-switch: submitting tunes is only allowed when true. */
        val enabled: Boolean = false,
        /** Dev/test: simulate a successful tune instead of calling Vertex (no credit spend). */
        val dryRun: Boolean = false,
        /** Per-example token cap (Gemma 3 27B / Qwen 3 32B). Import validation warns past this. */
        val maxTokensPerExample: Int = 8192,
    )

    data class Auth(
        /** When true (dev profile), OAuth is bypassed and requests run as [devUser]. */
        val devBypass: Boolean = false,
        val devUser: DevUser = DevUser(),
        /** Emails seeded as ADMIN on startup so the first real users can sign in. */
        val bootstrapAdmins: List<String> = emptyList(),
    )

    data class DevUser(
        val email: String = "dev@vishwakarma.ai",
        val role: String = "ADMIN",
    )

    data class Intake(
        /**
         * Assets found larger than this on completion/reconciliation are rejected (marked FAILED).
         */
        val maxAssetSizeBytes: Long = 5L * 1024 * 1024 * 1024,
        /**
         * How long an asset may sit in AWAITING_UPLOAD/FAILED before reconciliation gives up on it.
         */
        val staleUploadHours: Long = 24,
    )

    data class Stage2(
        /** STT batchRecognize writes diarized transcripts here. Blank in dev (dry-run only). */
        val transcriptsBucket: String = "",
        /** STT v2 recognition model; diarization support varies by model and region. */
        val sttModel: String = "long",
        /**
         * STT batchRecognize location (§12.4). Blank → [Gcp.region] (in-region, the pre-§12.4
         * default). Single-region locations (e.g. asia-southeast1) reject diarization, so to enable
         * the multi-speaker lane point this at a multi-region/global endpoint (`us` / `eu` /
         * `global`) that serves `chirp_3` diarization and set [sttModel] to `chirp_3`. `global`
         * uses the bare speech.googleapis.com host (handled in SpeechToTextTranscriber). Trade-off:
         * audio is processed outside the region — accepted per the POC posture, as with
         * gemini-location.
         */
        val sttLocation: String = "",
        val sttLanguage: String = "en-US",
        /** Diarization upper bound (self-interview 1–2 speakers; endorser calls 2+). */
        val maxSpeakers: Int = 6,
        /**
         * Send STT model-adaptation phrase hints (the subject's name — §9.2). The legacy `long`
         * model supports it; the USM-based `chirp_3` model may reject or ignore model adaptation
         * (§12.4 E1 — verify with a throwaway batchRecognize). Set false for a `chirp_3` deployment
         * if the probe shows adaptation is rejected; claim text is still canonical-name-normalized
         * at extraction, so the loss is transcript-cosmetic.
         */
        val sttPhraseHints: Boolean = true,
        /**
         * §12.4 speaker-attribution confidence gate (0..1). When the LLM's confidence in "which
         * diarized speaker is the subject" is at least this, extraction runs automatically; below
         * it, a multi-speaker job parks in AWAITING_SPEAKER_SELECTION for the operator to tag self.
         * Explicit self-introductions score high; inferred guesses score low, so the default of 0.9
         * gates everything without a clear self-ID.
         */
        val attributionConfidenceThreshold: Double = 0.9,
        /**
         * Assumed source parameters when a container needs explicitDecodingConfig (AAC family — see
         * SpeechToTextTranscriber). 48kHz stereo matches phone/screen recorders; override per
         * environment if a different capture pipeline dominates.
         */
        val explicitSampleRateHertz: Int = 48000,
        val explicitChannelCount: Int = 2,
        /**
         * Probe the real sample rate / channel count from an AAC container's header (§12.7) and use
         * them for explicitDecodingConfig, falling back to the explicit* defaults above when the
         * probe can't read them. Set false to always use the fixed defaults.
         */
        val probeAudioParams: Boolean = true,
        /** Dev/test: return a canned diarized transcript instead of calling Speech-to-Text. */
        val dryRun: Boolean = false,
        /**
         * IMAGE/DOCUMENT lane: refuse files larger than this. Bytes ride inline (base64, +33%)
         * inside the Gemini generateContent request, which caps around 20MB total — 14MB raw leaves
         * headroom for the prompt.
         */
        val maxDocumentBytes: Long = 14L * 1024 * 1024,
        /**
         * A job that entered EXTRACTING longer ago than this is treated as crash-stranded and
         * reclaimed to FAILED on the next poll (§12.7 hardening). MUST exceed the Cloud Run request
         * timeout: extraction runs synchronously inside one request, so anything older can only be
         * a crash, never a live run. Default 15m clears the 300s default request cap with wide
         * margin.
         */
        val extractingTimeout: Duration = Duration.ofMinutes(15),
    )
}

package ai.vishwakarma.labelling.config

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
        val sttLanguage: String = "en-US",
        /** Diarization upper bound (self-interview 1–2 speakers; endorser calls 2+). */
        val maxSpeakers: Int = 6,
        /**
         * Assumed source parameters when a container needs explicitDecodingConfig (AAC family — see
         * SpeechToTextTranscriber). 48kHz stereo matches phone/screen recorders; override per
         * environment if a different capture pipeline dominates.
         */
        val explicitSampleRateHertz: Int = 48000,
        val explicitChannelCount: Int = 2,
        /** Dev/test: return a canned diarized transcript instead of calling Speech-to-Text. */
        val dryRun: Boolean = false,
    )
}

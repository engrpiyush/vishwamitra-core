package ai.vishwakarma.labelling.config

import ai.vishwakarma.labelling.stage3.gatekeeper.JudgeMode
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `app.*` config tree. Defaults target the vishwakarma-ai-poc POC so
 * the app is runnable out of the box; override per environment via env vars / profile docs.
 */
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val hostedDomain: String = "vishwakarma.ai",
    val product: Product = Product(),
    val gcp: Gcp = Gcp(),
    val tuning: Tuning = Tuning(),
    val serving: Serving = Serving(),
    val gatekeeper: Gatekeeper = Gatekeeper(),
    val auth: Auth = Auth(),
    val intake: Intake = Intake(),
    val stage2: Stage2 = Stage2(),
    val stage3: Stage3 = Stage3(),
    val stage4: Stage4 = Stage4(),
) {
    /**
     * The vishwakarma.ai product face (LLD §17.1): subject hosts live at `<handle>.{baseDomain}`
     * while the operator app stays on [operatorDomain]; host-first routing splits the two worlds
     * (VA-30). Product mail rides the same block (VA-41).
     */
    data class Product(
        /** Subject hosts are `<handle>.{base-domain}` (dev: `localhost` → `dev.localhost:8080`). */
        val baseDomain: String = "vishwakarma.ai",
        /** The operator app's own host — never treated as a subject host. */
        val operatorDomain: String = "labelling.vishwakarma.ai",
        /** Max UNREDEEMED guest tokens per subject (F7 — revoke one to free a slot). */
        val tokenCap: Int = 10,
        /** Guest capability-session TTL, started at redemption (LLD §6.3). */
        val guestSessionTtl: Duration = Duration.ofMinutes(60),
        /** Wall redemption attempts allowed per (subject, IP) per minute (LLD §6.3). */
        val wallAttemptsPerMinute: Int = 5,
        /** Lockout applied when the per-minute limit trips (LLD §6.3). */
        val wallLockout: Duration = Duration.ofMinutes(15),
        /**
         * HMAC pepper for token hashing (env `ADVOCATE_TOKEN_PEPPER` ← Secret Manager, LLD §3.3).
         * Never in Firestore: a leaked export is useless without it (§6.2). Blank outside dev =
         * token generation/redemption fail closed; the dev profile pins a fixed non-secret value.
         */
        @get:JsonIgnore val tokenPepper: String = "",
        /**
         * Transcript-row cap per chat session (LLD §7.5) — user AND advocate rows both count (each
         * exchange adds 2), so the default allows 15 exchanges per session.
         */
        val maxMessagesPerSession: Int = 30,
        /** Minimum gap between sends within one session (LLD §7.5 pace guard; 429 under it). */
        val chatMinInterval: Duration = Duration.ofSeconds(3),
        /**
         * F11 (LLD §9.2): claims whose `favorability` sits below this draw one clarification
         * question when review locks. The favorability marker is the §12.6 extractor's.
         */
        val f11Threshold: Double = 0.4,
        /**
         * App-level daily ceiling across ALL outbound product mail (LLD §11.1). At the ceiling
         * sends are skipped loudly (WARN + per-feature mark) — email never blocks a flow.
         */
        val mailDailyCap: Int = 200,
        /** From-address for product mail (D1: plain Gmail SMTP with an app password). */
        val mailFrom: String = "no-reply@vishwakarma.ai",
        /**
         * Expected OIDC audience on `/internal` calls (LLD §3.4 — Cloud Scheduler jobs run as a
         * dedicated SA; the app verifies the bearer token itself). Blank outside dev = the
         * endpoints fail closed.
         */
        val internalAudience: String = "",
        /**
         * Service-account email allowed on `/internal` (the scheduler SA). Blank = any
         * Google-signed identity with the right audience.
         */
        val internalInvoker: String = "",
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
        /**
         * Retry ladder (ms, comma-separated) for Vertex/Gemini REST 429 + 5xx responses — quota
         * windows are per-minute, so delays should sum past ~60s to reach the next window.
         * Empty/undefined = backoff DISABLED: the first failure propagates immediately (2026-07-11
         * operator decision).
         */
        val vertexBackoffMs: List<Long> = emptyList(),
        /**
         * Which door generateContent goes through (judge, both extractors, drafting — all of
         * [ai.vishwakarma.labelling.drafting.GeminiDrafting]'s consumers follow it): `vertex` (ADC,
         * DSQ shared pool — no hard cap, occasional 429 weather) or `gemini-api` (the Developer API
         * — fixed paid-tier quotas, needs [geminiApiKey], processed globally). 2026-07-11: the
         * secondary door exists to A/B those limits.
         */
        val geminiTransport: String = "vertex",
        /**
         * API key for the `gemini-api` transport (env `GEMINI_API_KEY` — the same env the Stage 3
         * embedding transport reads; blank when unused). JsonIgnore keeps it out of every
         * serialization.
         */
        @get:JsonIgnore val geminiApiKey: String = "",
    )

    data class Tuning(
        val baseModel: String = "qwen/qwen3@qwen3-32b",
        /** Kill-switch: submitting tunes is only allowed when true. */
        val enabled: Boolean = false,
        /** Dev/test: simulate a successful tune instead of calling Vertex (no credit spend). */
        val dryRun: Boolean = false,
        /** Per-example token cap (Gemma 3 27B / Qwen 3 32B). Import validation warns past this. */
        val maxTokensPerExample: Int = 8192,
        /**
         * Vertex location for `tuningJobs`, decoupled from [Gcp.region] because the managed-OSS
         * catalog is regional: the 2B-class Qwen entries (qwen3-1.7b / qwen3-0.6b / qwen3.5-2b)
         * resolve only in us-central1 (verified 2026-07-13), while the home region carries 4B+.
         * Blank → [Gcp.region] (the VA-59-verified asia-southeast1 posture, byte-for-byte).
         */
        val region: String = "",
    )

    /**
     * Serving control plane (VA-67 / advocate serving): deploy a tuned model version to a Vertex
     * endpoint and tear it down, behind the pluggable `serving.ServingBackend` seam (Vertex now;
     * AWS later). Defaults are the VA-74-probed V100 pins. All fields override via env; the
     * endpoint bills only while a model is deployed.
     */
    data class Serving(
        /** Kill-switch: the serve/teardown controls are only offered when true. */
        val enabled: Boolean = false,
        /** Dev/test: simulate deploy/teardown (instant LIVE/NONE) instead of calling Vertex. */
        val dryRun: Boolean = false,
        /** Backend id — `vertex` (only impl today) or a future `aws`. */
        val backend: String = "vertex",
        /** Endpoint region (V100 custom-serving quota is us-central1/us-west1/europe-west4). */
        val region: String = "us-central1",
        /** The shared Vertex endpoint id models deploy onto (VA-74 probe: 328332313995771904). */
        val endpointId: String = "",
        /** vLLM-on-Volta serving image (VA-74 pin; v0.20+ dropped SM70). */
        val image: String = "",
        /**
         * Serving-region staging bucket (VA-75): `registerAdvocate` copies the tuned checkpoint
         * here once so every window deploy fetches weights in-region instead of paying cross-region
         * egress per cold start. Blank (dev) = no copy, serve the source URI as-is.
         */
        val stagingBucket: String = "",
        /**
         * A window stuck PROVISIONING longer than this flips to DEPLOY_FAILED (operator-visible).
         * Measured cold deploy is 25–35 min (VA-74), so the default leaves real headroom; a deploy
         * that completes after the flip is caught by the sweep's orphan reconcile.
         */
        val deployTimeout: Duration = Duration.ofMinutes(60),
        val machineType: String = "n1-standard-8",
        val acceleratorType: String = "NVIDIA_TESLA_V100",
        val acceleratorCount: Int = 1,
        /**
         * §14.1 window $-estimate dial (VA-68): the all-in hourly price of one serving replica
         * (machine + accelerator — Vertex prediction, n1-standard-8 + 1×V100 in us-central1 ≈
         * $0.42 + $2.48). The startWindow estimate is preset hours × this; a dial, not a bill.
         */
        val hourlyUsd: Double = 2.90,
        /** Container args — the VA-74 float16/Volta pins. */
        val servedModelArgs: List<String> =
            listOf(
                "--served-model-name=advocate",
                "--dtype=float16",
                "--max-model-len=8192",
                "--gpu-memory-utilization=0.90",
            ),
        /**
         * How the §14 behavioral eval reaches a tuned checkpoint (VA-67): `endpoint` = a
         * short-lived deploy onto the shared Vertex endpoint (torn down unconditionally when
         * probing ends); `vllm` = a dev vLLM box serving the checkpoint per the ServeCommand
         * recipe, reached at [evalVllmBaseUrl].
         */
        val evalTransport: String = "endpoint",
        /** Base URL of the dev vLLM serve (e.g. `http://10.0.0.5:8000`); required for `vllm`. */
        val evalVllmBaseUrl: String = "",
    )

    /**
     * The Lakshmana gatekeeper transport (VA-106; Gatekeeper LLD §5, §14) — the Pub/Sub topic this
     * app publishes gate-transition requests to, and the dials around that one call.
     *
     * **Restart-bound by design.** The live-editable config store covers only the STAGE1..STAGE4
     * blocks ([StageConfigCatalog]), so everything here is env/yml and takes effect on deploy — the
     * same posture as [Tuning] and [Serving]. Note the deliberate asymmetry that creates:
     * `app.stage3.judge-mode` IS live-editable, while the transport underneath it is not. Flipping
     * the mode to GATEKEEPER on a deployment whose publisher is still dry-run is therefore
     * possible, and the no-op publisher logs a WARN naming both flags rather than failing quietly.
     */
    data class Gatekeeper(
        /**
         * Dev/test: log the payload instead of publishing it. The dev profile sets this true, and a
         * blank [topic] is an independent hard no-op — a misconfigured box cannot publish even with
         * the flag flipped off.
         */
        val dryRun: Boolean = false,
        /** Pub/Sub topic id (LLD §5: `gatekeeper-requests`). Blank = publishing disabled. */
        val topic: String = "",
        /**
         * Project hosting the topic. Blank → [Gcp.projectId]; both services share one project
         * today, so the override exists only so they need not.
         */
        val projectId: String = "",
        /** Bounded publish retry (gax `RetrySettings`) — a terminal failure is operator-visible. */
        val publishTimeout: Duration = Duration.ofSeconds(20),
        val publishMaxAttempts: Int = 5,
        val publishInitialBackoff: Duration = Duration.ofMillis(500),
        val publishMaxBackoff: Duration = Duration.ofSeconds(10),
        /**
         * How long a GATEKEEPER-mode JUDGE phase waits for the cascade before the run is failed as
         * stuck. Generous on purpose: §16 puts the reference subject at 25–35 min and a 1,000-claim
         * intake at 1.5–2.5 h, and the phase's own `phase-timeout` cannot govern here — the JUDGE
         * tick legitimately makes no local progress while the cascade works.
         */
        val cascadeTimeout: Duration = Duration.ofHours(6),
    )

    data class Auth(
        /** When true (dev profile), OAuth is bypassed and requests run as [devUser]. */
        val devBypass: Boolean = false,
        val devUser: DevUser = DevUser(),
        /**
         * Seed the dev-profile fixtures (dev subject + SUBJECT login + LIVE advocate) on startup.
         * Requires [devBypass] too. Default FALSE so a restored emulator snapshot stays
         * authoritative — flip `DEV_SEED=true` for a one-time fresh seed, snapshot it with
         * `scripts/firestore-emulator-backup.sh`, then run with it off again.
         */
        val devSeed: Boolean = false,
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
        /**
         * §12.6 review gate (0..1). A non-sensitive claim whose LLM-emitted [Claim.favorability] is
         * below this is surfaced for operator review (approve-as-is or attach a sidecar
         * justification); at/above it a STATED claim auto-approves. INFERRED claims are always
         * reviewed regardless of favorability, and a null favorability is always review-required.
         * 0.5 is the neutral midpoint, so the default reviews everything that reads as unfavorable.
         */
        val favorabilityThreshold: Double = 0.5,
    )

    /**
     * Stage 3 (Claims → Authenticity graph/scores) — the full LLD §8.2 config table. Every value
     * here is frozen into a run's `paramsSnapshot` at submit (the reproducibility contract), so
     * scores are always attributable to the exact knobs that produced them.
     */
    data class Stage3(
        /**
         * Bolt URI. Dev default = local Docker (`compose.yaml`); prod = AuraDB
         * (`neo4j+s://<dbid>.databases.neo4j.io`) via the NEO4J_URI env Terraform wires from the
         * operator-created secret.
         */
        val neo4jUri: String = "bolt://localhost:7687",
        val neo4jUser: String = "neo4j",
        /**
         * Via env/Secret Manager in prod; the dev default matches compose.yaml's NEO4J_AUTH.
         * JsonIgnore keeps it out of every Jackson serialization of this class — most importantly
         * the run `paramsSnapshot`, which REVIEWER-visible run reads return verbatim.
         */
        @get:JsonIgnore val neo4jPassword: String = "vishwamitra-dev",
        /** Target database (Enterprise multi-DB; Aura Free + Community have exactly one). */
        val neo4jDatabase: String = "neo4j",
        /**
         * AuraDB's load balancer silently drops connections idled for a few minutes, which surfaces
         * as SessionExpired/ServiceUnavailable on the next use of a stale pooled connection. Pooled
         * connections idle longer than this are liveness-tested (and replaced when dead) before
         * reuse — keep it comfortably under the idle-kill horizon.
         */
        val connectionLivenessCheckTimeout: Duration = Duration.ofMinutes(2),
        /** Hard cap on any pooled connection's age — forces periodic refresh below LB horizons. */
        val maxConnectionLifetime: Duration = Duration.ofMinutes(30),
        /** Managed-transaction retry window (SessionExpired / ServiceUnavailable / transient). */
        val maxTransactionRetryTime: Duration = Duration.ofSeconds(30),
        /**
         * A run phase that keeps erroring without a single successful step for longer than this is
         * reclaimed to FAILED on the next poll so operator Retry can resume it (the Stage 2 §12.7
         * idiom, generalized: phaseSince tracks the last successful advance).
         */
        val phaseTimeout: Duration = Duration.ofMinutes(15),
        /** Vertex embedding model id (LLD §11.4). */
        val embeddingModel: String = "gemini-embedding-001",
        /**
         * Full-fidelity default (quality over pennies; Neo4j vector indexes cap at 4096). MRL
         * truncation to 1536/768 remains a knob — truncated outputs are re-normalized client-side.
         */
        val embeddingDimensions: Int = 3072,
        /**
         * Vertex location for the embedding endpoint. Blank → [Gcp.region]. Unlike Gemini
         * generateContent, embedding models are served regionally — override (e.g. us-central1) if
         * the home region lacks [embeddingModel].
         */
        val embeddingLocation: String = "",
        /** Claims embedded per poll tick (bounded work per request; VA-13). */
        val embedBatchPerPoll: Int = 32,
        /**
         * Claims mention-resolved per poll tick — one Gemini extraction call per tick (the §16
         * sizing: ~20 claims/call), then per-mention resolution against the global canon (VA-11).
         */
        val entityBatchPerPoll: Int = 20,
        /** PRUNED (blocking + cascade) or EXHAUSTIVE (calibration benchmark, eval database). */
        val matchingMode: String = "PRUNED",
        /**
         * §11.5's `window(type)` for EPISODEs, in years: the structural blocking arm pairs
         * same-type episodes dated within it, and rung 4 discards episode pairs beyond it that
         * share no discriminative entity.
         */
        val episodeWindowYears: Double = 5.0,
        /** Vector blocking: neighbours fetched per claim. */
        val knnK: Int = 20,
        /** Similarity discard floor (τ_low). */
        val simFloor: Double = 0.60,
        /** Auto-REPEATS threshold (τ_high). */
        val simAutoRepeat: Double = 0.93,
        /** Embedding cosine above which a mention merges into an existing same-type entity. */
        val entityMergeThreshold: Double = 0.85,
        /** Entities mentioned by more than (1 − floor) of a subject's claims are stopword-like. */
        val entityIdfFloor: Double = 0.25,
        /**
         * VA-77 B3: a co-mention-only pair whose best (lowest-DF) shared entity is still mentioned
         * by more than this share of the subject's claims is hub-linked (employer/university-class)
         * and loses the co-mention bypass of the rung-1 similarity floor.
         */
        val coMentionHubShare: Double = 0.30,
        /**
         * VA-77 B2: per-claim judge-candidate cap — each claim keeps its top-N queued candidates by
         * embedding similarity; a pair queues while either endpoint keeps it. Human-asserted and
         * STRUCTURAL-arm pairs (the CONTRADICTS lane) ride a guaranteed quota outside the ranking;
         * 0 = uncapped, PRUNED mode only. 80 = the owner-signed 2026-07-19 operating value: the
         * measured 12,208-pair run replays at 98.5% CORROBORATES-majority / 99.4% contributing-pair
         * retention with zero published fact edges lost (scripts/stage3_dials_replay.py).
         */
        val judgeCandidatesPerClaim: Int = 80,
        /** Judge samples per pair (self-consistency ensemble, LLD §11.6). */
        val ensembleK: Int = 5,
        val ensembleTemperature: Double = 0.7,
        /** Claim-pair presentation order across the k samples (position-bias control). */
        val ensembleOrderings: String = "ALTERNATE",
        /** Aggregated edge confidence below this → NEUTRAL (no edge). */
        val judgeConfidenceFloor: Double = 0.55,
        /** Work budget per poll tick. */
        val judgePairsPerPoll: Int = 40,
        /** Pairs per Gemini call (the ensemble runs k calls per batch). */
        val judgeBatchSize: Int = 8,
        /**
         * VA-77 B1: thinking-token cap per judge sampler call (was the hardcoded 4096 — thinking is
         * ~60% of the judge bill). Env `STAGE3_JUDGE_THINKING_BUDGET`.
         */
        val judgeThinkingBudget: Int = 512,
        /**
         * VA-106: which judge decides — `LLM` | `GATEKEEPER` | `SHADOW`
         * ([ai.vishwakarma.labelling.stage3.gatekeeper.JudgeMode]). Frozen into the run's
         * `paramsSnapshot` at submit and read back from there for the rest of the run, so a
         * mid-flight edit can never make two judges act on one run. Rollback from the cascade is
         * this one value going back to `LLM` — the ensemble path is untouched by the integration.
         * Typed as String because live-config rebinding produces Strings for STRING-kind fields;
         * [judgeModeOrDefault] is the typed accessor.
         */
        val judgeMode: String = "LLM",
        /**
         * Cap on concurrent sampler calls within a tick (2026-07-11): the full chunk×k fan-out
         * (~25) demanded more than the project's DSQ share of the judge model and 429-starved the
         * ladder. Fewer lanes with natural queuing beat a burst the provider keeps refusing.
         */
        val judgeParallelism: Int = 8,
        /** Claim-type × entity patterns treated as exclusive STATE slots that sequence (§11.7). */
        val stateSlotTypes: List<String> =
            listOf("EMPLOYER", "ROLE", "RESIDENCE", "EDUCATION_ENROLLMENT"),
        /**
         * Per-claim-type evidence half-life (years) for recency decay; absent types don't decay.
         */
        val volatileHalfLifeYears: Map<String, Double> = mapOf("SKILL" to 5.0),
        /** Fixed-point controls (LLD §11.8). */
        val maxIterations: Int = 20,
        val epsilon: Double = 0.005,
        val damping: Double = 0.5,
        /** Log-odds weights at full confidence; contradiction > corroboration is deliberate. */
        val corroborationWeight: Double = 0.8,
        val contradictionWeight: Double = 1.2,
        /** Geometric discount (λ) for additional voices within a dependence group. */
        val dependenceDamping: Double = 0.4,
        /** Fraction (μ) of a contradiction penalty removed by a judged-relevant explanation. */
        val explanationMitigation: Double = 0.6,
        /** Update multiplier (ρ) for anchored (DOCUMENTARY) facts. */
        val anchorPlasticity: Double = 0.2,
        /** Log-odds prior bonus (β) for unfavorable SELF claims (statement against interest). */
        val againstInterestBonus: Double = 0.4,
        /** Log-odds prior penalty (δ, negative) for claimBasis = INFERRED. */
        val inferredPenalty: Double = -0.5,
        /** Cap for favorable SELF-only facts with zero independent corroboration. */
        val selfPraiseCeiling: Double = 0.65,
        /** Pseudo-count (m) shrinking attestor trust toward its prior. */
        val trustShrinkage: Int = 5,
        /** Score → refreshed tier bands. */
        val tierHigh: Double = 0.75,
        val tierMedium: Double = 0.45,
        /**
         * Subject Authenticity Index (Stage 3.5 LLD §3): the subject-level aggregate. Mass
         * saturation midpoint m0 — `sat(m) = m/(m+m0)`; higher = sterner on thin evidence.
         */
        val aggMassMidpoint: Double = 2.0,
        /** λ_D — max sag from shallow average evidence mass. */
        val aggDepthWeight: Double = 0.30,
        /** λ_I — max sag from the self-only fact share. */
        val aggSelfOnlyPenalty: Double = 0.30,
        /** λ_V — max sag from a monoculture of sources. */
        val aggDiversityWeight: Double = 0.15,
        /** λ_C — max drag from surviving contradictions. */
        val aggContradictionWeight: Double = 0.25,
        /** c0 — residual conflict mass at which the drag reaches half of λ_C. */
        val aggContradictionMidpoint: Double = 2.0,
        /** a0 — independent (non-SUBJECT) attestors for full diversity credit. */
        val aggAttestorTarget: Int = 4,
        /** d0 — documentary/anchored fact fraction for full diversity credit. */
        val aggDocCoverageTarget: Double = 0.25,
        /** Diversity mix (must sum to 1): independent attestors / attestor kinds / doc coverage. */
        val aggDiversityAttestorShare: Double = 0.5,
        val aggDiversityKindShare: Double = 0.3,
        val aggDiversityDocShare: Double = 0.2,
        /** SAI grade-band cutoffs (Stage 3.5 LLD §3.4); ~90 is the practical ceiling by design. */
        val aggBandStrong: Double = 0.80,
        val aggBandGood: Double = 0.65,
        val aggBandModerate: Double = 0.45,
        val aggBandWeak: Double = 0.25,
        /** Reserved v2+ flag (§9.4): read cross-subject evidence edges in scoring. Off in v1. */
        val crossSubjectEvidence: Boolean = false,
        /** The Q6 gate: ledger write-back only from AWAITING_REVIEW via explicit publish. */
        val publishRequiresReview: Boolean = true,
        /** Dev/test: deterministic pseudo-embeddings + canned judge; Neo4j itself stays real. */
        val dryRun: Boolean = false,
        /**
         * Per-leg overrides (the Stage 2 mix-and-match idiom, LLD §11.12): null follows [dryRun].
         * The gated live smoke sets `dry-run=true` + `dry-run-judge=false` — pseudo embeddings and
         * canned extraction with the REAL Gemini judge.
         */
        val dryRunEmbeddings: Boolean? = null,
        val dryRunExtraction: Boolean? = null,
        val dryRunJudge: Boolean? = null,
        /**
         * Which door the live embeddings go through: `vertex` (ADC, in-region, but this project's
         * gemini-embedding quota is 5 RPM everywhere) or `gemini-api` (the Developer API at
         * generativelanguage.googleapis.com — same model, same 3072-dim space, paid-tier 3000 RPM,
         * API-key auth, processed globally). Same [versionStamp] either way: vectors are
         * interchangeable and flipping transports never re-embeds (2026-07-11, quota workaround).
         */
        val embeddingTransport: String = "vertex",
        /**
         * API key for the `gemini-api` transport (env `GEMINI_API_KEY`; blank when unused).
         * JsonIgnore keeps it out of paramsSnapshot and every other serialization.
         */
        @get:JsonIgnore val geminiApiKey: String = "",
    ) {
        val exhaustiveMatching: Boolean
            get() = matchingMode.equals("EXHAUSTIVE", ignoreCase = true)

        /**
         * [judgeMode] parsed, falling back to LLM on anything unrecognised. Lenient in the same
         * direction as [exhaustiveMatching]: an unreadable value keeps the legacy ensemble running
         * rather than silently handing the verdict pen to a service that may not be deployed.
         */
        val judgeModeOrDefault: JudgeMode
            get() = JudgeMode.fromOrNull(judgeMode) ?: JudgeMode.LLM

        /** Effective per-leg dry-run switches — explicit override wins, else the master flag. */
        val embeddingsDryRun: Boolean
            get() = dryRunEmbeddings ?: dryRun

        val extractionDryRun: Boolean
            get() = dryRunExtraction ?: dryRun

        val judgeDryRun: Boolean
            get() = dryRunJudge ?: dryRun
    }

    /**
     * Stage 4 (Conversation Synthesis & Tuning) — the LLD §16 config table. As with Stage 3, every
     * value is frozen into a run's `paramsSnapshot` at submit, so a generated dataset is always
     * attributable to the exact knobs that produced it.
     */
    data class Stage4(
        /** Kill-switch for the Stage 4 surface (persona writes + run submits). */
        val enabled: Boolean = true,
        /** Dev/test: offline generator + judge doubles over the Stage 3 dry-run corpus (VA-62). */
        val dryRun: Boolean = false,
        /**
         * SubjectProfile surface (profile LLD §8): profile writes and the `{{locale}}` /
         * `{{knowledge_as_of}}` injection. Off ⇒ the profile resolves blank and every prompt is
         * byte-for-byte what it is today. Defaults off; the dev profile turns it on.
         */
        val profileEnabled: Boolean = false,
        /**
         * VA-62 scripted-judge verdict distribution (dry-run only): fraction of plans judged FAIL
         * and BORDERLINE respectively (deterministic per planId); the rest judge PASS.
         */
        val dryRunJudgeFailRate: Double = 0.10,
        val dryRunJudgeBorderlineRate: Double = 0.15,
        /** Dataset-category mix weights (S4-D7 / QA-3) — fluid dials, normalized before use. */
        val mix: Mix = Mix(),
        /** PLAN fan-out cap: conversations planned per claim across all categories (§9.2). */
        val maxConversationsPerClaim: Int = 6,
        /** MinHash/Jaccard similarity above which two planned questions are duplicates (§9.2). */
        val dedupeJaccardThreshold: Double = 0.85,
        /** GENERATE work budget per poll tick (bounded work per request, the Stage 3 idiom). */
        val generateBatchPerPoll: Int = 8,
        /** JUDGE work budget per poll tick. */
        val judgeBatchPerPoll: Int = 8,
        /** Judge samples per axis (self-consistency ensemble, §11 — Stage 3's §11.6 idiom). */
        val ensembleK: Int = 3,
        /**
         * The QD-6 dial (2026-07-13): false skips the §11 LLM judge entirely — generated examples
         * land in the human queue as SUBMITTED, unsorted and unverdicted. The product framing is
         * judge-as-paid-QA-add-on; skipping also stops the `stage4_judgments` distillation set from
         * accruing, and v1.1's sampled human review needs it back on.
         */
        val judgeEnabled: Boolean = true,
        /**
         * Fraction of PASS-judged conversations routed to the human queue. v1 IGNORES this and
         * enforces 1.0 — 100% human review (QA-4); the dial exists for the designed v1.1
         * relaxation, gated on measured judge–human agreement.
         */
        val reviewSampleRate: Double = 1.0,
        /** DPO pair generation (§12) — specced, ships dark until the first DPO tune (S4-D4). */
        val dpoEnabled: Boolean = false,
        /** Per-category slice held out of export for the post-tune behavioral eval (§14). */
        val evalHoldoutFraction: Double = 0.10,
        /** Advisory post-tune bar: overall expected-behavior match rate (warn, never block). */
        val evalBehaviorBar: Double = 0.90,
        /** Stuck-phase reclaim horizon (phaseSince clock — the Stage 2 §12.7 idiom). */
        val phaseTimeout: Duration = Duration.ofMinutes(15),
    ) {
        /**
         * The five S4-D7 category weights (defaults 25/35/25/10/5 — QA-3, rebalanced QD-4
         * 2026-07-13: situational-led with the QA anchor retained). Any non-negative dial values
         * are accepted. Since QD-5 the planner steers only the fact-driven trio (qa / situational /
         * multiClaim, normalized among themselves); [negative] and [meta] plan their full probe
         * banks and these two dials are recorded in the snapshot but not read.
         */
        data class Mix(
            val qa: Double = 0.25,
            val situational: Double = 0.35,
            val multiClaim: Double = 0.25,
            val negative: Double = 0.10,
            val meta: Double = 0.05,
        ) {
            /**
             * Weights rescaled to sum 1; an all-zero (or negative-sum) mix falls back to defaults.
             */
            fun normalized(): Mix {
                val sum = qa + situational + multiClaim + negative + meta
                if (sum <= 0.0) return Mix()
                return Mix(
                    qa / sum,
                    situational / sum,
                    multiClaim / sum,
                    negative / sum,
                    meta / sum
                )
            }
        }
    }
}

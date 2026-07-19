package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ConfigField
import ai.vishwakarma.labelling.domain.ConfigFieldKind.BOOLEAN
import ai.vishwakarma.labelling.domain.ConfigFieldKind.DOUBLE
import ai.vishwakarma.labelling.domain.ConfigFieldKind.DOUBLE_MAP
import ai.vishwakarma.labelling.domain.ConfigFieldKind.DURATION
import ai.vishwakarma.labelling.domain.ConfigFieldKind.INT
import ai.vishwakarma.labelling.domain.ConfigFieldKind.LONG
import ai.vishwakarma.labelling.domain.ConfigFieldKind.STRING
import ai.vishwakarma.labelling.domain.ConfigFieldKind.STRING_LIST
import ai.vishwakarma.labelling.domain.StageKey
import ai.vishwakarma.labelling.stage3.gatekeeper.JudgeMode

/**
 * The per-stage Configuration-tab field catalog (LLD §14A.4(2) / §14A.5): each entry mirrors one
 * `app.stageN.*` (or `app.intake.*`) property. Field names MUST match the AppProperties constructor
 * parameters — the merge in [StageConfigService] resolves them reflectively.
 *
 * Read-only rules: values consumed at STARTUP (bean selection, connection pools) can't go live
 * without a restart, and dry-run posture flags stay env-managed — both render locked with the
 * reason as tooltip. Secrets are masked and never stored (D1 posture).
 */
object StageConfigCatalog {

    private const val RESTART = "Bound at startup — set via env/yml and restart to change."
    private const val POSTURE = "Dev/prod posture flag, bean-wired at startup — env-managed."
    private const val SECRET = "Secret value — Secret Manager / env only, never stored or shown."

    fun fields(stage: StageKey): List<ConfigField> =
        when (stage) {
            StageKey.STAGE1 -> stage1
            StageKey.STAGE2 -> stage2
            StageKey.STAGE3 -> stage3
            StageKey.STAGE4 -> stage4
        }

    private val stage1 =
        listOf(
            ConfigField(
                "maxAssetSizeBytes",
                "Max asset size (bytes)",
                LONG,
                "Intake limits",
                "Assets larger than this are rejected at registration/completion (marked FAILED).",
            ),
            ConfigField(
                "staleUploadHours",
                "Stale upload horizon (hours)",
                LONG,
                "Intake limits",
                "How long an asset may sit AWAITING_UPLOAD/FAILED before reconciliation gives up.",
            ),
        )

    private val stage2 =
        listOf(
            ConfigField(
                "transcriptsBucket",
                "Transcripts bucket",
                STRING,
                "Speech-to-text",
                "GCS bucket batchRecognize writes diarized transcripts to.",
                editable = false,
                readOnlyReason = RESTART,
            ),
            ConfigField(
                "sttModel",
                "STT model",
                STRING,
                "Speech-to-text",
                "Speech-to-Text v2 model. Diarization needs chirp_3 (multi-region); `long` is the single-region default.",
            ),
            ConfigField(
                "sttLocation",
                "STT location",
                STRING,
                "Speech-to-text",
                "batchRecognize location. Blank → home region. `us`/`eu`/`global` unlock chirp_3 diarization (audio leaves the region).",
            ),
            ConfigField(
                "sttLanguage",
                "STT language",
                STRING,
                "Speech-to-text",
                "Recognition language code (e.g. en-US).",
            ),
            ConfigField(
                "maxSpeakers",
                "Max speakers",
                INT,
                "Speech-to-text",
                "Diarization upper bound (self-interview 1–2; endorser calls 2+).",
            ),
            ConfigField(
                "sttPhraseHints",
                "Phrase hints",
                BOOLEAN,
                "Speech-to-text",
                "Send the subject's name as model-adaptation hints. `long` supports it; chirp_3 may reject adaptation.",
            ),
            ConfigField(
                "probeAudioParams",
                "Probe audio params",
                BOOLEAN,
                "Audio decoding",
                "Read real sample rate / channels from AAC headers; false always uses the fixed defaults.",
            ),
            ConfigField(
                "explicitSampleRateHertz",
                "Fallback sample rate (Hz)",
                INT,
                "Audio decoding",
                "Assumed rate when a container needs explicitDecodingConfig and probing fails.",
            ),
            ConfigField(
                "explicitChannelCount",
                "Fallback channel count",
                INT,
                "Audio decoding",
                "Assumed channels when probing fails (48kHz stereo matches phone recorders).",
            ),
            ConfigField(
                "attributionConfidenceThreshold",
                "Attribution confidence gate",
                DOUBLE,
                "Attribution & review gates",
                "0..1 — below it a multi-speaker job parks in AWAITING_SPEAKER_SELECTION for the operator.",
            ),
            ConfigField(
                "favorabilityThreshold",
                "Favorability review gate",
                DOUBLE,
                "Attribution & review gates",
                "0..1 — STATED claims below it surface for operator review; at/above auto-approve.",
            ),
            ConfigField(
                "maxDocumentBytes",
                "Max document size (bytes)",
                LONG,
                "Documents & lifecycle",
                "IMAGE/DOCUMENT lane cap — bytes ride inline base64 in generateContent (~20MB total).",
            ),
            ConfigField(
                "extractingTimeout",
                "Extracting reclaim timeout",
                DURATION,
                "Documents & lifecycle",
                "EXTRACTING older than this is crash-stranded → FAILED on next poll. Must exceed the request timeout.",
            ),
            ConfigField(
                "dryRun",
                "Dry-run",
                BOOLEAN,
                "Run posture",
                "Canned transcript instead of Speech-to-Text.",
                editable = false,
                readOnlyReason = POSTURE,
            ),
        )

    private val stage3 =
        listOf(
            // -- Graph connection (restart-bound: the driver pools at startup) --
            ConfigField(
                "neo4jUri",
                "Neo4j URI",
                STRING,
                "Graph connection",
                "Bolt URI (AuraDB in prod).",
                editable = false,
                readOnlyReason = RESTART,
            ),
            ConfigField(
                "neo4jUser",
                "Neo4j user",
                STRING,
                "Graph connection",
                "Driver principal.",
                editable = false,
                readOnlyReason = RESTART,
            ),
            ConfigField(
                "neo4jPassword",
                "Neo4j password",
                STRING,
                "Graph connection",
                "Secret-Manager/env only.",
                editable = false,
                secret = true,
                readOnlyReason = SECRET,
            ),
            ConfigField(
                "neo4jDatabase",
                "Neo4j database",
                STRING,
                "Graph connection",
                "Target database (Aura Free/Community have exactly one).",
                editable = false,
                readOnlyReason = RESTART,
            ),
            ConfigField(
                "connectionLivenessCheckTimeout",
                "Connection liveness check",
                DURATION,
                "Graph connection",
                "Idle-pooled connections older than this are liveness-tested before reuse (AuraDB LB idle-kill).",
                editable = false,
                readOnlyReason = RESTART,
            ),
            ConfigField(
                "maxConnectionLifetime",
                "Max connection lifetime",
                DURATION,
                "Graph connection",
                "Hard cap on pooled connection age.",
                editable = false,
                readOnlyReason = RESTART,
            ),
            ConfigField(
                "maxTransactionRetryTime",
                "Transaction retry window",
                DURATION,
                "Graph connection",
                "Managed-transaction retry window for transient failures.",
                editable = false,
                readOnlyReason = RESTART,
            ),
            // -- Embeddings --
            ConfigField(
                "embeddingModel",
                "Embedding model",
                STRING,
                "Embeddings",
                "Vertex embedding model id. Changing it re-embeds via the version stamp.",
            ),
            ConfigField(
                "embeddingDimensions",
                "Embedding dimensions",
                INT,
                "Embeddings",
                "3072 full-fidelity; MRL truncation (1536/768) re-normalizes client-side. Changes re-embed.",
            ),
            ConfigField(
                "embeddingLocation",
                "Embedding location",
                STRING,
                "Embeddings",
                "Vertex location for embeddings; blank → home region (models are served regionally).",
            ),
            ConfigField(
                "embeddingTransport",
                "Embedding transport",
                STRING,
                "Embeddings",
                "vertex (ADC, 5 RPM quota) or gemini-api (paid-tier RPM). Same vector space either way.",
                editable = false,
                readOnlyReason = POSTURE,
            ),
            ConfigField(
                "geminiApiKey",
                "Gemini API key",
                STRING,
                "Embeddings",
                "Key for the gemini-api transport.",
                editable = false,
                secret = true,
                readOnlyReason = SECRET,
            ),
            ConfigField(
                "embedBatchPerPoll",
                "Embed batch / poll",
                INT,
                "Embeddings",
                "Claims embedded per poll tick (bounded work per request).",
            ),
            // -- Entity resolution --
            ConfigField(
                "entityBatchPerPoll",
                "Entity batch / poll",
                INT,
                "Entity resolution",
                "Claims mention-resolved per tick — one Gemini extraction call (~20 claims).",
            ),
            ConfigField(
                "entityMergeThreshold",
                "Entity merge threshold",
                DOUBLE,
                "Entity resolution",
                "Embedding cosine above which a mention merges into an existing same-type entity.",
            ),
            ConfigField(
                "entityIdfFloor",
                "Entity IDF floor",
                DOUBLE,
                "Entity resolution",
                "Entities mentioned by more than (1 − floor) of a subject's claims are stopword-like.",
            ),
            // -- Matching & blocking --
            ConfigField(
                "matchingMode",
                "Matching mode",
                STRING,
                "Matching & blocking",
                "PRUNED (blocking + cascade) or EXHAUSTIVE (calibration benchmark).",
            ),
            ConfigField(
                "episodeWindowYears",
                "Episode window (years)",
                DOUBLE,
                "Matching & blocking",
                "Same-type EPISODEs pair within this dating window (§11.5).",
            ),
            ConfigField(
                "knnK",
                "kNN neighbours (k)",
                INT,
                "Matching & blocking",
                "Vector-blocking neighbours fetched per claim.",
            ),
            ConfigField(
                "simFloor",
                "Similarity floor (τ_low)",
                DOUBLE,
                "Matching & blocking",
                "Similarity discard floor.",
            ),
            ConfigField(
                "simAutoRepeat",
                "Auto-REPEATS threshold (τ_high)",
                DOUBLE,
                "Matching & blocking",
                "Similarity above which a pair auto-classifies REPEATS without the judge.",
            ),
            ConfigField(
                "coMentionHubShare",
                "Co-mention hub share",
                DOUBLE,
                "Matching & blocking",
                "A co-mention pair whose best shared entity exceeds this DF share loses the " +
                    "rung-1 floor bypass (VA-77 B3).",
            ),
            ConfigField(
                "judgeCandidatesPerClaim",
                "Judge candidates / claim",
                INT,
                "Matching & blocking",
                "Per-claim cap on sim-ranked judge candidates; human-asserted and structural " +
                    "pairs are quota-exempt. 0 = uncapped (VA-77 B2).",
            ),
            // -- Judge ensemble --
            ConfigField(
                "ensembleK",
                "Judge samples (k)",
                INT,
                "Judge ensemble",
                "Self-consistency samples per claim pair (§11.6).",
            ),
            ConfigField(
                "ensembleTemperature",
                "Judge temperature",
                DOUBLE,
                "Judge ensemble",
                "Sampler temperature across the ensemble.",
            ),
            ConfigField(
                "ensembleOrderings",
                "Pair orderings",
                STRING,
                "Judge ensemble",
                "Presentation order across k samples (ALTERNATE = position-bias control).",
            ),
            ConfigField(
                "judgeConfidenceFloor",
                "Judge confidence floor",
                DOUBLE,
                "Judge ensemble",
                "Aggregated edge confidence below this → NEUTRAL (no edge).",
            ),
            ConfigField(
                "judgePairsPerPoll",
                "Judge pairs / poll",
                INT,
                "Judge ensemble",
                "Work budget per poll tick.",
            ),
            ConfigField(
                "judgeBatchSize",
                "Judge batch size",
                INT,
                "Judge ensemble",
                "Pairs per Gemini call (the ensemble runs k calls per batch).",
            ),
            ConfigField(
                "judgeParallelism",
                "Judge parallelism",
                INT,
                "Judge ensemble",
                "Concurrent sampler calls per tick — keep low; bursts 429-starve the DSQ share.",
            ),
            ConfigField(
                "judgeThinkingBudget",
                "Judge thinking budget",
                INT,
                "Judge ensemble",
                "Thinking-token cap per sampler call (VA-77 B1); not part of the verdict-cache " +
                    "key — changing it is a calibration event.",
            ),
            ConfigField(
                "judgeMode",
                "Judge mode",
                STRING,
                "Judge ensemble",
                "Which judge decides (VA-106): LLM = the ensemble; GATEKEEPER = the Lakshmana " +
                    "cascade decides and the ensemble does not run; SHADOW = both run, the " +
                    "ensemble decides and the cascade records shadow verdicts only. Pinned into " +
                    "the run's paramsSnapshot at submit — a change never affects a run in flight.",
                options = JudgeMode.NAMES,
            ),
            // -- Scoring dynamics --
            ConfigField(
                "stateSlotTypes",
                "State slot types",
                STRING_LIST,
                "Scoring dynamics",
                "Claim-type × entity patterns treated as exclusive sequencing STATE slots (§11.7).",
            ),
            ConfigField(
                "volatileHalfLifeYears",
                "Evidence half-life (years)",
                DOUBLE_MAP,
                "Scoring dynamics",
                "Per-claim-type recency decay, e.g. SKILL=5.0; absent types don't decay.",
            ),
            ConfigField(
                "maxIterations",
                "Max iterations",
                INT,
                "Scoring dynamics",
                "Fixed-point iteration cap (§11.8).",
            ),
            ConfigField(
                "epsilon",
                "Convergence ε",
                DOUBLE,
                "Scoring dynamics",
                "Fixed-point convergence threshold.",
            ),
            ConfigField(
                "damping",
                "Damping",
                DOUBLE,
                "Scoring dynamics",
                "Fixed-point update damping.",
            ),
            ConfigField(
                "corroborationWeight",
                "Corroboration weight",
                DOUBLE,
                "Scoring dynamics",
                "Log-odds weight at full confidence.",
            ),
            ConfigField(
                "contradictionWeight",
                "Contradiction weight",
                DOUBLE,
                "Scoring dynamics",
                "Log-odds weight — deliberately above corroboration.",
            ),
            ConfigField(
                "dependenceDamping",
                "Dependence damping (λ)",
                DOUBLE,
                "Scoring dynamics",
                "Geometric discount for additional voices within a dependence group.",
            ),
            ConfigField(
                "explanationMitigation",
                "Explanation mitigation (μ)",
                DOUBLE,
                "Scoring dynamics",
                "Fraction of a contradiction penalty removed by a judged-relevant explanation.",
            ),
            ConfigField(
                "anchorPlasticity",
                "Anchor plasticity (ρ)",
                DOUBLE,
                "Scoring dynamics",
                "Update multiplier for anchored (DOCUMENTARY) facts.",
            ),
            ConfigField(
                "againstInterestBonus",
                "Against-interest bonus (β)",
                DOUBLE,
                "Scoring dynamics",
                "Prior bonus for unfavorable SELF claims (statement against interest).",
            ),
            ConfigField(
                "inferredPenalty",
                "Inferred penalty (δ)",
                DOUBLE,
                "Scoring dynamics",
                "Prior penalty (negative) for claimBasis = INFERRED.",
            ),
            ConfigField(
                "selfPraiseCeiling",
                "Self-praise ceiling",
                DOUBLE,
                "Scoring dynamics",
                "Cap for favorable SELF-only facts with zero independent corroboration.",
            ),
            ConfigField(
                "trustShrinkage",
                "Trust shrinkage (m)",
                INT,
                "Scoring dynamics",
                "Pseudo-count shrinking attestor trust toward its prior.",
            ),
            ConfigField(
                "tierHigh",
                "Tier HIGH cutoff",
                DOUBLE,
                "Scoring dynamics",
                "Score → refreshed tier band.",
            ),
            ConfigField(
                "tierMedium",
                "Tier MEDIUM cutoff",
                DOUBLE,
                "Scoring dynamics",
                "Score → refreshed tier band.",
            ),
            // -- Subject Authenticity Index --
            ConfigField(
                "aggMassMidpoint",
                "Mass midpoint (m0)",
                DOUBLE,
                "Subject Authenticity Index",
                "sat(m)=m/(m+m0) — higher is sterner on thin evidence.",
            ),
            ConfigField(
                "aggDepthWeight",
                "Depth weight (λ_D)",
                DOUBLE,
                "Subject Authenticity Index",
                "Max sag from shallow average evidence mass.",
            ),
            ConfigField(
                "aggSelfOnlyPenalty",
                "Self-only penalty (λ_I)",
                DOUBLE,
                "Subject Authenticity Index",
                "Max sag from the self-only fact share.",
            ),
            ConfigField(
                "aggDiversityWeight",
                "Diversity weight (λ_V)",
                DOUBLE,
                "Subject Authenticity Index",
                "Max sag from a monoculture of sources.",
            ),
            ConfigField(
                "aggContradictionWeight",
                "Contradiction drag (λ_C)",
                DOUBLE,
                "Subject Authenticity Index",
                "Max drag from surviving contradictions.",
            ),
            ConfigField(
                "aggContradictionMidpoint",
                "Contradiction midpoint (c0)",
                DOUBLE,
                "Subject Authenticity Index",
                "Residual conflict mass at which drag reaches half of λ_C.",
            ),
            ConfigField(
                "aggAttestorTarget",
                "Attestor target (a0)",
                INT,
                "Subject Authenticity Index",
                "Independent attestors for full diversity credit.",
            ),
            ConfigField(
                "aggDocCoverageTarget",
                "Doc coverage target (d0)",
                DOUBLE,
                "Subject Authenticity Index",
                "Documentary/anchored fact fraction for full diversity credit.",
            ),
            ConfigField(
                "aggDiversityAttestorShare",
                "Diversity mix — attestors",
                DOUBLE,
                "Subject Authenticity Index",
                "Shares must sum to 1 with kinds + doc coverage.",
            ),
            ConfigField(
                "aggDiversityKindShare",
                "Diversity mix — kinds",
                DOUBLE,
                "Subject Authenticity Index",
                "Attestor-kind share of the diversity mix.",
            ),
            ConfigField(
                "aggDiversityDocShare",
                "Diversity mix — docs",
                DOUBLE,
                "Subject Authenticity Index",
                "Doc-coverage share of the diversity mix.",
            ),
            ConfigField(
                "aggBandStrong",
                "Band STRONG cutoff",
                DOUBLE,
                "Subject Authenticity Index",
                "SAI grade band (~90 is the practical ceiling by design).",
            ),
            ConfigField(
                "aggBandGood",
                "Band GOOD cutoff",
                DOUBLE,
                "Subject Authenticity Index",
                "SAI grade band.",
            ),
            ConfigField(
                "aggBandModerate",
                "Band MODERATE cutoff",
                DOUBLE,
                "Subject Authenticity Index",
                "SAI grade band.",
            ),
            ConfigField(
                "aggBandWeak",
                "Band WEAK cutoff",
                DOUBLE,
                "Subject Authenticity Index",
                "SAI grade band.",
            ),
            // -- Workflow & posture --
            ConfigField(
                "phaseTimeout",
                "Phase reclaim timeout",
                DURATION,
                "Workflow & posture",
                "A phase erroring with no successful step for this long reclaims to FAILED for Retry.",
            ),
            ConfigField(
                "publishRequiresReview",
                "Publish requires review",
                BOOLEAN,
                "Workflow & posture",
                "The Q6 gate: ledger write-back only from AWAITING_REVIEW via explicit publish.",
            ),
            ConfigField(
                "crossSubjectEvidence",
                "Cross-subject evidence",
                BOOLEAN,
                "Workflow & posture",
                "Reserved v2+ flag: read cross-subject evidence edges in scoring.",
            ),
            ConfigField(
                "dryRun",
                "Dry-run (master)",
                BOOLEAN,
                "Workflow & posture",
                "Pseudo-embeddings + canned judge; Neo4j stays real.",
                editable = false,
                readOnlyReason = POSTURE,
            ),
        )

    private val stage4 =
        listOf(
            ConfigField(
                "enabled",
                "Stage 4 enabled",
                BOOLEAN,
                "Surface & posture",
                "Kill-switch for persona writes + run submits.",
            ),
            ConfigField(
                "dryRun",
                "Dry-run",
                BOOLEAN,
                "Surface & posture",
                "Offline generator + judge doubles over the dry-run corpus.",
                editable = false,
                readOnlyReason = POSTURE,
            ),
            ConfigField(
                "dryRunJudgeFailRate",
                "Dry-run judge FAIL rate",
                DOUBLE,
                "Surface & posture",
                "Scripted-judge FAIL fraction (dry-run only, deterministic per plan).",
            ),
            ConfigField(
                "dryRunJudgeBorderlineRate",
                "Dry-run judge BORDERLINE rate",
                DOUBLE,
                "Surface & posture",
                "Scripted-judge BORDERLINE fraction (dry-run only).",
            ),
            ConfigField(
                "mix.qa",
                "Mix — QA",
                DOUBLE,
                "Category mix",
                "S4-D7 dial; the fact-driven trio (qa/situational/multiClaim) normalizes among itself.",
            ),
            ConfigField(
                "mix.situational",
                "Mix — situational",
                DOUBLE,
                "Category mix",
                "Situational-led anchor of the mix.",
            ),
            ConfigField(
                "mix.multiClaim",
                "Mix — multi-claim",
                DOUBLE,
                "Category mix",
                "Multi-claim share of the fact-driven trio.",
            ),
            ConfigField(
                "mix.negative",
                "Mix — negative",
                DOUBLE,
                "Category mix",
                "Recorded in the snapshot; negative plans its full probe bank (QD-5).",
            ),
            ConfigField(
                "mix.meta",
                "Mix — meta",
                DOUBLE,
                "Category mix",
                "Recorded in the snapshot; meta plans its full probe bank (QD-5).",
            ),
            ConfigField(
                "maxConversationsPerClaim",
                "Max conversations / claim",
                INT,
                "Planning & generation",
                "PLAN fan-out cap across all categories (§9.2).",
            ),
            ConfigField(
                "dedupeJaccardThreshold",
                "Dedupe Jaccard threshold",
                DOUBLE,
                "Planning & generation",
                "MinHash/Jaccard similarity above which two planned questions are duplicates.",
            ),
            ConfigField(
                "generateBatchPerPoll",
                "Generate batch / poll",
                INT,
                "Planning & generation",
                "GENERATE work budget per poll tick.",
            ),
            ConfigField(
                "judgeBatchPerPoll",
                "Judge batch / poll",
                INT,
                "Judge & review",
                "JUDGE work budget per poll tick.",
            ),
            ConfigField(
                "ensembleK",
                "Judge samples (k)",
                INT,
                "Judge & review",
                "Self-consistency samples per axis (§11).",
            ),
            ConfigField(
                "judgeEnabled",
                "LLM judge enabled",
                BOOLEAN,
                "Judge & review",
                "QD-6 dial: false skips the §11 judge — examples land unsorted in the human queue.",
            ),
            ConfigField(
                "reviewSampleRate",
                "Review sample rate",
                DOUBLE,
                "Judge & review",
                "PASS fraction routed to human review. v1 enforces 1.0 regardless (QA-4).",
            ),
            ConfigField(
                "dpoEnabled",
                "DPO pairs enabled",
                BOOLEAN,
                "Export & eval",
                "§12 DPO generation — ships dark until the first DPO tune (S4-D4).",
            ),
            ConfigField(
                "evalHoldoutFraction",
                "Eval holdout fraction",
                DOUBLE,
                "Export & eval",
                "Per-category slice held out of export for the post-tune behavioral eval (§14).",
            ),
            ConfigField(
                "evalBehaviorBar",
                "Eval behavior bar",
                DOUBLE,
                "Export & eval",
                "Advisory post-tune expected-behavior match rate (warn, never block).",
            ),
            ConfigField(
                "phaseTimeout",
                "Phase reclaim timeout",
                DURATION,
                "Lifecycle",
                "Stuck-phase reclaim horizon (the Stage 2 §12.7 idiom).",
            ),
        )
}

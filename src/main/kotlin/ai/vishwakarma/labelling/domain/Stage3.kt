package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * Where a Stage 3 scoring run is in the LLD §9.7 state machine. Non-terminal phases advance one
 * bounded step per poll (no scheduler — the Stage 2 idiom); [FAILED], [PUBLISHED] and [SUPERSEDED]
 * are terminal; [AWAITING_REVIEW] parks the run at the Q6 gate (provisional scores graph-side,
 * ledger untouched) until the operator publishes, explicitly skips the contradiction queue, or
 * re-runs (which retires the parked run as [SUPERSEDED] — it never published, so no ledger claim
 * references it).
 */
enum class Stage3RunStatus {
    PENDING,
    SYNCING,
    RESOLVING_ENTITIES,
    EMBEDDING,
    MATCHING,
    JUDGING,
    ASSEMBLING,
    SCORING,
    AWAITING_REVIEW,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    SUPERSEDED;

    val terminal: Boolean
        get() = this == PUBLISHED || this == FAILED || this == SUPERSEDED

    companion object {
        fun fromOrNull(raw: String?): Stage3RunStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Well-known [Stage3Run.counters] keys — phases fill the ones they own. */
object Stage3Counters {
    const val CLAIMS_SYNCED = "claimsSynced"
    const val SOURCES_SYNCED = "sourcesSynced"
    const val ATTESTORS_SYNCED = "attestorsSynced"
    const val EXPLANATIONS_SYNCED = "explanationsSynced"
    /** Sidecar citations pointing at non-approved claims, dropped at projection (see VA-10). */
    const val CITATIONS_DROPPED = "citationsDropped"
    /** RESOLVE_ENTITIES progress: claims whose mentions are resolved under the current stamp. */
    const val CLAIMS_ENTITY_RESOLVED = "claimsEntityResolved"
    /** Mentions linked to existing canon — exact/alias hits and ≥-threshold kNN merges (§11.3). */
    const val MENTIONS_LINKED = "mentionsLinked"
    const val ENTITIES_MINTED = "entitiesMinted"
    /** Near-miss provisional links routed to the entity-review list (§11.3 band). */
    const val MENTIONS_REVIEW_LISTED = "mentionsReviewListed"
    /** Claim attestations migrated from per-asset fallback to issuer-entity keys (§18.2 Q2). */
    const val ISSUER_ATTESTORS_UPGRADED = "issuerAttestorsUpgraded"
    const val CLAIMS_EMBEDDED = "claimsEmbedded"
    /** MATCH's §15 #6 pre-flight: stale-space entity vectors re-embedded before kNN (VA-14). */
    const val ENTITIES_REEMBEDDED = "entitiesReembedded"
    /** The §11.5 funnel, per blocking arm (a pair counts once per arm that produced it)… */
    const val PAIRS_KNN = "pairsKnn"
    const val PAIRS_CO_MENTION = "pairsCoMention"
    const val PAIRS_STRUCTURAL = "pairsStructural"
    const val PAIRS_HUMAN_ASSERTED = "pairsHumanAsserted"
    /** …its deduped union… */
    const val PAIRS_CANDIDATE = "pairsCandidate"
    /** …and its outcomes: auto-REPEATS/same-fact (rungs 2/3/5), discards (1/4), judge queue. */
    const val PAIRS_AUTO_RESOLVED = "pairsAutoResolved"
    const val PAIRS_DISCARDED = "pairsDiscarded"
    /** VA-77 B3: rung-1 discards that only the hub-entity co-mention bypass used to save. */
    const val PAIRS_IDF_GATED = "pairsIdfGated"
    /** VA-77 B2: queue candidates dropped by the per-claim cap (neither endpoint kept them). */
    const val PAIRS_CAPPED = "pairsCapped"
    const val PAIRS_QUEUED = "pairsQueued"
    /** JUDGE progress: queue entries flipped to JUDGED (authoritative graph count, VA-15). */
    const val PAIRS_JUDGED = "pairsJudged"
    /** Verdict variants served from the `stage3_edges` cache without any sampling. */
    const val JUDGE_CACHE_HITS = "judgeCacheHits"
    /** Ensemble sampler invocations (k per missed sub-batch per variant; LLM calls when real). */
    const val JUDGE_SAMPLER_CALLS = "judgeSamplerCalls"
    /** Verdict variants whose majority relation was tied — LLD §15 #4's tie-rate numerator. */
    const val JUDGE_TIES = "judgeTies"
    /** ASSEMBLE (§11.7): the fact count + its kind split. */
    const val FACTS = "facts"
    const val FACTS_STATE = "factsState"
    const val FACTS_EVENT = "factsEvent"
    const val FACTS_TIMELESS = "factsTimeless"
    /** Lifted fact-level edges… */
    const val FACT_CORROBORATES = "factCorroborates"
    const val FACT_CONTRADICTS = "factContradicts"
    /** …CONTRADICTS verdicts the temporal gate dropped (sequences, not conflicts)… */
    const val CONTRADICTIONS_GATED = "contradictionsGated"
    /** …and the timeline edges laid between same-slot STATE facts. */
    const val SUCCEEDS_EDGES = "succeedsEdges"
    /** SCORE (§11.8): claims carrying a provisional vector after the fixed point. */
    const val CLAIMS_SCORED = "claimsScored"
    /** The §11.10 queue size at AWAITING_REVIEW (PROPOSED ∧ unexplained ∧ ≥ floor). */
    const val CONTRADICTION_QUEUE = "contradictionQueue"
    /** Claims whose explained score was lifted to scoreBare (I2 enforcement; 0 = healthy). */
    const val SCORE_I2_CLAMPED = "scoreI2Clamped"
    /** PUBLISHING (§11.11): claim vectors written to the Firestore ledger. */
    const val CLAIMS_PUBLISHED = "claimsPublished"
    /** PUBLISHING (contract v2): fact docs frozen into `subject_facts`. */
    const val FACTS_PUBLISHED = "factsPublished"
    /** The Stage 3.5 §5 subject aggregate frozen at publish, as its 0–100 display value. */
    const val SUBJECT_SCORE = "subjectScore"
}

/**
 * One Stage 3 scoring run (`stage3_runs`, LLD §9.6) — the submit-then-poll record mirroring
 * [Stage2Job]. One *active* (non-terminal) run per subject; a re-run of a PUBLISHED run creates a
 * **new** record so the published run's [paramsSnapshot] keeps resolving the `scoreRunId` stamped
 * on ledger claims (the reproducibility contract survives re-runs).
 */
data class Stage3Run(
    val id: String,
    val subjectId: String,
    val status: Stage3RunStatus = Stage3RunStatus.PENDING,
    /**
     * The phase the run was in when it FAILED; operator Retry resumes here. Phases are re-entrant
     * (MERGE-idempotent / graph-as-cursor), so resuming a phase never double-applies work.
     */
    val failedPhase: Stage3RunStatus? = null,
    /**
     * Re-run hygiene flag: SYNC detach-deletes the subject's evidence layer before projecting
     * (never global layers). Recorded at submit; judge-cache dropping rides this too (VA-15+).
     */
    val fresh: Boolean = false,
    /** JSON of every `app.stage3.*` value frozen at submit (§9.6) — scores stay attributable. */
    val paramsSnapshot: String? = null,
    /** Phase progress counters, keyed by [Stage3Counters]. */
    val counters: Map<String, Long> = emptyMap(),
    /**
     * Resumability cursors for chunked phases (judgeCursor arrives with VA-15). SYNC is a single
     * idempotent projection and EMBED uses the graph itself as its cursor (unembedded claims stop
     * matching), so neither stores one here.
     */
    val cursors: Map<String, String> = emptyMap(),
    /** Fixed-point outcome (§11.8) — null until SCORING runs. */
    val converged: Boolean? = null,
    val iterations: Int? = null,
    /** The Q6 gate outcome (§11.10). */
    val publishedAt: Instant? = null,
    val publishedBy: String? = null,
    val reviewSkipped: Boolean = false,
    /** Verbatim provider/graph error on FAILED (terminal-state failure idiom). */
    val error: String? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /**
     * When the run last made a successful phase advance (phase entry or completed chunk) — the
     * stuck-phase reclaim clock (the Stage 2 §12.7 idiom generalized; see
     * `app.stage3.phase-timeout`).
     */
    val phaseSince: Instant? = null,
)

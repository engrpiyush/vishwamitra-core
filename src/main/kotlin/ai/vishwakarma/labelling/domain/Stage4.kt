package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * Where a Stage 4 generation run is in the LLD §9 state machine. Non-terminal phases advance one
 * bounded step per poll (no scheduler — the Stage 2/3 idiom); [REVIEW_WAIT] parks the run at the
 * 100%-human-review gate (QA-4, the AWAITING_REVIEW idiom) until the operator exports; [DONE],
 * [FAILED] and [SUPERSEDED] are terminal.
 */
enum class Stage4RunStatus {
    PENDING,
    SELECTING,
    PLANNING,
    GENERATING,
    JUDGING,
    REVIEW_WAIT,
    DONE,
    FAILED,
    SUPERSEDED;

    val terminal: Boolean
        get() = this == DONE || this == FAILED || this == SUPERSEDED

    companion object {
        fun fromOrNull(raw: String?): Stage4RunStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** The five S4-D7 dataset categories; the §16 mix weights are keyed to these. */
enum class Stage4Category {
    /** Claim-grounded single-fact Q&A. */
    QA,
    /** Situational-CoT derivations (§10). */
    SITUATIONAL,
    /** Multi-claim conversations over fact groups + SUCCEEDS chains. */
    MULTI_CLAIM,
    /** Negative space: refusals, injection probes, defensive advocacy. */
    NEGATIVE,
    /** Meta/identity — template-rendered, LLM-free (§9.3). */
    META;

    companion object {
        fun fromOrNull(raw: String?): Stage4Category? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Well-known [Stage4Run.counters] keys — phases fill the ones they own (§15 run-page card). */
object Stage4Counters {
    /** SELECT: approved-for-downstream claims carrying the current scoreRunId. */
    const val CLAIMS_ELIGIBLE = "claimsEligible"
    /** SELECT drift sweep (QA-6): examples auto-archived as superseded. */
    const val EXAMPLES_ARCHIVED = "examplesArchived"
    /** PLAN: voicing plans frozen into `stage4_plans`. */
    const val PLANS = "plans"
    /** PLAN: planned questions dropped by MinHash/Jaccard dedupe (§9.2). */
    const val PLANS_DEDUPED = "plansDeduped"
    /** PLAN: units dropped by the per-claim fan-out cap (§9.2). */
    const val PLANS_CAPPED = "plansCapped"
    /** PLAN: units planned from the NotebookTemplate library (VA-88; 0 = the legacy trio ran). */
    const val PLANS_FROM_TEMPLATES = "plansFromTemplates"
    /** GENERATE: DRAFT examples written (LLM or template-rendered). */
    const val GENERATED = "generated"
    /** GENERATE: plans served from the generation cache without an LLM call (§9.3). */
    const val CACHE_HITS = "cacheHits"
    /** JUDGE verdict split (§11) — the review queue's pre-sort. */
    const val JUDGED_PASS = "judgedPass"
    const val JUDGED_BORDERLINE = "judgedBorderline"
    const val JUDGED_FAIL = "judgedFail"
}

/**
 * One Stage 4 generation run (`stage4_runs`, LLD §6) — the submit-then-poll record mirroring
 * [Stage3Run]. One *active* (non-terminal) run per subject; [scoreRunId] and [personaHash] are
 * frozen at SELECT so every artifact the run produces is attributable to the exact published ledger
 * and persona that authorized it (§1 traceability).
 */
data class Stage4Run(
    val id: String,
    val subjectId: String,
    /** The publish the run is pinned to (QA-6) — `subject_scores.scoreRunId` frozen at SELECT. */
    val scoreRunId: String? = null,
    /** The resolved persona hash frozen at SELECT (defaults-only persona materializes one). */
    val personaHash: String? = null,
    /**
     * The resolved SubjectProfile A/B hash frozen at submit beside the injected locale/as-of
     * scalars (profile LLD §6.4, OD-7) — the third drift axis. Null when the subject declared no
     * profile, which is also what every pre-profile run carries: null vs null never drifts.
     */
    val profileHash: String? = null,
    val status: Stage4RunStatus = Stage4RunStatus.PENDING,
    /** The phase the run was in when it FAILED; operator Retry resumes here (phases re-entrant). */
    val failedPhase: Stage4RunStatus? = null,
    /** Full regeneration: ignore the generation cache and re-draft every plan (QA-6). */
    val fresh: Boolean = false,
    /** JSON of every `app.stage4.*` value frozen at submit — datasets stay attributable. */
    val paramsSnapshot: String? = null,
    /** Phase progress counters, keyed by [Stage4Counters]. */
    val counters: Map<String, Long> = emptyMap(),
    /** Resumability cursors for chunked phases (plan/generate/judge batches). */
    val cursors: Map<String, String> = emptyMap(),
    /** Verbatim provider error on FAILED (terminal-state failure idiom). */
    val error: String? = null,
    /**
     * The VA-88 coverage report, frozen at PLAN: JSON array of `{category, target, planned}` per
     * template category — hit/missed reads straight off `planned < target`. Null = pre-VA-88 run or
     * a run planned by the legacy (template-less) trio.
     */
    val coverageReport: String? = null,
    /** The `exports` doc that completed this run (REVIEW_WAIT → DONE journal, §9.4/VA-58). */
    val exportRecordId: String? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /**
     * Last successful phase advance — the stuck-phase reclaim clock (`app.stage4.phase-timeout`).
     */
    val phaseSince: Instant? = null,
)

/**
 * The §6 traceability stamp carried by every Stage 4-generated [SftExample]/[DpoPair]: the exact
 * scored evidence, voicing plan, persona and prompt that authorized the training line. Null on
 * legacy (labelling-era) examples — the existing sft/dpo lifecycle is untouched without it.
 */
data class Stage4Stamp(
    val subjectId: String,
    val sourceClaimIds: List<String> = emptyList(),
    /** The publish the source claims were scored under (§4 consumer rule). */
    val scoreRunId: String? = null,
    val category: Stage4Category? = null,
    /** The frozen voicing plan (`stage4_plans` doc id) behind this conversation. */
    val planId: String? = null,
    /** Hash of the resolved persona the generation prompt embedded. */
    val personaHash: String? = null,
    /**
     * Hash of the resolved SubjectProfile A/B block whose locale/freshness the prompt injected
     * (profile LLD §6.4). Null = no profile declared; an A/B edit changes it and the drift sweep
     * archives this example exactly as a persona-dial change would.
     */
    val profileHash: String? = null,
    /** Short hash of the exact generator prompt row used (ExtractionPrompt idiom). */
    val generatorPromptHash: String? = null,
    /** The NotebookTemplate that shaped this conversation (VA-88); null = legacy planning. */
    val templateId: String? = null,
    /** The template's evaluation-trait category slug — the VA-60 eval axis this example probes. */
    val templateCategory: String? = null,
)

/**
 * Assertion ladder for a planned conversation, most to least assertive. The C6=conservative persona
 * dial shifts band rows one notch down via [down]; there is deliberately no `up()` — nothing can
 * ever voice above what the score authorizes (fixed rule F4, structural).
 */
enum class HedgeLevel {
    /** Flat assertion (row 1/2 bands; verbatim opted-in PII). */
    ASSERTIVE,
    /** Confident but softened — the conservative notch below assertive. */
    MEASURED,
    /** Explicitly hedged, attribution named (rows 3/4; situational conclusions). */
    HEDGED,
    /** Deep reserve — voiced only as the subject's own account, heavily qualified. */
    RESERVED,
    /** No assertion at all: honest-gap / refusal voices (rows 10/12/13). */
    GAP;

    /** One hedge notch down, saturating at [RESERVED] ([GAP] is a voice, not a notch). */
    fun down(): HedgeLevel =
        when (this) {
            ASSERTIVE -> MEASURED
            MEASURED -> HEDGED
            HEDGED -> RESERVED
            RESERVED -> RESERVED
            GAP -> GAP
        }

    companion object {
        fun fromOrNull(raw: String?): HedgeLevel? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * The voicing planner's frozen output for one planned conversation (§8): *what may be said and how
 * confidently*, computed deterministically before any LLM call. Generation prompts embed
 * [constraints] verbatim; the judge's voice-compliance axis re-checks against the same plan.
 */
data class VoicingPlan(
    /**
     * Content hash over (category, unit key, claim ids, row, hedge, persona hash) — see planner.
     */
    val planId: String,
    /** The §8 table row (1–14) that fired. */
    val rowId: Int,
    /** The row's voice label, verbatim from the table. */
    val voice: String,
    val hedgeLevel: HedgeLevel,
    /** The constraint lines the generation prompt embeds verbatim (fixed rules + row rules). */
    val constraints: List<String> = emptyList(),
    val sourceClaimIds: List<String> = emptyList(),
    val category: Stage4Category,
    /** False only for row 5 (unexplained CONFIRMED conflict): DPO rejected-side candidate. */
    val sftEligible: Boolean = true,
    /**
     * The NotebookTemplate behind this plan (VA-88): the template constrains form at GENERATE —
     * this plan's constraints still own what may be said (the deterministic-planner contract). Null
     * = a legacy (trio/probe-bank) plan.
     */
    val templateId: String? = null,
    /** The template's evaluation-trait category slug at plan time. */
    val templateCategory: String? = null,
)

/**
 * One `stage4_plans` doc (doc id = [VoicingPlan.planId]): the plan plus the subject/publish/
 * persona context it was computed under. Content-addressed, so an unchanged claim re-plans onto the
 * same doc across runs (the re-run-free cache key's first half, §9.3).
 */
data class Stage4Plan(
    val subjectId: String,
    val scoreRunId: String? = null,
    val personaHash: String? = null,
    /** The planned guest question (PLAN fills it; MinHash dedupe runs over these, §9.2). */
    val question: String? = null,
    val plan: VoicingPlan,
    val createdAt: Instant? = null,
)

/** A judge axis outcome: majority verdict over the ensemble votes (§11). */
enum class JudgeVerdict {
    PASS,
    BORDERLINE,
    FAIL;

    companion object {
        fun fromOrNull(raw: String?): JudgeVerdict? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** The four §11 judge axes. */
enum class JudgeAxis {
    /** Every assertion entailed by the cited claims. */
    FAITHFULNESS,
    /** Matches the plan's row + hedge level + F5 precision. */
    VOICE_COMPLIANCE,
    /** §10.3 hedging re-derived and checked. */
    SPECULATION_GROUNDING,
    /** Stance, preset and dials respected. */
    PERSONA_CONSISTENCY;

    companion object {
        fun fromOrNull(raw: String?): JudgeAxis? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** One axis result inside a judgment: the majority [verdict], the raw vote split, and why. */
data class JudgeAxisResult(
    val verdict: JudgeVerdict,
    /** Verdict name → vote count across the ensemble's k samples. */
    val votes: Map<String, Int> = emptyMap(),
    val rationale: String? = null,
)

/**
 * One judge pass over one example (`stage4_judgments`, LLD §6) — persisted verbatim because this
 * collection is the future judge-distillation set (train a small judge on accrued verdicts).
 */
data class Stage4Judgment(
    val id: String,
    val exampleId: String,
    val runId: String? = null,
    val subjectId: String? = null,
    /** [JudgeAxis] name → axis result. */
    val axes: Map<String, JudgeAxisResult> = emptyMap(),
    /** Worst-axis rollup: FAIL if any axis failed, else BORDERLINE if any, else PASS. */
    val overall: JudgeVerdict = JudgeVerdict.PASS,
    /** Judge rubric provenance (ExtractionPrompt idiom) — rubric fixes re-run cleanly. */
    val judgePromptVersion: Int? = null,
    val judgePromptHash: String? = null,
    /**
     * Short hash of the exact turns judged — the judge-once cursor's staleness key: an edited
     * example misses at its new hash and re-judges (§11 feedback loop, the ctx-verdict idiom).
     */
    val turnsHash: String? = null,
    val model: String? = null,
    val createdAt: Instant? = null,
)

/** Region bucket for the admin advocate-name pool (QA on A2: 15–20 names, US/EU/IN). */
enum class AdvocateRegion {
    US,
    EU,
    IN;

    companion object {
        fun fromOrNull(raw: String?): AdvocateRegion? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * One admin-pool advocate name (`advocate_names`, QA on A2). A skipped A2 wizard answer resolves to
 * a deterministic pick from this pool — stable per subject while the pool is unchanged.
 */
data class AdvocateName(
    val id: String,
    val name: String,
    val region: AdvocateRegion? = null,
    /** Free-form ("F"/"M" seeded 50/50 per QA-1); display metadata, not a selection key. */
    val gender: String? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
)

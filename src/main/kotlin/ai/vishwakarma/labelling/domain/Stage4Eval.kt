package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * Where a post-tune behavioral eval run is (LLD §14, VA-60/VA-67). The submit-then-poll idiom:
 * [DEPLOYING] waits on the eval transport making the tuned model answerable (a 25–35 min Vertex LRO
 * on the endpoint transport; instant on vLLM); [PROBING] asks + grades a bounded probe batch per
 * poll; [TEARING_DOWN] waits on the unconditional release (the cost guard — reached from both the
 * happy path and a probe-phase failure); [DONE]/[FAILED] are terminal.
 */
enum class EvalRunStatus {
    DEPLOYING,
    PROBING,
    TEARING_DOWN,
    DONE,
    FAILED;

    val terminal: Boolean
        get() = this == DONE || this == FAILED

    companion object {
        fun fromOrNull(raw: String?): EvalRunStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * The §14 expected-behavior vocabulary the eval grades against: what the trained advocate should
 * *do* with a probe, independent of wording — assert the fact, hedge it, refuse the question,
 * reframe criticism with evidence, or disclose its AI-advocate identity.
 */
enum class ExpectedBehavior {
    ASSERT,
    HEDGE,
    REFUSE,
    REFRAME,
    DISCLOSE;

    companion object {
        fun fromOrNull(raw: String?): ExpectedBehavior? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Where a probe came from: the fixed hand-authored core bank, or the export-time holdout slice. */
enum class EvalProbeKind {
    CORE,
    HOLDOUT;

    companion object {
        fun fromOrNull(raw: String?): EvalProbeKind? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Well-known [Stage4EvalRun.counters] keys (the run page's live card). */
object EvalCounters {
    const val PROBES = "probes"
    const val ANSWERED = "answered"
    const val MATCHED = "matched"
    /** Safety-row probes (banned-class / PII / disclosure) whose behavior did NOT match. */
    const val SAFETY_FAILED = "safetyFailed"
}

/**
 * One behavioral eval of one tuned [ModelVersion] (`stage4_eval_runs`). The probe set is frozen at
 * start into `stage4_eval_probes` docs; the report JSON lands on the version itself when the run
 * completes (LLD §14 — the operator reads it before registering the advocate).
 */
data class Stage4EvalRun(
    val id: String,
    val versionId: String,
    /** The subject the tuned dataset spoke for — derived from the version's export lineage. */
    val subjectId: String,
    /** The eval transport that served the probes (`endpoint` or `vllm`, VA-67). */
    val transport: String,
    val status: EvalRunStatus = EvalRunStatus.DEPLOYING,
    /** Progress counters, keyed by [EvalCounters]. */
    val counters: Map<String, Long> = emptyMap(),
    /** Verbatim error carried through the unconditional teardown into FAILED. */
    val error: String? = null,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val finishedAt: Instant? = null,
)

/**
 * One probe of one eval run (`stage4_eval_probes`) — the transcript the report links to. Created
 * unanswered at start (the store-is-the-cursor idiom: PROBING picks up docs with a null [reply]);
 * the ask + grade fill the rest in one step.
 */
data class Stage4EvalProbe(
    val id: String,
    val evalRunId: String,
    val versionId: String,
    val kind: EvalProbeKind,
    /** The §8 voicing row this probe exercises (core: authored; holdout: the plan's row). */
    val rowId: Int? = null,
    /** Holdout probes: the source example's dataset category. */
    val category: Stage4Category? = null,
    /**
     * Non-null marks a safety row (§14 advisory bar: 100% required): `banned:<topic>`, `pii` or
     * `disclosure`.
     */
    val safetyClass: String? = null,
    val expectedBehavior: ExpectedBehavior,
    val question: String,
    /** Holdout probes: the approved conversation's final model turn — the grading ground truth. */
    val referenceAnswer: String? = null,
    /** Holdout probes: the held-out `sft_examples` doc behind this probe. */
    val sourceExampleId: String? = null,
    /** The tuned model's reply; null until the PROBING tick asks. */
    val reply: String? = null,
    /** The grader's read of what the reply did (§14 behavior match). */
    val observedBehavior: ExpectedBehavior? = null,
    val behaviorMatch: Boolean? = null,
    /** [JudgeAxis] name → verdict name — the four-axis grading detail (advisory). */
    val axes: Map<String, String> = emptyMap(),
    val rationale: String? = null,
    val createdAt: Instant? = null,
    val answeredAt: Instant? = null,
)

package ai.vishwakarma.labelling.stage3.gatekeeper

import ai.vishwakarma.labelling.persistence.GatekeeperEdgeKey
import ai.vishwakarma.labelling.persistence.GatekeeperEdgeRepository
import ai.vishwakarma.labelling.persistence.GatekeeperRunRepository
import ai.vishwakarma.labelling.stage3.JudgeTickOutcome
import ai.vishwakarma.labelling.stage3.JudgedPair
import ai.vishwakarma.labelling.stage3.PairToJudge
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Outcome of a publish attempt — what the caller records on the run. */
data class TriggerResult(
    val runRequestId: String,
    val published: Boolean,
    val error: String? = null,
) {
    val publishState: String
        get() = if (published) PUBLISHED else PUBLISH_FAILED

    companion object {
        const val PUBLISHED = "PUBLISHED"
        const val PUBLISH_FAILED = "PUBLISH_FAILED"
    }
}

/**
 * The vishwamitra half of the gatekeeper integration (Gatekeeper LLD §14).
 *
 * Deliberately **not** a writer of `Stage3Run`: vishwamitra's Stage 3 service is that document's
 * sole writer (§7.4), so this returns what happened and the caller persists it. Equally, it never
 * writes `gatekeeper_runs` — lakshmana owns that collection outright and this side only reads it.
 *
 * There are no callbacks anywhere in the design (owner requirement #6): the Stage 3 poll loop asks
 * [status] how the cascade is doing and advances its own lifecycle from the answer.
 */
@Service
class GatekeeperService(
    private val publisher: GatekeeperPublisher,
    private val runs: GatekeeperRunRepository,
    private val edges: GatekeeperEdgeRepository,
) {

    private val log = LoggerFactory.getLogger(GatekeeperService::class.java)

    /**
     * Ask the cascade to run, from G1.
     *
     * [intakeId] is the subject's intake manifest id, which in this codebase equals the subjectId
     * (one manifest per subject) — the wire keeps the two distinct because lakshmana's model does.
     */
    fun trigger(
        intakeId: String,
        stage3RunId: String,
        triggeredBy: TriggeredBy = TriggeredBy.SYSTEM,
    ): TriggerResult =
        publish(
            GatekeeperRunRequest(
                runRequestId = GatekeeperContract.mintRunRequestId(),
                intakeId = intakeId,
                stage3RunId = stage3RunId,
                gate = Gate.G1_NEUTRAL,
                mode = RunMode.FULL,
                triggeredBy = triggeredBy,
                requestTimestamp = GatekeeperContract.formatTimestamp(Instant.now()),
            )
        )

    /**
     * Re-enter one failed gate, keeping earlier gates' work ([fromGate] non-null), or start over
     * with a fresh id ([fromGate] null → FROM_START, which supersedes any non-terminal
     * predecessor). FROM_GATE deliberately reuses the existing `runRequestId`: the chain is one
     * run, and the transactional claim on that id is what keeps a redelivery harmless (LLD §6 rule
     * 2).
     */
    fun retrigger(
        intakeId: String,
        stage3RunId: String,
        runRequestId: String?,
        fromGate: Gate?,
    ): TriggerResult {
        val fromStart = fromGate == null
        return publish(
            GatekeeperRunRequest(
                runRequestId =
                    if (fromStart || runRequestId.isNullOrBlank())
                        GatekeeperContract.mintRunRequestId()
                    else runRequestId,
                intakeId = intakeId,
                stage3RunId = stage3RunId,
                gate = fromGate ?: Gate.G1_NEUTRAL,
                mode = if (fromStart) RunMode.FULL else RunMode.FROM_GATE,
                triggeredBy = TriggeredBy.OPERATOR,
                requestTimestamp = GatekeeperContract.formatTimestamp(Instant.now()),
            )
        )
    }

    private fun publish(request: GatekeeperRunRequest): TriggerResult =
        try {
            publisher.publish(request)
            TriggerResult(request.runRequestId, published = true)
        } catch (e: Exception) {
            // Never rethrow: a publish failure must land on the run as PUBLISH_FAILED with a retry
            // button, not as a phase exception that fails the whole Stage 3 run (LLD §14).
            log.warn(
                "Gatekeeper publish FAILED for stage3Run {} ({} {}): {}",
                request.stage3RunId,
                request.mode,
                request.gate,
                e.message,
            )
            TriggerResult(request.runRequestId, published = false, error = e.message ?: "$e")
        }

    /** The run doc for an id we minted, or [GatekeeperRunView.absent] before the first claim. */
    fun status(runRequestId: String, intakeId: String, stage3RunId: String): GatekeeperRunView =
        runs.findByRunRequestId(runRequestId)
            ?: GatekeeperRunView.absent(runRequestId, intakeId, stage3RunId)

    /** The intake's newest run — what the run page shows regardless of which run wrote it. */
    fun latestForIntake(intakeId: String): GatekeeperRunView? = runs.findLatestByIntake(intakeId)

    /**
     * A batch of queued pairs split by whether the cascade decided them: [decided] shaped exactly
     * as the LLM ensemble's [JudgeTickOutcome] (so the JUDGE phase persists them through the same
     * `applyJudgeOutcome` path and ASSEMBLE is untouched), and [escalated] the pairs the cascade
     * routed to a human. The caller applies the first and marks the second `ESCALATED` so both
     * leave the QUEUED window and the phase drains.
     */
    data class GatekeeperBatch(
        val decided: JudgeTickOutcome,
        val escalated: List<PairToJudge>,
    )

    /**
     * Read the cascade's verdicts for one batch of queued pairs.
     *
     * **Variant-aware, deliberately.** The cascade writes exactly one `stage3_edges` doc per pair,
     * keyed on that pair's own `withContext` flag (lakshmana `worker/edges.py`) — a dual-eval
     * (§11.9) pair has only a `…|ctx|GK` row, not a bare one. So the primary verdict is read from
     * the pair's *own* variant, not always from bare; a pair whose declared-variant doc is absent
     * (or carries no relation — the human-escalation shape) is returned in
     * [GatekeeperBatch.escalated], **never defaulted to NEUTRAL**: fabricating a verdict would bury
     * exactly the pairs that most need a person.
     */
    fun decidePairs(pairs: List<PairToJudge>): GatekeeperBatch {
        if (pairs.isEmpty())
            return GatekeeperBatch(JudgeTickOutcome(emptyList(), 0, 0, 0), emptyList())
        val rows = edges.findAll(pairs.map { it.pair })
        val judged = mutableListOf<JudgedPair>()
        val escalated = mutableListOf<PairToJudge>()
        pairs.forEach { pair ->
            val row = rows[GatekeeperEdgeKey(pair.pair, pair.withContext)]
            val verdict = row?.toVerdict()
            if (verdict == null) {
                escalated += pair
            } else {
                judged +=
                    JudgedPair(
                        pair = pair.pair,
                        bare = verdict,
                        // The cascade's single doc IS the pair's verdict; it does not answer the
                        // §11.9 relevance question separately, so no distinct ctx verdict exists.
                        ctx = null,
                        judgeModel = row.judgeModel ?: GATEKEEPER_MODEL,
                        promptStamp = GatekeeperEdgeRepository.STAMP,
                    )
            }
        }
        if (escalated.isNotEmpty())
            log.info(
                "Gatekeeper: {} of {} pair(s) in this batch have no verdict (human-escalated) — " +
                    "marking ESCALATED",
                escalated.size,
                pairs.size,
            )
        // cacheHits/samplerCalls/ties are ensemble vocabulary. The cascade makes no sampler calls
        // and casts no votes, so reporting zeros is accurate rather than lazy.
        return GatekeeperBatch(
            decided = JudgeTickOutcome(judged = judged, cacheHits = 0, samplerCalls = 0, ties = 0),
            escalated = escalated,
        )
    }

    val posture: String
        get() = publisher.posture

    companion object {
        /** Fallback `judgeModel` stamp when a row omits one — provenance stays truthful. */
        const val GATEKEEPER_MODEL = "gatekeeper"
    }
}

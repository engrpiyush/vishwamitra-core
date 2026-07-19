package ai.vishwakarma.labelling.stage3.gatekeeper

import java.time.Duration
import java.time.Instant

/** Run-level lifecycle of a gatekeeper run (Gatekeeper LLD §6). */
enum class GatekeeperRunState {
    REQUESTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SUPERSEDED;

    val terminal: Boolean
        get() = this == SUCCEEDED || this == SUPERSEDED

    companion object {
        fun fromOrNull(raw: String?): GatekeeperRunState? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Per-gate lifecycle inside the run doc's gate map (Gatekeeper LLD §6). */
enum class GateState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED;

    /** Maps onto the existing `.badge--*` vocabulary so the run page needs no new CSS. */
    val badge: String
        get() =
            when (this) {
                SUCCEEDED -> "success"
                RUNNING -> "info"
                FAILED -> "danger"
                SKIPPED -> "warning"
                PENDING -> "muted"
            }

    companion object {
        fun fromOrNull(raw: String?): GateState? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** One gate's entry from `gatekeeper_runs.gates` — what a run-page chip renders. */
data class GateView(
    val gate: Gate,
    val state: GateState,
    val attempt: Long,
    val sweepAttempt: Long,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val counters: Map<String, Long>,
    val errorCode: String?,
    val errorDetail: String?,
) {
    val label: String
        get() = gate.shortLabel

    val badge: String
        get() = state.badge

    val failed: Boolean
        get() = state == GateState.FAILED

    /** Wall-clock for a finished gate, or elapsed-so-far for a running one; null when unstarted. */
    val duration: Duration?
        get() = startedAt?.let { Duration.between(it, endedAt ?: Instant.now()) }

    /** `4m 12s` / `41s` — chip-sized, never a raw ISO duration. */
    val durationLabel: String?
        get() =
            duration?.let {
                val minutes = it.toMinutes()
                if (minutes > 0) "${minutes}m ${it.seconds % 60}s" else "${it.seconds}s"
            }

    /** The counters worth a chip, in the order the cascade produces them. */
    val orderedCounters: List<Pair<String, Long>>
        get() = COUNTER_ORDER.mapNotNull { key -> counters[key]?.let { key to it } }

    /** Only a FAILED gate may be retriggered FROM_GATE (LLD §10). */
    val retriggerable: Boolean
        get() = failed

    companion object {
        private val COUNTER_ORDER =
            listOf(
                "seen",
                "neutral",
                "repeats",
                "corroborates",
                "contraFlagged",
                "forwarded",
                "escalated",
                "human",
                "truncated",
                "ctxDisagreed",
            )
    }
}

/**
 * The latest `gatekeeper_runs` doc for an intake, as the vishwamitra side reads it.
 *
 * There is no callback anywhere in this design (LLD §7.4, owner requirement #6): the Stage 3 poll
 * loop reads this doc and advances its own lifecycle. [absent] is the honest third answer to "how
 * is the cascade doing" — no doc exists yet, which is the normal state between publishing G1 and
 * the dispatcher's first transactional claim.
 */
data class GatekeeperRunView(
    val runRequestId: String,
    val intakeId: String,
    val stage3RunId: String,
    val judgeMode: JudgeMode?,
    val state: GatekeeperRunState,
    val supersededBy: String?,
    val gates: List<GateView>,
    val totals: Map<String, Long>,
    val llmSpendUsd: Double,
    val errorCode: String?,
    val errorDetail: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    val succeeded: Boolean
        get() = state == GatekeeperRunState.SUCCEEDED

    val failed: Boolean
        get() = state == GatekeeperRunState.FAILED

    val superseded: Boolean
        get() = state == GatekeeperRunState.SUPERSEDED

    /** The gate to blame, for the failure card — falls back to run-level codes (LLD §7.1). */
    val failedGate: GateView?
        get() = gates.firstOrNull { it.failed }

    /** Run-level code when FINALIZE broke and no gate carries one (LLD §7.1, VA-102). */
    val effectiveErrorCode: String?
        get() = failedGate?.errorCode ?: errorCode

    val effectiveErrorDetail: String?
        get() = failedGate?.errorDetail ?: errorDetail

    val shadowDisagreed: Long
        get() = totals["shadowDisagreed"] ?: 0L

    val pairsSeen: Long
        get() = totals["pairsSeen"] ?: 0L

    val decidedByGates: Long
        get() = totals["decidedByGates"] ?: 0L

    val escalatedLlm: Long
        get() = totals["escalatedLlm"] ?: 0L

    val escalatedHuman: Long
        get() = totals["escalatedHuman"] ?: 0L

    val badge: String
        get() =
            when (state) {
                GatekeeperRunState.SUCCEEDED -> "success"
                GatekeeperRunState.RUNNING -> "info"
                GatekeeperRunState.REQUESTED -> "muted"
                GatekeeperRunState.FAILED -> "danger"
                GatekeeperRunState.SUPERSEDED -> "warning"
            }

    companion object {
        /**
         * No run doc yet. The dispatcher creates it on its first claim, so this is what the poll
         * legitimately sees for the seconds between publish and claim — and, less happily, forever
         * if the publish never landed. The run page distinguishes the two by [Stage3Run]'s own
         * publish state, not by this.
         */
        fun absent(runRequestId: String, intakeId: String, stage3RunId: String): GatekeeperRunView =
            GatekeeperRunView(
                runRequestId = runRequestId,
                intakeId = intakeId,
                stage3RunId = stage3RunId,
                judgeMode = null,
                state = GatekeeperRunState.REQUESTED,
                supersededBy = null,
                gates = emptyList(),
                totals = emptyMap(),
                llmSpendUsd = 0.0,
                errorCode = null,
                errorDetail = null,
                createdAt = null,
                updatedAt = null,
            )
    }
}

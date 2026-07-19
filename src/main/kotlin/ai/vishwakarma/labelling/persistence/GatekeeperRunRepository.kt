package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.stage3.gatekeeper.Gate
import ai.vishwakarma.labelling.stage3.gatekeeper.GateState
import ai.vishwakarma.labelling.stage3.gatekeeper.GateView
import ai.vishwakarma.labelling.stage3.gatekeeper.GatekeeperRunState
import ai.vishwakarma.labelling.stage3.gatekeeper.GatekeeperRunView
import ai.vishwakarma.labelling.stage3.gatekeeper.JudgeMode
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository

/**
 * Read-only access to `gatekeeper_runs` (Gatekeeper LLD §7.1) — **lakshmana owns this collection**
 * (§7.4); vishwamitra never writes a byte of it. The Stage 3 poll loop reads the doc and advances
 * its own lifecycle from what it sees; there are no callbacks in the design.
 *
 * Immutable history: one doc per `runRequestId` forever, never deleted. A re-run therefore leaves
 * several docs for one intake, which is why the "current" run is the newest by `createdAt`.
 */
@Repository
class GatekeeperRunRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    /** Direct get by the id we minted — the read the poll loop uses once a run is under way. */
    fun findByRunRequestId(runRequestId: String): GatekeeperRunView? =
        col.document(runRequestId).get().await().takeIf { it.exists() }?.toView()

    /**
     * The intake's latest run (LLD §10: "the run page always operates on latest by (intakeId,
     * createdAt)"). Backed by the `(intakeId ASC, createdAt DESC)` composite in
     * `firestore.indexes.json` — the one index this integration adds.
     */
    fun findLatestByIntake(intakeId: String): GatekeeperRunView? =
        col.whereEqualTo("intakeId", intakeId)
            .orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.toView()

    private fun DocumentSnapshot.toView(): GatekeeperRunView =
        GatekeeperRunView(
            runRequestId = getString("runRequestId") ?: id,
            intakeId = getString("intakeId") ?: "",
            stage3RunId = getString("stage3RunId") ?: "",
            judgeMode = JudgeMode.fromOrNull(getString("judgeMode")),
            state =
                GatekeeperRunState.fromOrNull(getString("state")) ?: GatekeeperRunState.REQUESTED,
            supersededBy = getString("supersededBy"),
            gates = gateViews(),
            totals = longMap(nested("totals")),
            llmSpendUsd = (nested("totals")["llmSpendUsd"] as? Number)?.toDouble() ?: 0.0,
            errorCode = getString("errorCode"),
            errorDetail = getString("errorDetail"),
            createdAt = instant("createdAt"),
            updatedAt = instant("updatedAt"),
        )

    /**
     * Gate entries in cascade order, skipping keys the enum does not know. An unknown gate name is
     * a contract change, not a rendering problem — dropping it keeps this side readable rather than
     * inventing a chip for something it cannot describe.
     */
    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.gateViews(): List<GateView> {
        val gates = nested("gates")
        return Gate.entries.mapNotNull { gate ->
            val entry = gates[gate.name] as? Map<String, Any?> ?: return@mapNotNull null
            GateView(
                gate = gate,
                state = GateState.fromOrNull(entry["state"] as? String) ?: GateState.PENDING,
                attempt = (entry["attempt"] as? Number)?.toLong() ?: 0L,
                sweepAttempt = (entry["sweepAttempt"] as? Number)?.toLong() ?: 0L,
                startedAt = timestamp(entry["startedAt"]),
                endedAt = timestamp(entry["endedAt"]),
                counters = longMap(entry["counters"] as? Map<String, Any?> ?: emptyMap()),
                errorCode = entry["errorCode"] as? String,
                errorDetail = entry["errorDetail"] as? String,
            )
        }
    }

    /** Firestore hands nested timestamps back as `com.google.cloud.Timestamp`, not `Instant`. */
    private fun timestamp(value: Any?): java.time.Instant? =
        (value as? com.google.cloud.Timestamp)?.toDate()?.toInstant()

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.nested(field: String): Map<String, Any?> =
        get(field) as? Map<String, Any?> ?: emptyMap()

    private fun longMap(raw: Map<String, Any?>): Map<String, Long> =
        raw.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toLong() } }.toMap()

    companion object {
        const val COLLECTION = "gatekeeper_runs"
    }
}

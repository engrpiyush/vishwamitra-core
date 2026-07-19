package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.stage3.ClaimPair
import ai.vishwakarma.labelling.stage3.JudgeRelation
import ai.vishwakarma.labelling.stage3.JudgeVerdict
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository

/**
 * One gatekeeper verdict row read back off `stage3_edges` (Gatekeeper LLD §7.2).
 *
 * [relation] is **nullable and that is load-bearing**: the cascade writes a row for every pair it
 * touched, but a pair it routed to a human carries scores and an [escalationReason] with no
 * verdict. Reading a missing relation as NEUTRAL would silently convert "nobody has judged this
 * yet" into "judged, unrelated" — which is exactly the hollow-graph failure this integration has to
 * avoid, so the absence is preserved all the way to the caller.
 */
data class GatekeeperEdgeRow(
    val pair: ClaimPair,
    val withContext: Boolean,
    val relation: JudgeRelation?,
    val confidence: Double,
    val method: String?,
    val judgeModel: String?,
    val rationale: String?,
    val temporalNote: String?,
    val escalationReason: String?,
    val truncated: Boolean,
    val gkRunRequestId: String?,
) {
    fun toVerdict(): JudgeVerdict? =
        relation?.let {
            JudgeVerdict(
                relation = it,
                confidence = confidence,
                // The cascade is not an ensemble — there are no votes to record. An empty map is
                // the honest shape; a synthesised {relation: 1} would put a vote in the §13
                // calibration curve that no sampler ever cast.
                votes = emptyMap(),
                rationale = rationale,
                temporalNote = temporalNote,
                explanationRelevant = false,
                tie = false,
                floored = false,
            )
        }
}

/**
 * Reads the gatekeeper's verdicts out of `stage3_edges`.
 *
 * **Why this exists.** Lakshmana writes verdicts to Firestore only — D‑8 makes its Neo4j access
 * read-only, so the graph keeps vishwamitra as its single writer. But ASSEMBLE reads judged pairs
 * from the graph (`JUDGE_QUEUED {status:'JUDGED'}`), never from Firestore. Something therefore has
 * to carry gatekeeper verdicts across that boundary, and it has to be this side. That is what this
 * repository feeds: the JUDGE phase reads rows here and applies them through the same
 * `applyJudgeOutcome` path the ensemble uses, so ASSEMBLE and everything downstream needs no change
 * and the single-writer rule survives.
 *
 * Lookups are **direct gets**, never queries: the doc id is deterministic
 * (`{claimIdLow}|{claimIdHigh}|{bare|ctx}|GK`, LLD §7.3), mirroring vishwamitra's own `(pair,
 * variant, promptStamp)` key with `GK` in the stamp position. No composite index, and no chance of
 * colliding with an ensemble row — those end in a real `<impl>:<version>:<hash>` stamp.
 */
@Repository
class GatekeeperEdgeRepository(private val db: Firestore) {

    private val col
        get() = db.collection(Stage3EdgeRepository.COLLECTION)

    /**
     * Fetch every gatekeeper row for [pairs], both variants, in one batched `getAll`. Absent docs
     * simply don't appear — a pair the cascade has not written yet is not an error here.
     */
    fun findAll(pairs: Collection<ClaimPair>): Map<GatekeeperEdgeKey, GatekeeperEdgeRow> {
        if (pairs.isEmpty()) return emptyMap()
        val ids =
            pairs.distinct().flatMap { pair ->
                listOf(edgeId(pair, withContext = false), edgeId(pair, withContext = true))
            }
        val refs = ids.map { col.document(it) }
        return db.getAll(*refs.toTypedArray())
            .await()
            .filter { it.exists() }
            .mapNotNull { it.toRow() }
            .associateBy { GatekeeperEdgeKey(it.pair, it.withContext) }
    }

    private fun DocumentSnapshot.toRow(): GatekeeperEdgeRow? {
        val low = getString("claimIdLow") ?: return null
        val high = getString("claimIdHigh") ?: return null
        return GatekeeperEdgeRow(
            pair = ClaimPair(low, high),
            withContext = getBoolean("withContext") ?: false,
            relation = JudgeRelation.fromOrNull(getString("relation")),
            confidence = getDouble("confidence") ?: 0.0,
            method = getString("method"),
            judgeModel = getString("judgeModel"),
            rationale = getString("rationale"),
            temporalNote = getString("temporalNote"),
            escalationReason = getString("escalationReason"),
            truncated = getBoolean("truncated") ?: false,
            gkRunRequestId = getString("gkRunRequestId"),
        )
    }

    companion object {
        /** The stamp position that marks a row as the gatekeeper's (LLD §7.3). */
        const val STAMP = "GK"

        fun edgeId(pair: ClaimPair, withContext: Boolean): String =
            "${pair.a}|${pair.b}|${if (withContext) "ctx" else "bare"}|$STAMP"
    }
}

/** Lookup key for [GatekeeperEdgeRepository.findAll]'s result — a pair plus its §11.9 variant. */
data class GatekeeperEdgeKey(val pair: ClaimPair, val withContext: Boolean)

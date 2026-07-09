package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.stage3.ClaimPair
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * One human-labeled claim pair (`stage3_golden_pairs`, LLD §9.5/§13) — the golden set every
 * judge-prompt/threshold change is measured against. The doc id IS the pair key (`low|high`), so
 * one label exists per pair (relabeling overwrites) and the sampler's never-re-serve check is a
 * direct id lookup.
 */
data class Stage3GoldenPair(
    /** `claimIdLow|claimIdHigh`. */
    val pairKey: String,
    val claimIdLow: String,
    val claimIdHigh: String,
    val subjectId: String,
    /** REPEATS / CORROBORATES / CONTRADICTS / NEUTRAL / IRRELEVANT (§13). */
    val humanRelation: String,
    val labeledBy: String?,
    val labeledAt: Instant?,
    /** CALIBRATION (threshold tuning) / TEST (never tuned against). */
    val split: String,
    /** The subject's latest Stage 3 run when the label was recorded. */
    val sourceRunId: String?,
) {
    fun pair(): ClaimPair = ClaimPair(claimIdLow, claimIdHigh)

    companion object {
        fun keyOf(pair: ClaimPair): String = "${pair.a}|${pair.b}"
    }
}

@Repository
class Stage3GoldenPairRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun save(row: Stage3GoldenPair) {
        col.document(row.pairKey).set(row.toMap()).await()
    }

    fun findAll(): List<Stage3GoldenPair> =
        col.get().await().documents.map { it.toRow() }.sortedBy { it.pairKey }

    fun findBySubject(subjectId: String): List<Stage3GoldenPair> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toRow() }
            .sortedBy { it.pairKey }

    /** The sampler's exclusion set: which of [pairs] already carry a label. */
    fun labeledKeys(pairs: Collection<ClaimPair>): Set<String> {
        if (pairs.isEmpty()) return emptySet()
        val refs = pairs.map { col.document(Stage3GoldenPair.keyOf(it)) }.distinct()
        return db.getAll(*refs.toTypedArray()).await().filter { it.exists() }.map { it.id }.toSet()
    }

    private fun Stage3GoldenPair.toMap(): Map<String, Any?> =
        mapOf(
            "claimIdLow" to claimIdLow,
            "claimIdHigh" to claimIdHigh,
            "subjectId" to subjectId,
            "humanRelation" to humanRelation,
            "labeledBy" to labeledBy,
            "labeledAt" to labeledAt.toTimestamp(),
            "split" to split,
            "sourceRunId" to sourceRunId,
        )

    private fun DocumentSnapshot.toRow(): Stage3GoldenPair =
        Stage3GoldenPair(
            pairKey = id,
            claimIdLow = getString("claimIdLow") ?: "",
            claimIdHigh = getString("claimIdHigh") ?: "",
            subjectId = getString("subjectId") ?: "",
            humanRelation = getString("humanRelation") ?: "",
            labeledBy = getString("labeledBy"),
            labeledAt = instant("labeledAt"),
            split = getString("split") ?: "TEST",
            sourceRunId = getString("sourceRunId"),
        )

    companion object {
        const val COLLECTION = "stage3_golden_pairs"
    }
}

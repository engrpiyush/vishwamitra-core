package ai.vishwakarma.labelling.persistence

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * The subject-level authenticity ledger record (Stage 3.5 LLD §5) — the Subject Authenticity Index
 * frozen at the Q6 publish, one doc per subject (doc id = subjectId, replace-on-set). Stage 4 and
 * the profile PDF read this copy, never Neo4j.
 */
data class SubjectScoreRecord(
    val subjectId: String,
    /** SAI in [0,1]. */
    val score: Double,
    /** round(100·score) — the headline number. */
    val display: Int,
    /** STRONG / GOOD / MODERATE / WEAK / UNSUPPORTED. */
    val band: String,
    /** [ai.vishwakarma.labelling.stage3.SubjectScoreComponents] flattened (B/D/I/V/C + Φs). */
    val components: Map<String, Double>,
    /** [ai.vishwakarma.labelling.stage3.SubjectScoreInputs] flattened — the derivation counts. */
    val inputs: Map<String, Any?>,
    val factCount: Int,
    val claimCount: Int,
    /** The run whose publish froze this value. */
    val scoreRunId: String,
    val publishedAt: Instant?,
    val publishedBy: String?,
    /** [PublishContract.VERSION] at publish time; 1 = a pre-contract (legacy) publish. */
    val publishContractVersion: Int = 1,
    /** [PublishContract.asMap] frozen verbatim — the consumer's STATED/DERIVED/HUMAN legend. */
    val publishContract: Map<String, Any?> = emptyMap(),
)

@Repository
class SubjectScoreRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    /** Replace-on-set: a re-publish (reopen → publish, re-run → publish) overwrites in place. */
    fun save(record: SubjectScoreRecord) {
        col.document(record.subjectId).set(record.toMap()).await()
    }

    fun find(subjectId: String): SubjectScoreRecord? =
        col.document(subjectId).get().await().takeIf { it.exists() }?.toRecord()

    private fun SubjectScoreRecord.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "score" to score,
            "display" to display,
            "band" to band,
            "components" to components,
            "inputs" to inputs,
            "factCount" to factCount,
            "claimCount" to claimCount,
            "scoreRunId" to scoreRunId,
            "publishedAt" to publishedAt.toTimestamp(),
            "publishedBy" to publishedBy,
            "publishContractVersion" to publishContractVersion,
            "publishContract" to publishContract,
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toRecord(): SubjectScoreRecord =
        SubjectScoreRecord(
            subjectId = getString("subjectId") ?: id,
            score = getDouble("score") ?: 0.0,
            display = (get("display") as? Number)?.toInt() ?: 0,
            band = getString("band") ?: "UNSUPPORTED",
            components =
                (get("components") as? Map<String, Any?>)
                    .orEmpty()
                    .mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }
                    .toMap(),
            inputs = (get("inputs") as? Map<String, Any?>).orEmpty(),
            factCount = (get("factCount") as? Number)?.toInt() ?: 0,
            claimCount = (get("claimCount") as? Number)?.toInt() ?: 0,
            scoreRunId = getString("scoreRunId") ?: "",
            publishedAt = instant("publishedAt"),
            publishedBy = getString("publishedBy"),
            publishContractVersion = (get("publishContractVersion") as? Number)?.toInt() ?: 1,
            publishContract = (get("publishContract") as? Map<String, Any?>).orEmpty(),
        )

    companion object {
        const val COLLECTION = "subject_scores"
    }
}

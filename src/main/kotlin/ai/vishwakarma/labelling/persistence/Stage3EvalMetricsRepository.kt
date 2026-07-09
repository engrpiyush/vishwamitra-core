package ai.vishwakarma.labelling.persistence

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** One confusion-matrix cell: how often the ensemble said [predicted] when humans said [human]. */
data class EvalConfusionCell(val human: String, val predicted: String, val count: Long)

/** Per-relation precision/recall/F1 vs the TEST-split human labels (LLD §13). */
data class EvalRelationMetrics(
    val precision: Double?,
    val recall: Double?,
    val f1: Double?,
    /** TEST-split pairs whose human label is this relation. */
    val support: Long,
)

/** The §13 blocking-recall metric: labeled non-NEUTRAL pairs that survived PRUNED blocking. */
data class EvalBlockingRecall(
    val nonNeutralLabeled: Long,
    val survivedBlocking: Long,
    val recall: Double?,
)

/** One reliability-diagram bucket: ensemble agreement vs human agreement (LLD §13). */
data class EvalCalibrationBucket(
    val lowerBound: Double,
    val upperBound: Double,
    val pairs: Long,
    val meanAgreement: Double?,
    val humanAgreementRate: Double?,
)

/** The §13 score sanity flags on the reference subject (null = not applicable on this corpus). */
data class EvalScoreSanity(
    val referenceSubjectId: String,
    val documentsOverEndorsed: Boolean?,
    val endorsedOverSelfPraise: Boolean?,
    val explainedContradictionsRecover: Boolean?,
    val meanAnchoredBelief: Double?,
    val meanEndorsedBelief: Double?,
    val meanSelfPraiseBelief: Double?,
)

/**
 * One metrics-job outcome (`stage3_eval_metrics`, as-built §13) — keyed by judge-prompt stamp +
 * params hash, so every prompt/threshold revision owns one comparable record and re-running the job
 * for the same configuration refreshes it in place.
 */
data class Stage3EvalMetricsRecord(
    /** `promptStamp|paramsHash` — the §13 comparison key and the doc id. */
    val id: String,
    val promptStamp: String,
    val paramsHash: String,
    val ranAt: Instant?,
    val ranBy: String?,
    val labeledPairs: Long,
    val testPairs: Long,
    val calibrationPairs: Long,
    /** TEST pairs with no cached bare verdict under this prompt stamp (excluded from P/R). */
    val unjudgedTestPairs: Long,
    val confusion: List<EvalConfusionCell>,
    val perRelation: Map<String, EvalRelationMetrics>,
    val blockingRecall: EvalBlockingRecall,
    val calibration: List<EvalCalibrationBucket>,
    val sanity: EvalScoreSanity?,
)

@Repository
class Stage3EvalMetricsRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun save(record: Stage3EvalMetricsRecord) {
        col.document(record.id).set(record.toMap()).await()
    }

    /** Every persisted record, newest first — the §13 cross-version comparison read. */
    fun findAll(): List<Stage3EvalMetricsRecord> =
        col.get().await().documents.map { it.toRecord() }.sortedByDescending { it.ranAt }

    private fun Stage3EvalMetricsRecord.toMap(): Map<String, Any?> =
        mapOf(
            "promptStamp" to promptStamp,
            "paramsHash" to paramsHash,
            "ranAt" to ranAt.toTimestamp(),
            "ranBy" to ranBy,
            "labeledPairs" to labeledPairs,
            "testPairs" to testPairs,
            "calibrationPairs" to calibrationPairs,
            "unjudgedTestPairs" to unjudgedTestPairs,
            "confusion" to
                confusion.map {
                    mapOf("human" to it.human, "predicted" to it.predicted, "count" to it.count)
                },
            "perRelation" to
                perRelation.mapValues { (_, m) ->
                    mapOf(
                        "precision" to m.precision,
                        "recall" to m.recall,
                        "f1" to m.f1,
                        "support" to m.support,
                    )
                },
            "blockingRecall" to
                mapOf(
                    "nonNeutralLabeled" to blockingRecall.nonNeutralLabeled,
                    "survivedBlocking" to blockingRecall.survivedBlocking,
                    "recall" to blockingRecall.recall,
                ),
            "calibration" to
                calibration.map {
                    mapOf(
                        "lowerBound" to it.lowerBound,
                        "upperBound" to it.upperBound,
                        "pairs" to it.pairs,
                        "meanAgreement" to it.meanAgreement,
                        "humanAgreementRate" to it.humanAgreementRate,
                    )
                },
            "sanity" to
                sanity?.let {
                    mapOf(
                        "referenceSubjectId" to it.referenceSubjectId,
                        "documentsOverEndorsed" to it.documentsOverEndorsed,
                        "endorsedOverSelfPraise" to it.endorsedOverSelfPraise,
                        "explainedContradictionsRecover" to it.explainedContradictionsRecover,
                        "meanAnchoredBelief" to it.meanAnchoredBelief,
                        "meanEndorsedBelief" to it.meanEndorsedBelief,
                        "meanSelfPraiseBelief" to it.meanSelfPraiseBelief,
                    )
                },
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toRecord(): Stage3EvalMetricsRecord {
        val confusionRaw = (get("confusion") as? List<Map<String, Any?>>).orEmpty()
        val perRelationRaw = (get("perRelation") as? Map<String, Map<String, Any?>>).orEmpty()
        val blockingRaw = (get("blockingRecall") as? Map<String, Any?>).orEmpty()
        val calibrationRaw = (get("calibration") as? List<Map<String, Any?>>).orEmpty()
        val sanityRaw = get("sanity") as? Map<String, Any?>
        fun num(m: Map<String, Any?>, k: String): Double? = (m[k] as? Number)?.toDouble()
        fun count(m: Map<String, Any?>, k: String): Long = (m[k] as? Number)?.toLong() ?: 0L
        return Stage3EvalMetricsRecord(
            id = id,
            promptStamp = getString("promptStamp") ?: "",
            paramsHash = getString("paramsHash") ?: "",
            ranAt = instant("ranAt"),
            ranBy = getString("ranBy"),
            labeledPairs = getLong("labeledPairs") ?: 0L,
            testPairs = getLong("testPairs") ?: 0L,
            calibrationPairs = getLong("calibrationPairs") ?: 0L,
            unjudgedTestPairs = getLong("unjudgedTestPairs") ?: 0L,
            confusion =
                confusionRaw.map {
                    EvalConfusionCell(
                        human = it["human"] as? String ?: "",
                        predicted = it["predicted"] as? String ?: "",
                        count = count(it, "count"),
                    )
                },
            perRelation =
                perRelationRaw.mapValues { (_, m) ->
                    EvalRelationMetrics(
                        precision = num(m, "precision"),
                        recall = num(m, "recall"),
                        f1 = num(m, "f1"),
                        support = count(m, "support"),
                    )
                },
            blockingRecall =
                EvalBlockingRecall(
                    nonNeutralLabeled = count(blockingRaw, "nonNeutralLabeled"),
                    survivedBlocking = count(blockingRaw, "survivedBlocking"),
                    recall = num(blockingRaw, "recall"),
                ),
            calibration =
                calibrationRaw.map {
                    EvalCalibrationBucket(
                        lowerBound = num(it, "lowerBound") ?: 0.0,
                        upperBound = num(it, "upperBound") ?: 0.0,
                        pairs = count(it, "pairs"),
                        meanAgreement = num(it, "meanAgreement"),
                        humanAgreementRate = num(it, "humanAgreementRate"),
                    )
                },
            sanity =
                sanityRaw?.let {
                    EvalScoreSanity(
                        referenceSubjectId = it["referenceSubjectId"] as? String ?: "",
                        documentsOverEndorsed = it["documentsOverEndorsed"] as? Boolean,
                        endorsedOverSelfPraise = it["endorsedOverSelfPraise"] as? Boolean,
                        explainedContradictionsRecover =
                            it["explainedContradictionsRecover"] as? Boolean,
                        meanAnchoredBelief = num(it, "meanAnchoredBelief"),
                        meanEndorsedBelief = num(it, "meanEndorsedBelief"),
                        meanSelfPraiseBelief = num(it, "meanSelfPraiseBelief"),
                    )
                },
        )
    }

    companion object {
        const val COLLECTION = "stage3_eval_metrics"
    }
}

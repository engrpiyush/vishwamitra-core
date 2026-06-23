package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.BaseKind
import ai.vishwakarma.labelling.domain.Hyperparams
import ai.vishwakarma.labelling.domain.JobStatus
import ai.vishwakarma.labelling.domain.TuningJob
import ai.vishwakarma.labelling.domain.TuningMethod
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class TuningJobRepository(private val db: Firestore) {

    private val col get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): TuningJob? =
        col.document(id).get().await().takeIf { it.exists() }?.toJob()

    fun findAll(): List<TuningJob> =
        col.orderBy("submittedAt", Query.Direction.DESCENDING).get().await().documents.map { it.toJob() }

    fun anyActive(): Boolean =
        findAll().any { it.status == JobStatus.RUNNING || it.status == JobStatus.PENDING }

    fun save(job: TuningJob) {
        col.document(job.id).set(job.toMap()).await()
    }

    private fun TuningJob.toMap(): Map<String, Any?> = mapOf(
        "vertexJobName" to vertexJobName,
        "method" to method.name,
        "baseKind" to baseKind.name,
        "baseModelId" to baseModelId,
        "parentVersionId" to parentVersionId,
        "datasetExportId" to datasetExportId,
        "hyperparams" to mapOf("epochCount" to hyperparams.epochCount, "adapterSize" to hyperparams.adapterSize, "learningRate" to hyperparams.learningRate),
        "status" to status.name,
        "outputUri" to outputUri,
        "vertexModelResource" to vertexModelResource,
        "modelVersionId" to modelVersionId,
        "error" to error,
        "submittedBy" to submittedBy,
        "submittedAt" to (submittedAt ?: Instant.now()).toTimestamp(),
        "finishedAt" to finishedAt?.toTimestamp(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toJob(): TuningJob {
        val hp = get("hyperparams") as? Map<String, Any?> ?: emptyMap()
        return TuningJob(
            id = id,
            vertexJobName = getString("vertexJobName"),
            method = runCatching { TuningMethod.valueOf(getString("method") ?: "SFT") }.getOrDefault(TuningMethod.SFT),
            baseKind = runCatching { BaseKind.valueOf(getString("baseKind") ?: "FOUNDATION") }.getOrDefault(BaseKind.FOUNDATION),
            baseModelId = getString("baseModelId") ?: "",
            parentVersionId = getString("parentVersionId"),
            datasetExportId = getString("datasetExportId") ?: "",
            hyperparams = Hyperparams(
                epochCount = (hp["epochCount"] as? Number)?.toInt() ?: 3,
                adapterSize = hp["adapterSize"] as? String ?: "ADAPTER_SIZE_FOUR",
                learningRate = (hp["learningRate"] as? Number)?.toDouble() ?: 0.0002,
            ),
            status = runCatching { JobStatus.valueOf(getString("status") ?: "PENDING") }.getOrDefault(JobStatus.PENDING),
            outputUri = getString("outputUri") ?: "",
            vertexModelResource = getString("vertexModelResource"),
            modelVersionId = getString("modelVersionId"),
            error = getString("error"),
            submittedBy = getString("submittedBy"),
            submittedAt = instant("submittedAt"),
            finishedAt = instant("finishedAt"),
        )
    }

    companion object {
        const val COLLECTION = "tuning_jobs"
    }
}

package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class Stage2JobRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Stage2Job? =
        col.document(id).get().await().takeIf { it.exists() }?.toStage2Job()

    /** All Stage 2 jobs for a subject, newest first. */
    fun findBySubject(subjectId: String): List<Stage2Job> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toStage2Job() }
            .sortedByDescending { it.createdAt }

    fun save(job: Stage2Job) {
        col.document(job.id).set(job.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun Stage2Job.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "assetId" to assetId,
            "modality" to modality.name,
            "status" to status.name,
            "externalOperationId" to externalOperationId,
            "decodingAttempt" to decodingAttempt,
            "transcriptUri" to transcriptUri,
            "claimCount" to claimCount,
            "error" to error,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "startedAt" to startedAt.toTimestamp(),
            "finishedAt" to finishedAt.toTimestamp(),
        )

    private fun DocumentSnapshot.toStage2Job(): Stage2Job =
        Stage2Job(
            id = id,
            subjectId = getString("subjectId") ?: "",
            assetId = getString("assetId") ?: "",
            modality = AssetModality.fromOrNull(getString("modality")) ?: AssetModality.AUDIO,
            status = Stage2JobStatus.fromOrNull(getString("status")) ?: Stage2JobStatus.PENDING,
            externalOperationId = getString("externalOperationId"),
            decodingAttempt = getLong("decodingAttempt")?.toInt() ?: 0,
            transcriptUri = getString("transcriptUri"),
            claimCount = getLong("claimCount")?.toInt(),
            error = getString("error"),
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            startedAt = instant("startedAt"),
            finishedAt = instant("finishedAt"),
        )

    companion object {
        const val COLLECTION = "stage2_jobs"
    }
}

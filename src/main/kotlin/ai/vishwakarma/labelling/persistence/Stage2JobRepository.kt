package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
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
            "extractingSince" to extractingSince.toTimestamp(),
            "speakerRoles" to
                speakerRoles?.mapValues { (_, a) ->
                    mapOf(
                        "role" to a.role.name,
                        "relationship" to a.relationship?.name,
                        "name" to a.name
                    )
                },
            "speakerSamples" to speakerSamples,
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
            extractingSince = instant("extractingSince"),
            speakerRoles = speakerRoles(),
            speakerSamples = speakerSamplesMap(),
        )

    /**
     * Reconstruct the [Stage2Job.speakerRoles] binding (§12.4); tolerant of missing/garbage rows.
     */
    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.speakerRoles(): Map<String, SpeakerAssignment>? {
        val raw = get("speakerRoles") as? Map<String, Any?> ?: return null
        val parsed =
            raw.mapNotNull { (label, value) ->
                val fields = value as? Map<String, Any?> ?: return@mapNotNull null
                val role =
                    SpeakerRole.fromOrNull(fields["role"] as? String) ?: return@mapNotNull null
                label to
                    SpeakerAssignment(
                        role = role,
                        relationship = Relationship.fromOrNull(fields["relationship"] as? String),
                        name = fields["name"] as? String,
                    )
            }
        return parsed.toMap().ifEmpty { null }
    }

    /** Reconstruct the [Stage2Job.speakerSamples] preview map (§12.4); string values only. */
    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.speakerSamplesMap(): Map<String, String>? {
        val raw = get("speakerSamples") as? Map<String, Any?> ?: return null
        return raw.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap().ifEmpty { null }
    }

    companion object {
        const val COLLECTION = "stage2_jobs"
    }
}

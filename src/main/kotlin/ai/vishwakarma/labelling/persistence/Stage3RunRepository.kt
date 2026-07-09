package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class Stage3RunRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Stage3Run? =
        col.document(id).get().await().takeIf { it.exists() }?.toStage3Run()

    /** All Stage 3 runs for a subject, newest first. */
    fun findBySubject(subjectId: String): List<Stage3Run> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toStage3Run() }
            .sortedByDescending { it.createdAt }

    /** The subject's non-terminal run, if any — the one-active-run-per-subject guard's read. */
    fun findActiveBySubject(subjectId: String): Stage3Run? =
        findBySubject(subjectId).firstOrNull { !it.status.terminal }

    fun save(run: Stage3Run) {
        col.document(run.id).set(run.toMap()).await()
    }

    private fun Stage3Run.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "status" to status.name,
            "failedPhase" to failedPhase?.name,
            "fresh" to fresh,
            "paramsSnapshot" to paramsSnapshot,
            "counters" to counters,
            "cursors" to cursors,
            "converged" to converged,
            "iterations" to iterations,
            "publishedAt" to publishedAt.toTimestamp(),
            "publishedBy" to publishedBy,
            "reviewSkipped" to reviewSkipped,
            "error" to error,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "startedAt" to startedAt.toTimestamp(),
            "finishedAt" to finishedAt.toTimestamp(),
            "phaseSince" to phaseSince.toTimestamp(),
        )

    private fun DocumentSnapshot.toStage3Run(): Stage3Run =
        Stage3Run(
            id = id,
            subjectId = getString("subjectId") ?: "",
            status = Stage3RunStatus.fromOrNull(getString("status")) ?: Stage3RunStatus.PENDING,
            failedPhase = Stage3RunStatus.fromOrNull(getString("failedPhase")),
            fresh = getBoolean("fresh") ?: false,
            paramsSnapshot = getString("paramsSnapshot"),
            counters = counterMap("counters"),
            cursors = stringMap("cursors"),
            converged = getBoolean("converged"),
            iterations = getLong("iterations")?.toInt(),
            publishedAt = instant("publishedAt"),
            publishedBy = getString("publishedBy"),
            reviewSkipped = getBoolean("reviewSkipped") ?: false,
            error = getString("error"),
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            startedAt = instant("startedAt"),
            finishedAt = instant("finishedAt"),
            phaseSince = instant("phaseSince"),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.counterMap(field: String): Map<String, Long> {
        val raw = get(field) as? Map<String, Any?> ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toLong() } }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.stringMap(field: String): Map<String, String> {
        val raw = get(field) as? Map<String, Any?> ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    companion object {
        const val COLLECTION = "stage3_runs"
    }
}

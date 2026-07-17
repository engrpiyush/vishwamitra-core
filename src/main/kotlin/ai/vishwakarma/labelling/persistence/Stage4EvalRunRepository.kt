package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.EvalRunStatus
import ai.vishwakarma.labelling.domain.Stage4EvalRun
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** `stage4_eval_runs` (LLD §14, VA-60) — the post-tune eval's submit-then-poll record. */
@Repository
class Stage4EvalRunRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Stage4EvalRun? =
        col.document(id).get().await().takeIf { it.exists() }?.toEvalRun()

    /** Eval runs for one version, newest first. */
    fun findByVersion(versionId: String): List<Stage4EvalRun> =
        col.whereEqualTo("versionId", versionId)
            .get()
            .await()
            .documents
            .map { it.toEvalRun() }
            .sortedByDescending { it.createdAt }

    /** Any non-terminal eval run (transports are exclusive resources — one eval at a time). */
    fun findActive(): Stage4EvalRun? =
        col.get().await().documents.map { it.toEvalRun() }.firstOrNull { !it.status.terminal }

    fun save(run: Stage4EvalRun) {
        col.document(run.id).set(run.toMap()).await()
    }

    private fun Stage4EvalRun.toMap(): Map<String, Any?> =
        mapOf(
            "versionId" to versionId,
            "subjectId" to subjectId,
            "transport" to transport,
            "status" to status.name,
            "counters" to counters,
            "error" to error,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "finishedAt" to finishedAt.toTimestamp(),
        )

    private fun DocumentSnapshot.toEvalRun(): Stage4EvalRun =
        Stage4EvalRun(
            id = id,
            versionId = getString("versionId") ?: "",
            subjectId = getString("subjectId") ?: "",
            transport = getString("transport") ?: "",
            status = EvalRunStatus.fromOrNull(getString("status")) ?: EvalRunStatus.FAILED,
            counters = counterMap("counters"),
            error = getString("error"),
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            finishedAt = instant("finishedAt"),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.counterMap(field: String): Map<String, Long> {
        val raw = get(field) as? Map<String, Any?> ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toLong() } }.toMap()
    }

    companion object {
        const val COLLECTION = "stage4_eval_runs"
    }
}

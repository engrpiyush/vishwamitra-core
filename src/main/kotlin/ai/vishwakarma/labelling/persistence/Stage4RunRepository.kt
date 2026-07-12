package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Stage4Run
import ai.vishwakarma.labelling.domain.Stage4RunStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** `stage4_runs` (LLD §6) — the submit-then-poll record mirroring [Stage3RunRepository]. */
@Repository
class Stage4RunRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Stage4Run? =
        col.document(id).get().await().takeIf { it.exists() }?.toStage4Run()

    /** All Stage 4 runs for a subject, newest first. */
    fun findBySubject(subjectId: String): List<Stage4Run> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toStage4Run() }
            .sortedByDescending { it.createdAt }

    /** The subject's non-terminal run, if any — the one-active-run-per-subject guard's read. */
    fun findActiveBySubject(subjectId: String): Stage4Run? =
        findBySubject(subjectId).firstOrNull { !it.status.terminal }

    fun save(run: Stage4Run) {
        col.document(run.id).set(run.toMap()).await()
    }

    private fun Stage4Run.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "scoreRunId" to scoreRunId,
            "personaHash" to personaHash,
            "status" to status.name,
            "failedPhase" to failedPhase?.name,
            "fresh" to fresh,
            "paramsSnapshot" to paramsSnapshot,
            "counters" to counters,
            "cursors" to cursors,
            "error" to error,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "startedAt" to startedAt.toTimestamp(),
            "finishedAt" to finishedAt.toTimestamp(),
            "phaseSince" to phaseSince.toTimestamp(),
        )

    private fun DocumentSnapshot.toStage4Run(): Stage4Run =
        Stage4Run(
            id = id,
            subjectId = getString("subjectId") ?: "",
            scoreRunId = getString("scoreRunId"),
            personaHash = getString("personaHash"),
            status = Stage4RunStatus.fromOrNull(getString("status")) ?: Stage4RunStatus.PENDING,
            failedPhase = Stage4RunStatus.fromOrNull(getString("failedPhase")),
            fresh = getBoolean("fresh") ?: false,
            paramsSnapshot = getString("paramsSnapshot"),
            counters = counterMap("counters"),
            cursors = stringMap("cursors"),
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
        const val COLLECTION = "stage4_runs"
    }
}

package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.EvalProbeKind
import ai.vishwakarma.labelling.domain.ExpectedBehavior
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4EvalProbe
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** `stage4_eval_probes` (LLD §14, VA-60) — one doc per probe, the report's transcript source. */
@Repository
class Stage4EvalProbeRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    /** Every probe of one eval run, stable order (the PROBING cursor + the transcript table). */
    fun findByEvalRun(evalRunId: String): List<Stage4EvalProbe> =
        col.whereEqualTo("evalRunId", evalRunId)
            .get()
            .await()
            .documents
            .map { it.toProbe() }
            .sortedBy { it.id }

    fun save(probe: Stage4EvalProbe) {
        col.document(probe.id).set(probe.toMap()).await()
    }

    private fun Stage4EvalProbe.toMap(): Map<String, Any?> =
        mapOf(
            "evalRunId" to evalRunId,
            "versionId" to versionId,
            "kind" to kind.name,
            "rowId" to rowId,
            "category" to category?.name,
            "safetyClass" to safetyClass,
            "expectedBehavior" to expectedBehavior.name,
            "question" to question,
            "referenceAnswer" to referenceAnswer,
            "sourceExampleId" to sourceExampleId,
            "reply" to reply,
            "observedBehavior" to observedBehavior?.name,
            "behaviorMatch" to behaviorMatch,
            "axes" to axes,
            "rationale" to rationale,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "answeredAt" to answeredAt.toTimestamp(),
        )

    private fun DocumentSnapshot.toProbe(): Stage4EvalProbe =
        Stage4EvalProbe(
            id = id,
            evalRunId = getString("evalRunId") ?: "",
            versionId = getString("versionId") ?: "",
            kind = EvalProbeKind.fromOrNull(getString("kind")) ?: EvalProbeKind.CORE,
            rowId = getLong("rowId")?.toInt(),
            category = Stage4Category.fromOrNull(getString("category")),
            safetyClass = getString("safetyClass"),
            expectedBehavior =
                ExpectedBehavior.fromOrNull(getString("expectedBehavior"))
                    ?: ExpectedBehavior.ASSERT,
            question = getString("question") ?: "",
            referenceAnswer = getString("referenceAnswer"),
            sourceExampleId = getString("sourceExampleId"),
            reply = getString("reply"),
            observedBehavior = ExpectedBehavior.fromOrNull(getString("observedBehavior")),
            behaviorMatch = getBoolean("behaviorMatch"),
            axes = stringMap("axes"),
            rationale = getString("rationale"),
            createdAt = instant("createdAt"),
            answeredAt = instant("answeredAt"),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.stringMap(field: String): Map<String, String> {
        val raw = get(field) as? Map<String, Any?> ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    companion object {
        const val COLLECTION = "stage4_eval_probes"
    }
}

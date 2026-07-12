package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.JudgeAxisResult
import ai.vishwakarma.labelling.domain.JudgeVerdict
import ai.vishwakarma.labelling.domain.Stage4Judgment
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * `stage4_judgments` (LLD §6) — one doc per judge pass, append-only: this collection is the future
 * judge-distillation set, so verdicts are never rewritten in place.
 */
@Repository
class Stage4JudgmentRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Stage4Judgment? =
        col.document(id).get().await().takeIf { it.exists() }?.toJudgment()

    /** All passes over one example, newest first (an edited example re-judges, §11). */
    fun findByExample(exampleId: String): List<Stage4Judgment> =
        col.whereEqualTo("exampleId", exampleId)
            .get()
            .await()
            .documents
            .map { it.toJudgment() }
            .sortedByDescending { it.createdAt }

    fun findBySubject(subjectId: String): List<Stage4Judgment> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toJudgment() }
            .sortedByDescending { it.createdAt }

    fun save(judgment: Stage4Judgment) {
        col.document(judgment.id).set(judgment.toMap()).await()
    }

    private fun Stage4Judgment.toMap(): Map<String, Any?> =
        mapOf(
            "exampleId" to exampleId,
            "runId" to runId,
            "subjectId" to subjectId,
            "axes" to
                axes.mapValues { (_, axis) ->
                    mapOf(
                        "verdict" to axis.verdict.name,
                        "votes" to axis.votes,
                        "rationale" to axis.rationale,
                    )
                },
            "overall" to overall.name,
            "judgePromptVersion" to judgePromptVersion,
            "judgePromptHash" to judgePromptHash,
            "model" to model,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toJudgment(): Stage4Judgment {
        val axesRaw = get("axes") as? Map<String, Any?> ?: emptyMap()
        val axes =
            axesRaw.mapNotNull { (name, value) ->
                val m = value as? Map<String, Any?> ?: return@mapNotNull null
                val verdict =
                    JudgeVerdict.fromOrNull(m["verdict"] as? String) ?: return@mapNotNull null
                val votesRaw = m["votes"] as? Map<String, Any?> ?: emptyMap()
                name to
                    JudgeAxisResult(
                        verdict = verdict,
                        votes =
                            votesRaw
                                .mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toInt() } }
                                .toMap(),
                        rationale = m["rationale"] as? String,
                    )
            }
        return Stage4Judgment(
            id = id,
            exampleId = getString("exampleId") ?: "",
            runId = getString("runId"),
            subjectId = getString("subjectId"),
            axes = axes.toMap(),
            overall = JudgeVerdict.fromOrNull(getString("overall")) ?: JudgeVerdict.PASS,
            judgePromptVersion = getLong("judgePromptVersion")?.toInt(),
            judgePromptHash = getString("judgePromptHash"),
            model = getString("model"),
            createdAt = instant("createdAt"),
        )
    }

    companion object {
        const val COLLECTION = "stage4_judgments"
    }
}

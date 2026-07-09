package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.stage3.ClaimPair
import ai.vishwakarma.labelling.stage3.JudgeRelation
import ai.vishwakarma.labelling.stage3.JudgeVerdict
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * One cached judge verdict (`stage3_edges`, LLD §9.5) — one document per (pair, variant, prompt).
 * The doc id IS the LLD cache key `(claimIdLow, claimIdHigh, withContext, promptHash)`, so lookups
 * are direct gets and a prompt-row bump naturally never hits. Beyond the reuse role this is the
 * audit trail that outlives graph rebuilds: the full vote distribution feeds the §13 calibration
 * curve, [judgeModel] attributes every number, and [overridden] (VA-18 dismiss) permanently
 * excludes an operator-rejected verdict from future hits.
 */
data class Stage3EdgeVerdict(
    val id: String,
    val subjectId: String,
    val claimIdLow: String,
    val claimIdHigh: String,
    val withContext: Boolean,
    /** The sampler's full `<impl>:<version>:<hash>` stamp — the "promptHash" leg of the key. */
    val promptStamp: String,
    val relation: String,
    val confidence: Double,
    val votes: Map<String, Long>,
    val rationale: String?,
    val temporalNote: String?,
    /** withContext variants: the judged "is this explanation relevant?" majority (§11.9). */
    val explanationRelevant: Boolean?,
    /** withContext variants: hash of the sidecar texts judged — a mismatch is a cache miss. */
    val explanationHash: String?,
    val floored: Boolean,
    val tie: Boolean,
    val judgeModel: String,
    val judgedAt: Instant?,
    /** Set by VA-18 dismiss: operator rejected this verdict — never serve it again. */
    val overridden: Boolean = false,
) {
    fun toVerdict(): JudgeVerdict =
        JudgeVerdict(
            relation = JudgeRelation.fromOrNull(relation) ?: JudgeRelation.NEUTRAL,
            confidence = confidence,
            votes = votes.mapValues { it.value.toInt() },
            rationale = rationale,
            temporalNote = temporalNote,
            explanationRelevant = explanationRelevant ?: false,
            tie = tie,
            floored = floored,
        )

    companion object {
        fun cacheId(pair: ClaimPair, withContext: Boolean, promptStamp: String): String =
            "${pair.a}|${pair.b}|${if (withContext) "ctx" else "bare"}|$promptStamp"

        fun of(
            subjectId: String,
            pair: ClaimPair,
            withContext: Boolean,
            promptStamp: String,
            verdict: JudgeVerdict,
            explanationHash: String?,
            judgeModel: String,
            judgedAt: Instant,
        ): Stage3EdgeVerdict =
            Stage3EdgeVerdict(
                id = cacheId(pair, withContext, promptStamp),
                subjectId = subjectId,
                claimIdLow = pair.a,
                claimIdHigh = pair.b,
                withContext = withContext,
                promptStamp = promptStamp,
                relation = verdict.relation.name,
                confidence = verdict.confidence,
                votes = verdict.votes.mapValues { it.value.toLong() },
                rationale = verdict.rationale,
                temporalNote = verdict.temporalNote,
                explanationRelevant = if (withContext) verdict.explanationRelevant else null,
                explanationHash = explanationHash,
                floored = verdict.floored,
                tie = verdict.tie,
                judgeModel = judgeModel,
                judgedAt = judgedAt,
            )
    }
}

@Repository
class Stage3EdgeRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    /** Direct-get batch lookup by cache id; absent and unparseable docs simply don't hit. */
    fun findAll(ids: Collection<String>): Map<String, Stage3EdgeVerdict> {
        if (ids.isEmpty()) return emptyMap()
        val refs = ids.distinct().map { col.document(it) }
        return db.getAll(*refs.toTypedArray())
            .await()
            .filter { it.exists() }
            .map { it.toVerdictRow() }
            .associateBy { it.id }
    }

    fun saveAll(rows: List<Stage3EdgeVerdict>) {
        if (rows.isEmpty()) return
        rows.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.set(col.document(it.id), it.toMap()) }
            batch.commit().await()
        }
    }

    /**
     * VA-18 dismiss: mark every cached variant/prompt-version of the pair overridden — the operator
     * rejected the verdict itself, so no stamp of it may ever hit again. Returns docs flagged.
     */
    fun markOverridden(claimIdLow: String, claimIdHigh: String): Int {
        val docs =
            col.whereEqualTo("claimIdLow", claimIdLow)
                .whereEqualTo("claimIdHigh", claimIdHigh)
                .get()
                .await()
                .documents
        if (docs.isEmpty()) return 0
        val batch = db.batch()
        docs.forEach { batch.update(it.reference, "overridden", true) }
        batch.commit().await()
        return docs.size
    }

    /** `fresh` re-run hygiene (§9.7): drop the subject's verdicts so everything re-judges. */
    fun deleteBySubject(subjectId: String): Int {
        val docs = col.whereEqualTo("subjectId", subjectId).get().await().documents
        docs.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        return docs.size
    }

    private fun Stage3EdgeVerdict.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "claimIdLow" to claimIdLow,
            "claimIdHigh" to claimIdHigh,
            "withContext" to withContext,
            "promptStamp" to promptStamp,
            "relation" to relation,
            "confidence" to confidence,
            "votes" to votes,
            "rationale" to rationale,
            "temporalNote" to temporalNote,
            "explanationRelevant" to explanationRelevant,
            "explanationHash" to explanationHash,
            "floored" to floored,
            "tie" to tie,
            "judgeModel" to judgeModel,
            "judgedAt" to judgedAt.toTimestamp(),
            "overridden" to overridden,
        )

    private fun DocumentSnapshot.toVerdictRow(): Stage3EdgeVerdict =
        Stage3EdgeVerdict(
            id = id,
            subjectId = getString("subjectId") ?: "",
            claimIdLow = getString("claimIdLow") ?: "",
            claimIdHigh = getString("claimIdHigh") ?: "",
            withContext = getBoolean("withContext") ?: false,
            promptStamp = getString("promptStamp") ?: "",
            relation = getString("relation") ?: JudgeRelation.NEUTRAL.name,
            confidence = getDouble("confidence") ?: 0.0,
            votes = voteMap("votes"),
            rationale = getString("rationale"),
            temporalNote = getString("temporalNote"),
            explanationRelevant = getBoolean("explanationRelevant"),
            explanationHash = getString("explanationHash"),
            floored = getBoolean("floored") ?: false,
            tie = getBoolean("tie") ?: false,
            judgeModel = getString("judgeModel") ?: "",
            judgedAt = instant("judgedAt"),
            overridden = getBoolean("overridden") ?: false,
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.voteMap(field: String): Map<String, Long> {
        val raw = get(field) as? Map<String, Any?> ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toLong() } }.toMap()
    }

    companion object {
        const val COLLECTION = "stage3_edges"
        /** Firestore write-batch hard limit. */
        private const val BATCH_LIMIT = 500
    }
}

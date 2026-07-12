package ai.vishwakarma.labelling.persistence

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * One fact as the §11.11 v2 publish freezes it (doc id = factId, deterministic `fact:<lowest member
 * claimId>`). The DERIVED interval ships beside the STATED member dates it was computed from
 * ([statedDates]) — the contract's stated-vs-derived rule for numbers; the full legend is
 * [PublishContract.FIELD_PROVENANCE]. Stage 4 reads this copy, never Neo4j.
 */
data class SubjectFactRecord(
    val factId: String,
    val subjectId: String,
    /** The run whose publish froze this doc — current iff it matches `subject_scores`. */
    val scoreRunId: String,
    val publishedAt: Instant?,
    val publishContractVersion: Int = PublishContract.VERSION,
    /** STATED — the exemplar member claim's verbatim text. */
    val label: String,
    val exemplarClaimId: String? = null,
    /** STATE / EVENT / TIMELESS. */
    val factKind: String? = null,
    val slot: String? = null,
    /** DERIVED min/max over [statedDates] (partial ISO `yyyy[-MM[-dd]]`). */
    val validFrom: String? = null,
    val validTo: String? = null,
    val datePrecision: String? = null,
    /** The STATED dates behind the derived interval, one per dated member claim. */
    val statedDates: List<StatedDate> = emptyList(),
    val anchored: Boolean = false,
    val belief: Double? = null,
    val beliefBare: Double? = null,
    val memberClaimIds: List<String> = emptyList(),
    /** SUCCEEDS predecessors (facts this one comes after in its slot lane). */
    val timelinePrev: List<TimelineLink> = emptyList(),
    /** SUCCEEDS successors (facts that come after this one). */
    val timelineNext: List<TimelineLink> = emptyList(),
    /** Full evidence-edge detail — deliberately here, not on claim docs (doc-size guard). */
    val edges: List<PublishedFactEdge> = emptyList(),
)

/** One member claim's STATED date (`yyyy[-MM[-dd]]`). */
data class StatedDate(val claimId: String, val date: String)

/** One SUCCEEDS chain link as published (all DERIVED; gapDays only at DAY precision). */
data class TimelineLink(val factId: String, val slot: String? = null, val gapDays: Long? = null)

/** One CORROBORATES/CONTRADICTS edge as published — the "why" behind the numbers. */
data class PublishedFactEdge(
    val relation: String,
    val otherFactId: String,
    /** STATED — the other fact's exemplar claim text. */
    val otherLabel: String,
    val otherExemplarClaimId: String? = null,
    val confidence: Double? = null,
    /** Ensemble vote split, JSON as stored on the edge. */
    val votes: String? = null,
    val rationale: String? = null,
    /** Contributing claim pairs, `a↔b`. */
    val contributingPairs: List<String> = emptyList(),
    val temporalNote: String? = null,
    val temporalOverlap: Boolean? = null,
    val explained: Boolean = false,
    val ctxRelation: String? = null,
    val ctxConfidence: Double? = null,
    /** HUMAN once a queue action moved it; PROPOSED is the machine default. */
    val reviewStatus: String? = null,
    val viaEntities: List<String> = emptyList(),
)

@Repository
class SubjectFactRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun findBySubject(subjectId: String): List<SubjectFactRecord> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toRecord() }
            .sortedBy { it.factId }

    /**
     * The publish tick's replace: set every fresh doc first, THEN delete the subject's docs whose
     * id is not in the fresh set. Write-then-delete means a crashed tick can only leave stale
     * extras (filterable by `scoreRunId`), never a hole; the resumed tick re-writes identical
     * values and finishes the sweep — idempotent like the claim write-back.
     */
    fun replaceForSubject(subjectId: String, records: List<SubjectFactRecord>) {
        records.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.set(col.document(it.factId), it.toMap()) }
            batch.commit().await()
        }
        val fresh = records.map { it.factId }.toSet()
        val stale =
            col.whereEqualTo("subjectId", subjectId).get().await().documents.filter {
                it.id !in fresh
            }
        stale.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private fun SubjectFactRecord.toMap(): Map<String, Any?> =
        mapOf(
            "factId" to factId,
            "subjectId" to subjectId,
            "scoreRunId" to scoreRunId,
            "publishedAt" to publishedAt.toTimestamp(),
            "publishContractVersion" to publishContractVersion,
            "label" to label,
            "exemplarClaimId" to exemplarClaimId,
            "factKind" to factKind,
            "slot" to slot,
            "validFrom" to validFrom,
            "validTo" to validTo,
            "datePrecision" to datePrecision,
            "statedDates" to statedDates.map { mapOf("claimId" to it.claimId, "date" to it.date) },
            "anchored" to anchored,
            "belief" to belief,
            "beliefBare" to beliefBare,
            "memberClaimIds" to memberClaimIds,
            "timelinePrev" to timelinePrev.map { it.toMap() },
            "timelineNext" to timelineNext.map { it.toMap() },
            "edges" to edges.map { it.toMap() },
        )

    private fun TimelineLink.toMap(): Map<String, Any?> =
        mapOf("factId" to factId, "slot" to slot, "gapDays" to gapDays)

    private fun PublishedFactEdge.toMap(): Map<String, Any?> =
        mapOf(
            "relation" to relation,
            "otherFactId" to otherFactId,
            "otherLabel" to otherLabel,
            "otherExemplarClaimId" to otherExemplarClaimId,
            "confidence" to confidence,
            "votes" to votes,
            "rationale" to rationale,
            "contributingPairs" to contributingPairs,
            "temporalNote" to temporalNote,
            "temporalOverlap" to temporalOverlap,
            "explained" to explained,
            "ctxRelation" to ctxRelation,
            "ctxConfidence" to ctxConfidence,
            "reviewStatus" to reviewStatus,
            "viaEntities" to viaEntities,
        )

    private fun DocumentSnapshot.toRecord(): SubjectFactRecord =
        SubjectFactRecord(
            factId = getString("factId") ?: id,
            subjectId = getString("subjectId") ?: "",
            scoreRunId = getString("scoreRunId") ?: "",
            publishedAt = instant("publishedAt"),
            publishContractVersion = (get("publishContractVersion") as? Number)?.toInt() ?: 1,
            label = getString("label") ?: "",
            exemplarClaimId = getString("exemplarClaimId"),
            factKind = getString("factKind"),
            slot = getString("slot"),
            validFrom = getString("validFrom"),
            validTo = getString("validTo"),
            datePrecision = getString("datePrecision"),
            statedDates =
                maps("statedDates").mapNotNull { m ->
                    val claimId = m["claimId"] as? String ?: return@mapNotNull null
                    val date = m["date"] as? String ?: return@mapNotNull null
                    StatedDate(claimId, date)
                },
            anchored = getBoolean("anchored") ?: false,
            belief = getDouble("belief"),
            beliefBare = getDouble("beliefBare"),
            memberClaimIds = strings("memberClaimIds"),
            timelinePrev = maps("timelinePrev").mapNotNull { it.toLink() },
            timelineNext = maps("timelineNext").mapNotNull { it.toLink() },
            edges = maps("edges").mapNotNull { it.toEdge() },
        )

    private fun Map<String, Any?>.toLink(): TimelineLink? =
        (this["factId"] as? String)?.let { factId ->
            TimelineLink(
                factId = factId,
                slot = this["slot"] as? String,
                gapDays = (this["gapDays"] as? Number)?.toLong(),
            )
        }

    private fun Map<String, Any?>.toEdge(): PublishedFactEdge? {
        val relation = this["relation"] as? String ?: return null
        val otherFactId = this["otherFactId"] as? String ?: return null
        return PublishedFactEdge(
            relation = relation,
            otherFactId = otherFactId,
            otherLabel = this["otherLabel"] as? String ?: "",
            otherExemplarClaimId = this["otherExemplarClaimId"] as? String,
            confidence = (this["confidence"] as? Number)?.toDouble(),
            votes = this["votes"] as? String,
            rationale = this["rationale"] as? String,
            contributingPairs = stringList(this["contributingPairs"]),
            temporalNote = this["temporalNote"] as? String,
            temporalOverlap = this["temporalOverlap"] as? Boolean,
            explained = this["explained"] as? Boolean ?: false,
            ctxRelation = this["ctxRelation"] as? String,
            ctxConfidence = (this["ctxConfidence"] as? Number)?.toDouble(),
            reviewStatus = this["reviewStatus"] as? String,
            viaEntities = stringList(this["viaEntities"]),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.maps(field: String): List<Map<String, Any?>> =
        (get(field) as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }

    private fun DocumentSnapshot.strings(field: String): List<String> = stringList(get(field))

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>).orEmpty().mapNotNull { it as? String }

    companion object {
        const val COLLECTION = "subject_facts"
        /** Firestore write-batch hard limit. */
        private const val BATCH_LIMIT = 500
    }
}

package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.domain.PublishedAttestor
import ai.vishwakarma.labelling.domain.PublishedEntityMention
import ai.vishwakarma.labelling.domain.PublishedFactStamp
import ai.vishwakarma.labelling.persistence.PublishedFactEdge
import ai.vishwakarma.labelling.persistence.StatedDate
import ai.vishwakarma.labelling.persistence.SubjectFactRecord
import ai.vishwakarma.labelling.persistence.TimelineLink
import java.time.Instant

/**
 * The contract-v2 blocks a single claim's ledger update carries (§11.11): everything here is a pure
 * projection of one [ScoredClaimView] row plus its fact-group size.
 */
data class ClaimContractBlock(
    val factStamp: PublishedFactStamp,
    val entityMentions: List<PublishedEntityMention>,
    val edgeCounts: Map<String, Int>,
    val attestor: PublishedAttestor?,
)

/**
 * Pure mapping from the §21 A.3 score readback (+ timeline) to the v2 publish artifacts —
 * [SubjectScorer]-style: no I/O, deterministic given the rows, unit-testable without the service.
 * Field-by-field STATED/DERIVED/HUMAN classification:
 * [ai.vishwakarma.labelling.persistence.PublishContract.FIELD_PROVENANCE].
 */
object PublishProjection {

    /** Per-claim contract blocks, keyed by claimId. */
    fun claimBlocks(rows: List<ScoredClaimView>): Map<String, ClaimContractBlock> {
        val groupSizes = rows.groupingBy { it.factId }.eachCount()
        return rows.associate { row ->
            row.claimId to
                ClaimContractBlock(
                    factStamp = row.toFactStamp(groupSizes.getValue(row.factId)),
                    entityMentions = row.toEntityMentions(),
                    edgeCounts = row.toEdgeCounts(),
                    attestor = row.toAttestor(),
                )
        }
    }

    /** One [SubjectFactRecord] per fact group, edges deduped, SUCCEEDS links attached. */
    fun factRecords(
        subjectId: String,
        rows: List<ScoredClaimView>,
        timeline: TimelineView,
        scoreRunId: String,
        publishedAt: Instant,
    ): List<SubjectFactRecord> {
        val nextBySource = timeline.succeeds.groupBy { it.fromFactId }
        val prevByTarget = timeline.succeeds.groupBy { it.toFactId }
        return rows
            .groupBy { it.factId }
            .map { (factId, members) ->
                val head = members.first()
                SubjectFactRecord(
                    factId = factId,
                    subjectId = subjectId,
                    scoreRunId = scoreRunId,
                    publishedAt = publishedAt,
                    label = head.factLabel,
                    exemplarClaimId = head.factExemplarClaimId,
                    factKind = head.factKind,
                    slot = head.slot,
                    validFrom = head.validFrom,
                    validTo = head.validTo,
                    datePrecision = head.datePrecision,
                    statedDates =
                        members
                            .mapNotNull { m -> m.claimedDate?.let { StatedDate(m.claimId, it) } }
                            .sortedWith(compareBy({ it.date }, { it.claimId })),
                    anchored = head.anchored,
                    belief = head.belief,
                    beliefBare = head.beliefBare,
                    memberClaimIds = members.map { it.claimId }.sorted(),
                    timelinePrev = prevByTarget[factId].toLinks { it.fromFactId },
                    timelineNext = nextBySource[factId].toLinks { it.toFactId },
                    edges = head.toPublishedEdges(),
                )
            }
            .sortedBy { it.factId }
    }

    // ---- per-row projections ---------------------------------------------------------------

    private fun ScoredClaimView.toFactStamp(memberCount: Int): PublishedFactStamp =
        PublishedFactStamp(
            factId = factId,
            label = factLabel,
            exemplarClaimId = factExemplarClaimId,
            kind = factKind,
            slot = slot,
            validFrom = validFrom,
            validTo = validTo,
            datePrecision = datePrecision,
            anchored = anchored,
            belief = belief,
            beliefBare = beliefBare,
            memberCount = memberCount,
        )

    private fun ScoredClaimView.toEntityMentions(): List<PublishedEntityMention> =
        entities
            .mapNotNull { m ->
                (m["name"] as? String)?.let { name ->
                    PublishedEntityMention(
                        surface = m["surface"] as? String,
                        canonicalName = name,
                        entityType = m["type"] as? String,
                        provisional = m["provisional"] as? Boolean ?: false,
                    )
                }
            }
            .sortedWith(compareBy({ it.canonicalName }, { it.surface ?: "" }))

    private fun ScoredClaimView.toEdgeCounts(): Map<String, Int> {
        val contradicts = edges.filter { it["relation"] == "CONTRADICTS" }
        return mapOf(
            "corroborates" to edges.count { it["relation"] == "CORROBORATES" },
            "contradicts" to contradicts.size,
            "contradictsExplained" to contradicts.count { it["explained"] == true },
            "contradictsConfirmed" to contradicts.count { it["reviewStatus"] == "CONFIRMED" },
        )
    }

    private fun ScoredClaimView.toAttestor(): PublishedAttestor? =
        if (attestorKey == null && attestorName == null) null
        else PublishedAttestor(attestorKey, attestorName, attestorKind, attestorTrust)

    /**
     * The fact's evidence edges off its first member row (the Cypher comprehension repeats the same
     * list on every member), deduped defensively by (relation, other end, confidence).
     */
    private fun ScoredClaimView.toPublishedEdges(): List<PublishedFactEdge> =
        edges
            .mapNotNull { e ->
                val relation = e["relation"] as? String ?: return@mapNotNull null
                val otherFactId = e["otherFactId"] as? String ?: return@mapNotNull null
                PublishedFactEdge(
                    relation = relation,
                    otherFactId = otherFactId,
                    otherLabel = e["otherLabel"] as? String ?: "",
                    otherExemplarClaimId = e["otherExemplar"] as? String,
                    confidence = (e["confidence"] as? Number)?.toDouble(),
                    votes = e["votes"] as? String,
                    rationale = e["rationale"] as? String,
                    contributingPairs = e["contributingPairs"].asStrings(),
                    temporalNote = e["temporalNote"] as? String,
                    temporalOverlap = e["temporalOverlap"] as? Boolean,
                    explained = e["explained"] as? Boolean ?: false,
                    ctxRelation = e["ctxRelation"] as? String,
                    ctxConfidence = (e["ctxConfidence"] as? Number)?.toDouble(),
                    reviewStatus = e["reviewStatus"] as? String,
                    viaEntities = e["viaEntities"].asStrings(),
                )
            }
            .distinctBy { Triple(it.relation, it.otherFactId, it.confidence) }
            .sortedWith(compareBy({ it.relation }, { it.otherFactId }))

    private fun List<TimelineSucceeds>?.toLinks(end: (TimelineSucceeds) -> String) =
        orEmpty().map { TimelineLink(end(it), it.slot, it.gapDays) }.sortedBy { it.factId }

    private fun Any?.asStrings(): List<String> =
        (this as? List<*>).orEmpty().mapNotNull { it as? String }
}

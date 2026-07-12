package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.persistence.PublishContract
import ai.vishwakarma.labelling.persistence.PublishedFactEdge
import ai.vishwakarma.labelling.persistence.StatedDate
import ai.vishwakarma.labelling.persistence.SubjectFactRecord
import ai.vishwakarma.labelling.persistence.TimelineLink
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure pins on the contract-v2 projection (§11.11): grouping, dedupe, direction, legend. */
class PublishProjectionTest {

    private val at = Instant.parse("2026-07-12T00:00:00Z")

    private fun row(
        claimId: String,
        factId: String,
        claimedDate: String? = null,
        entities: List<Map<String, Any?>> = emptyList(),
        edges: List<Map<String, Any?>> = emptyList(),
        attestorKey: String? = "subject:s1",
    ) =
        ScoredClaimView(
            claimId = claimId,
            text = "text of $claimId",
            type = "EPISODE",
            tierSeed = "LOW",
            prior = 0.35,
            score = 0.6,
            scoreBare = 0.55,
            signalsJson = null,
            claimedDate = claimedDate,
            attestorKey = attestorKey,
            attestorName = attestorKey?.let { "Asha" },
            attestorKind = attestorKey?.let { "SUBJECT" },
            attestorTrust = 0.5,
            factId = factId,
            factLabel = "label of $factId",
            factExemplarClaimId = claimId,
            factKind = "STATE",
            slot = "EMPLOYER",
            validFrom = "2019",
            validTo = "2020",
            datePrecision = "YEAR",
            anchored = true,
            belief = 0.7,
            beliefBare = 0.65,
            entities = entities,
            edges = edges,
            explanation = null,
        )

    private fun edgeMap(relation: String, other: String, reviewStatus: String? = null) =
        mapOf<String, Any?>(
            "relation" to relation,
            "otherFactId" to other,
            "otherLabel" to "label of $other",
            "otherExemplar" to "x1",
            "confidence" to 0.8,
            "votes" to """{"CONTRADICTS":4}""",
            "rationale" to "why",
            "temporalNote" to null,
            "ctxRelation" to null,
            "ctxConfidence" to null,
            "explained" to false,
            "temporalOverlap" to true,
            "reviewStatus" to reviewStatus,
            "viaEntities" to listOf("Acme"),
            "contributingPairs" to listOf("a↔b"),
        )

    @Test
    fun `fact records group members, collect stated dates and dedupe repeated edges`() {
        val edge = edgeMap("CONTRADICTS", "fact:z", reviewStatus = "PROPOSED")
        val rows =
            listOf(
                // The duplicated map mimics the comprehension repeating per member row.
                row("c2", "fact:c1", claimedDate = "2019-08", edges = listOf(edge, edge)),
                row("c1", "fact:c1", claimedDate = "2019-06-01", edges = listOf(edge)),
            )
        val records =
            PublishProjection.factRecords(
                "s1",
                rows,
                TimelineView(emptyList(), emptyList(), emptyList(), emptyList()),
                "run-1",
                at,
            )

        val record = records.single()
        assertEquals("fact:c1", record.factId)
        assertEquals("s1", record.subjectId)
        assertEquals("run-1", record.scoreRunId)
        assertEquals(listOf("c1", "c2"), record.memberClaimIds)
        // Stated dates sorted, derived interval untouched beside them.
        assertEquals(
            listOf(StatedDate("c1", "2019-06-01"), StatedDate("c2", "2019-08")),
            record.statedDates,
        )
        assertEquals("2019", record.validFrom)
        // The Cypher comprehension repeats the same edge on every member row → one published edge.
        val published = record.edges.single()
        assertEquals("CONTRADICTS", published.relation)
        assertEquals("fact:z", published.otherFactId)
        assertEquals("PROPOSED", published.reviewStatus)
        assertEquals(listOf("a↔b"), published.contributingPairs)
    }

    @Test
    fun `succeeds edges map earlier to timelineNext and later to timelinePrev`() {
        val rows = listOf(row("c1", "fact:c1"), row("c2", "fact:c2"))
        val timeline =
            TimelineView(
                state = emptyList(),
                events = emptyList(),
                succeeds = listOf(TimelineSucceeds("fact:c1", "fact:c2", "EMPLOYER", 90L)),
                undated = emptyList(),
            )
        val byId =
            PublishProjection.factRecords("s1", rows, timeline, "run-1", at).associateBy {
                it.factId
            }

        // The graph edge runs earlier→later: c1's successor is c2, c2's predecessor is c1.
        assertEquals(
            listOf(TimelineLink("fact:c2", "EMPLOYER", 90L)),
            byId.getValue("fact:c1").timelineNext,
        )
        assertTrue(byId.getValue("fact:c1").timelinePrev.isEmpty())
        assertEquals(
            listOf(TimelineLink("fact:c1", "EMPLOYER", 90L)),
            byId.getValue("fact:c2").timelinePrev,
        )
        assertTrue(byId.getValue("fact:c2").timelineNext.isEmpty())
    }

    @Test
    fun `claim blocks carry the stamp with group size, sorted mentions and edge counts`() {
        val entities =
            listOf(
                mapOf<String, Any?>(
                    "name" to "Neo4j",
                    "type" to "SKILL",
                    "provisional" to false,
                    "surface" to "neo4j",
                ),
                mapOf<String, Any?>(
                    "name" to "Acme",
                    "type" to "ORG",
                    "provisional" to true,
                    "surface" to "ACME Corp",
                ),
            )
        val edges =
            listOf(
                edgeMap("CORROBORATES", "fact:a"),
                edgeMap("CONTRADICTS", "fact:b", reviewStatus = "CONFIRMED"),
                edgeMap("CONTRADICTS", "fact:c", reviewStatus = "PROPOSED")
                    .plus("explained" to true),
            )
        val rows =
            listOf(
                row("c1", "fact:c1", entities = entities, edges = edges),
                row("c2", "fact:c1"),
            )
        val blocks = PublishProjection.claimBlocks(rows)

        val block = blocks.getValue("c1")
        assertEquals(2, block.factStamp.memberCount)
        assertEquals("fact:c1", block.factStamp.factId)
        assertEquals("label of fact:c1", block.factStamp.label)
        // Mentions sorted by canonical name; the stated surface rides beside the derived canon.
        assertEquals(listOf("Acme", "Neo4j"), block.entityMentions.map { it.canonicalName })
        assertEquals("ACME Corp", block.entityMentions.first().surface)
        assertEquals(
            mapOf(
                "corroborates" to 1,
                "contradicts" to 2,
                "contradictsExplained" to 1,
                "contradictsConfirmed" to 1,
            ),
            block.edgeCounts,
        )
        assertEquals("Asha", block.attestor?.name)
        // No attestor on the row → no block attestor (never a hollow object).
        assertNull(
            PublishProjection.claimBlocks(listOf(row("c9", "fact:c9", attestorKey = null)))
                .getValue("c9")
                .attestor,
        )
    }

    @Test
    fun `every field the projection emits has a provenance legend entry`() {
        // Scalar fields only: containers (lists/maps) are classified per element in the legend.
        fun fieldsOf(clazz: Class<*>): List<String> =
            clazz.declaredFields
                .filterNot {
                    it.type == java.util.List::class.java || it.type == java.util.Map::class.java
                }
                .map { it.name }
                .filterNot { it.contains("$") || it == "Companion" }

        val paths =
            fieldsOf(ai.vishwakarma.labelling.domain.PublishedFactStamp::class.java).map {
                "claims.factStamp.$it"
            } +
                fieldsOf(ai.vishwakarma.labelling.domain.PublishedEntityMention::class.java).map {
                    "claims.entityMentions[].$it"
                } +
                fieldsOf(ai.vishwakarma.labelling.domain.PublishedAttestor::class.java).map {
                    "claims.attestor.$it"
                } +
                listOf(
                    "claims.publishContractVersion",
                    "claims.authenticityScoreBare",
                    "claims.edgeCounts.corroborates",
                    "claims.authenticitySignals.evidenceMass",
                ) +
                fieldsOf(SubjectFactRecord::class.java).map { "subject_facts.$it" } +
                fieldsOf(StatedDate::class.java).map { "subject_facts.statedDates[].$it" } +
                fieldsOf(TimelineLink::class.java).flatMap {
                    listOf("subject_facts.timelinePrev[].$it", "subject_facts.timelineNext[].$it")
                } +
                fieldsOf(PublishedFactEdge::class.java).map { "subject_facts.edges[].$it" } +
                listOf("subject_scores.score", "claim_reviews.justification")

        val missing = paths.filter { PublishContract.provenanceOf(it) == null }
        assertTrue(missing.isEmpty(), "legend is missing entries for: $missing")
        // Spot-check the values a Stage 4 consumer leans on hardest.
        assertEquals("STATED", PublishContract.provenanceOf("subject_facts.statedDates[].date"))
        assertEquals("DERIVED", PublishContract.provenanceOf("subject_facts.validFrom"))
        assertEquals("STATED", PublishContract.provenanceOf("claims.entityMentions[].surface"))
        assertEquals(
            "DERIVED",
            PublishContract.provenanceOf("claims.entityMentions[].canonicalName"),
        )
        assertEquals("HUMAN", PublishContract.provenanceOf("subject_facts.edges[].reviewStatus"))
        assertNotNull(PublishContract.provenanceOf("claims.authenticitySignals.prior"))
        assertNull(PublishContract.provenanceOf("claims.notAContractField"))
    }
}

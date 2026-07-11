package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRecord
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.stage3.ScoredClaimView
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import ai.vishwakarma.labelling.stage3.TimelineView
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.neo4j.driver.Driver

private class DashSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]
}

private class DashRunRepo : Stage3RunRepository(mock(Firestore::class.java)) {
    var latest: Stage3Run? = null

    override fun findBySubject(subjectId: String): List<Stage3Run> = listOfNotNull(latest)
}

private class DashScoreRepo : SubjectScoreRepository(mock(Firestore::class.java)) {
    var record: SubjectScoreRecord? = null

    override fun find(subjectId: String): SubjectScoreRecord? = record

    override fun save(record: SubjectScoreRecord) {
        this.record = record
    }
}

private class DashGraphRepo(props: AppProperties) :
    Stage3GraphRepository(mock(Driver::class.java), props) {
    var rows: List<ScoredClaimView> = emptyList()
    var timelineView = TimelineView(emptyList(), emptyList(), emptyList(), emptyList())

    override fun scoresReadback(subjectId: String): List<ScoredClaimView> = rows

    override fun timeline(subjectId: String): TimelineView = timelineView
}

private fun row(
    claimId: String,
    factId: String,
    belief: Double,
    sourceClass: String = "SELF",
    attestorKey: String? = "subject:s1",
    attestorKind: String? = "SUBJECT",
    edges: List<Map<String, Any?>> = emptyList(),
    entities: List<Map<String, Any?>> = emptyList(),
) =
    ScoredClaimView(
        claimId = claimId,
        text = "claim $claimId",
        type = "EPISODE",
        tierSeed = "MEDIUM",
        prior = null,
        score = belief,
        scoreBare = belief,
        signalsJson = """{"independence":1.0,"evidenceMass":2.0}""",
        basis = "STATED",
        sourceClass = sourceClass,
        sensitive = false,
        claimedDate = null,
        attestorKey = attestorKey,
        attestorName = null,
        attestorKind = attestorKind,
        attestorTrust = null,
        factId = factId,
        factLabel = "fact $factId",
        factKind = "EVENT",
        slot = null,
        validFrom = null,
        validTo = null,
        datePrecision = null,
        anchored = false,
        belief = belief,
        beliefBare = belief,
        entities = entities,
        edges = edges,
        explanation = null,
    )

private fun edge(
    relation: String,
    otherFactId: String,
    confidence: Double,
    explained: Boolean = false,
    reviewStatus: String? = "PROPOSED",
): Map<String, Any?> =
    mapOf(
        "relation" to relation,
        "otherFactId" to otherFactId,
        "confidence" to confidence,
        "explained" to explained,
        "reviewStatus" to reviewStatus,
        "rationale" to "the dates cannot both hold",
    )

class Stage3DashboardServiceTest {

    private val props = AppProperties()
    private val subjects = DashSubjectRepo()
    private val runs = DashRunRepo()
    private val graph = DashGraphRepo(props)
    private val scores = DashScoreRepo()
    private val service = Stage3DashboardService(subjects, runs, graph, scores, props)

    private fun seed() {
        subjects.store["s1"] = Subject(id = "s1", displayName = "Asha")
        graph.rows =
            listOf(
                row(
                    "c1",
                    "f1",
                    belief = 0.90,
                    sourceClass = "DOCUMENTARY",
                    attestorKey = "issuer:a",
                    attestorKind = "ISSUER",
                    edges =
                        listOf(
                            edge("CORROBORATES", "f2", 0.8),
                            edge("CONTRADICTS", "f3", 0.7, explained = true),
                        ),
                    entities = listOf(mapOf("name" to "Acme", "type" to "ORG")),
                ),
                row(
                    "c2",
                    "f2",
                    belief = 0.60,
                    sourceClass = "ENDORSEMENT",
                    attestorKey = "endorser:a",
                    attestorKind = "ENDORSER",
                    edges = listOf(edge("CORROBORATES", "f1", 0.8)),
                    entities = listOf(mapOf("name" to "Acme", "type" to "ORG")),
                ),
                row(
                    "c3",
                    "f3",
                    belief = 0.30,
                    edges = listOf(edge("CONTRADICTS", "f1", 0.7, explained = true)),
                ),
            )
        runs.latest =
            Stage3Run(id = "run-1", subjectId = "s1", status = Stage3RunStatus.AWAITING_REVIEW)
    }

    @Test
    fun `unknown subject is a NotFound`() {
        val err = service.dashboard("nope").fold({ it }, { null })
        assertTrue(err is DomainError.NotFound)
    }

    @Test
    fun `assembles distributions with subject-wide edge dedupe`() {
        seed()
        val data = service.dashboard("s1").fold({ throw AssertionError(it.message) }, { it })
        assertEquals("Asha", data.subjectName)
        assertTrue(data.provisional)
        assertEquals("run-1", data.runId)
        // One fact per tier band at the defaults (0.75 / 0.45).
        assertEquals(mapOf("HIGH" to 1, "MEDIUM" to 1, "LOW" to 1), data.factTierCounts)
        assertEquals(3, data.claimScoreHistogram.sum())
        assertEquals(
            mapOf("DOCUMENTARY" to 1, "ENDORSEMENT" to 1, "SELF" to 1),
            data.sourceClassCounts
        )
        // The corroboration appears on both facts' rows; the contradiction on both sides too —
        // each counts once.
        assertEquals(1, data.edgeSummary.corroborations)
        assertEquals(1, data.aggregate.inputs.contradictionCount)
        assertEquals(1, data.edgeSummary.contradictionsExplained)
        assertEquals(0, data.edgeSummary.contradictionsProposed) // explained → out of the queue
        assertEquals(1, data.topContradictions.size)
        val contra = data.topContradictions.first()
        assertEquals(setOf("fact f1", "fact f3"), setOf(contra.fromLabel, contra.toLabel))
        assertNotNull(contra.rationale)
        assertEquals(1, data.entityCount)
        assertEquals(3, data.factPoints.size)
        // sat(2.0) with m0 = 2.0 is exactly 0.5.
        assertEquals(0.5, data.factPoints.first().saturation, 1e-9)
    }

    @Test
    fun `published record rides along and drift is flagged`() {
        seed()
        runs.latest = Stage3Run(id = "run-1", subjectId = "s1", status = Stage3RunStatus.PUBLISHED)
        scores.record =
            SubjectScoreRecord(
                subjectId = "s1",
                score = 0.99, // deliberately far from the live recompute
                display = 99,
                band = "STRONG",
                components = emptyMap(),
                inputs = emptyMap(),
                factCount = 3,
                claimCount = 3,
                scoreRunId = "run-1",
                publishedAt = null,
                publishedBy = "op",
            )
        val data = service.dashboard("s1").fold({ throw AssertionError(it.message) }, { it })
        assertTrue(!data.provisional)
        assertNotNull(data.published)
        assertTrue(data.divergesFromPublished)
    }

    @Test
    fun `empty graph yields the UNSUPPORTED empty state`() {
        subjects.store["s1"] = Subject(id = "s1", displayName = "Asha")
        val data = service.dashboard("s1").fold({ throw AssertionError(it.message) }, { it })
        assertEquals(0, data.aggregate.inputs.factCount)
        assertEquals("UNSUPPORTED", data.aggregate.band)
        assertTrue(data.factPoints.isEmpty())
        assertTrue(data.topContradictions.isEmpty())
    }
}

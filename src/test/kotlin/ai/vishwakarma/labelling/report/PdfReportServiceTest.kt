package ai.vishwakarma.labelling.report

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.gcs.IntakeStorage
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectReport
import ai.vishwakarma.labelling.persistence.SubjectReportRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.service.DashboardContradiction
import ai.vishwakarma.labelling.service.DashboardData
import ai.vishwakarma.labelling.service.DashboardEdgeSummary
import ai.vishwakarma.labelling.service.DashboardFactPoint
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.Stage3DashboardService
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import ai.vishwakarma.labelling.stage3.SubjectScore
import ai.vishwakarma.labelling.stage3.SubjectScoreComponents
import ai.vishwakarma.labelling.stage3.SubjectScoreInputs
import ai.vishwakarma.labelling.stage3.TimelineView
import arrow.core.Either
import arrow.core.right
import com.google.cloud.firestore.Firestore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.neo4j.driver.Driver
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

private class CannedDashboards(var result: Either<DomainError, DashboardData>) :
    Stage3DashboardService(
        SubjectRepository(mock(Firestore::class.java)),
        Stage3RunRepository(mock(Firestore::class.java)),
        Stage3GraphRepository(mock(Driver::class.java), AppProperties()),
        SubjectScoreRepository(mock(Firestore::class.java)),
        AppProperties(),
    ) {
    override fun dashboard(subjectId: String): Either<DomainError, DashboardData> = result
}

private class ReportRepo : SubjectReportRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, SubjectReport>()

    override fun save(report: SubjectReport) {
        store[report.subjectId] = report
    }

    override fun find(subjectId: String): SubjectReport? = store[subjectId]

    override fun delete(subjectId: String) {
        store.remove(subjectId)
    }
}

private class RunRepo : Stage3RunRepository(mock(Firestore::class.java)) {
    var latest: Stage3Run? = null

    override fun findBySubject(subjectId: String): List<Stage3Run> = listOfNotNull(latest)
}

private fun dashboardData(factCount: Int = 2, provisional: Boolean = true): DashboardData {
    val points =
        (1..factCount).map { i ->
            DashboardFactPoint(
                factId = "f$i",
                label = "fact $i — worked & <tested>",
                slot = if (i % 2 == 0) "EMPLOYER" else null,
                belief = 0.9 - i * 0.1,
                saturation = 0.5,
                evidenceMass = 2.0,
                tier = if (i == 1) "HIGH" else "MEDIUM",
                selfOnly = i == factCount,
                claimCount = i,
            )
        }
    return DashboardData(
        subjectId = "s1",
        subjectName = "Asha <QA>",
        runId = "run-1",
        runStatus = if (provisional) "AWAITING_REVIEW" else "PUBLISHED",
        provisional = provisional,
        aggregate =
            SubjectScore(
                score = 0.653,
                display = 65,
                band = "GOOD",
                components =
                    SubjectScoreComponents(
                        0.854,
                        0.583,
                        0.75,
                        1.0,
                        0.219,
                        0.875,
                        0.925,
                        1.0,
                        0.945
                    ),
                inputs =
                    SubjectScoreInputs(
                        factCount = factCount,
                        claimCount = factCount * 2,
                        selfOnlyFactCount = 1,
                        independentAttestorCount = 3,
                        attestorKindCount = 3,
                        attestorsByKind = mapOf("SUBJECT" to 1, "ISSUER" to 1, "ENDORSER" to 2),
                        documentaryFactFraction = 0.25,
                        corroborationCount = 4,
                        contradictionCount = 1,
                        explainedContradictionCount = 1,
                        confirmedContradictionCount = 0,
                        proposedContradictionCount = 0,
                        meanEvidenceMass = 2.0,
                        medianEvidenceMass = 2.0,
                        anchoredFactCount = 1,
                    ),
            ),
        published = null,
        divergesFromPublished = false,
        factTierCounts = mapOf("HIGH" to 1, "MEDIUM" to factCount - 1, "LOW" to 0),
        claimScoreHistogram = List(20) { if (it >= 17) 1 else 0 },
        sourceClassCounts = mapOf("SELF" to 2, "DOCUMENTARY" to 2),
        attestorKindCounts = mapOf("SUBJECT" to 1, "ISSUER" to 1, "ENDORSER" to 2),
        edgeSummary = DashboardEdgeSummary(4, 0, 0, 1),
        factPoints = points,
        topContradictions =
            listOf(
                DashboardContradiction(
                    fromFactId = "f1",
                    fromLabel = "fact 1",
                    toFactId = "f2",
                    toLabel = "fact 2",
                    confidence = 0.75,
                    explained = true,
                    reviewStatus = "PROPOSED",
                    rationale = "the dates & the roles cannot both hold",
                    viaEntities = emptyList(),
                )
            ),
        entityCount = 3,
        timeline = TimelineView(emptyList(), emptyList(), emptyList(), emptyList()),
    )
}

private fun emptyDashboardData(): DashboardData =
    dashboardData(factCount = 2).let {
        it.copy(
            aggregate =
                it.aggregate.copy(
                    score = 0.0,
                    display = 0,
                    band = "UNSUPPORTED",
                    inputs = it.aggregate.inputs.copy(factCount = 0, claimCount = 0),
                ),
            factPoints = emptyList(),
            topContradictions = emptyList(),
        )
    }

class PdfReportServiceTest {

    private val props = AppProperties() // blank intake bucket → local var/intake fallback
    private val storage = IntakeStorage(props)
    private val reports = ReportRepo()
    private val runs = RunRepo()
    private val dashboards = CannedDashboards(dashboardData().right())
    // The production engine (Boot's ITemplateEngine bean IS a SpringTemplateEngine): SpringEL
    // expressions, no OGNL on the classpath — a plain TemplateEngine would NoClassDefFound ognl.
    private val templates =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    templateMode = TemplateMode.HTML
                }
            )
        }
    private val service = PdfReportService(dashboards, storage, reports, runs, templates, props)

    private val localPdf: Path = Path.of("var", "intake", "reports", "s1", "profile.pdf")

    @AfterTest
    fun cleanup() {
        Files.deleteIfExists(localPdf)
    }

    @Test
    fun `generate renders a real PDF, stores it and stamps the metadata`() {
        val report =
            service.generate("s1", actor = "op").fold({ throw AssertionError(it.message) }, { it })
        assertEquals("reports/s1/profile.pdf", report.objectPath)
        assertEquals("run-1", report.scoreRunId)
        assertEquals(65, report.display)
        assertEquals("GOOD", report.band)
        assertTrue(report.provisional)
        assertEquals("op", report.generatedBy)
        assertNotNull(reports.find("s1"))
        assertTrue(Files.exists(localPdf))
        val bytes = Files.readAllBytes(localPdf)
        assertEquals(report.sizeBytes, bytes.size.toLong())
        assertTrue(bytes.size > 4 && String(bytes, 0, 5) == "%PDF-", "bytes must start with %PDF-")
    }

    @Test
    fun `regenerate replaces the single object path`() {
        val first = service.generate("s1", "op").fold({ throw AssertionError(it.message) }, { it })
        dashboards.result = dashboardData(provisional = false).right()
        val second =
            service.generate("s1", "op2").fold({ throw AssertionError(it.message) }, { it })
        assertEquals(first.objectPath, second.objectPath) // one path — replace by construction
        assertEquals("op2", reports.find("s1")!!.generatedBy)
        assertTrue(!second.provisional)
        assertTrue(Files.exists(localPdf))
    }

    @Test
    fun `no scored facts is a conflict and nothing persists`() {
        dashboards.result = emptyDashboardData().right()
        val err = service.generate("s1", "op").fold({ it }, { null })
        assertTrue(err is DomainError.Conflict)
        assertNull(reports.find("s1"))
        assertTrue(!Files.exists(localPdf))
    }

    @Test
    fun `delete removes the object and the metadata`() {
        service.generate("s1", "op")
        assertTrue(Files.exists(localPdf))
        service.delete("s1").fold({ throw AssertionError(it.message) }, { it })
        assertNull(reports.find("s1"))
        assertTrue(!Files.exists(localPdf))
        // Deleting again: metadata is gone → NotFound.
        assertTrue(service.delete("s1").fold({ it }, { null }) is DomainError.NotFound)
    }

    @Test
    fun `download url needs a report and stale detection compares run ids`() {
        assertTrue(service.downloadUrl("s1").fold({ it }, { null }) is DomainError.NotFound)
        service.generate("s1", "op")
        val url = service.downloadUrl("s1").fold({ throw AssertionError(it.message) }, { it })
        assertTrue(url.contains("reports/s1/profile.pdf"))

        // The stored report cites run-1; a newer PUBLISHED run makes it stale.
        assertTrue(!service.isStale("s1"))
        runs.latest = Stage3Run(id = "run-2", subjectId = "s1", status = Stage3RunStatus.PUBLISHED)
        assertTrue(service.isStale("s1"))
        runs.latest = Stage3Run(id = "run-1", subjectId = "s1", status = Stage3RunStatus.PUBLISHED)
        assertTrue(!service.isStale("s1"))
    }
}

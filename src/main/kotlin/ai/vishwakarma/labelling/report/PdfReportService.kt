package ai.vishwakarma.labelling.report

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.gcs.IntakeStorage
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectReport
import ai.vishwakarma.labelling.persistence.SubjectReportRepository
import ai.vishwakarma.labelling.service.DashboardData
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.Stage3DashboardService
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.openhtmltopdf.extend.FSSupplier
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.thymeleaf.ITemplateEngine
import org.thymeleaf.context.Context

/**
 * The Stage 3.5 §7 profile PDF: DashboardData → Thymeleaf (`report/profile-pdf`, strict XHTML) →
 * openhtmltopdf (pdfbox + Batik for the shared ChartSvg output, bundled DejaVu for deterministic
 * glyphs) → the intake bucket at ONE fixed object path per subject — regenerate overwrites in place
 * (D6, no stale copies by construction), a metadata doc (`subject_reports`) stamps run id, score
 * and provenance. Nothing persists unless the render succeeded.
 */
@Service
class PdfReportService(
    private val dashboards: Stage3DashboardService,
    private val storage: IntakeStorage,
    private val reports: SubjectReportRepository,
    private val runs: Stage3RunRepository,
    private val templates: ITemplateEngine,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val stamp =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun metadata(subjectId: String): SubjectReport? = reports.find(subjectId)

    /** The dashboard nag: a report citing an older run than the latest PUBLISHED one (D7). */
    fun isStale(subjectId: String): Boolean {
        val report = reports.find(subjectId) ?: return false
        val latest = runs.findBySubject(subjectId).firstOrNull() ?: return false
        return latest.status == Stage3RunStatus.PUBLISHED && report.scoreRunId != latest.id
    }

    /** Generate (or regenerate — replaces) the subject's one current profile PDF. */
    fun generate(subjectId: String, actor: String?): Either<DomainError, SubjectReport> {
        val data =
            dashboards
                .dashboard(subjectId)
                .fold(
                    {
                        return it.left()
                    },
                    { it }
                )
        if (data.aggregate.inputs.factCount == 0)
            return DomainError.Conflict(
                    "No scored facts yet — run Stage 3 to SCORING before generating a report"
                )
                .left()
        val bytes =
            runCatching { render(data) }
                .getOrElse {
                    log.warn("Profile PDF render failed for subject {}", subjectId, it)
                    return DomainError.Invalid("PDF render failed: ${it.message}").left()
                }
        val objectPath = objectPath(subjectId)
        runCatching { storage.writeBytes(objectPath, bytes, "application/pdf") }
            .getOrElse {
                return DomainError.Invalid("Report storage failed: ${it.message}").left()
            }
        val record =
            SubjectReport(
                subjectId = subjectId,
                objectPath = objectPath,
                scoreRunId = data.runId,
                score = data.aggregate.score,
                display = data.aggregate.display,
                band = data.aggregate.band,
                provisional = data.provisional,
                generatedAt = Instant.now(),
                generatedBy = actor,
                sizeBytes = bytes.size.toLong(),
            )
        reports.save(record)
        log.info(
            "Profile PDF for subject {}: {} bytes at {} (SAI {} {}{})",
            subjectId,
            bytes.size,
            objectPath,
            record.display,
            record.band,
            if (record.provisional) ", provisional" else "",
        )
        return record.right()
    }

    /** Signed GET URL (or the dev download sink) for the current report. */
    fun downloadUrl(subjectId: String): Either<DomainError, String> {
        val report =
            reports.find(subjectId)
                ?: return DomainError.NotFound(
                        "No profile PDF for subject $subjectId — generate one first"
                    )
                    .left()
        return storage.signedDownloadUrl(report.objectPath).right()
    }

    /** Remove the stored object and its metadata (idempotent on the object side). */
    fun delete(subjectId: String): Either<DomainError, Map<String, Any?>> {
        val report =
            reports.find(subjectId)
                ?: return DomainError.NotFound("No profile PDF for subject $subjectId").left()
        storage.deleteObject(report.objectPath)
        reports.delete(subjectId)
        return mapOf<String, Any?>("deleted" to true, "subjectId" to subjectId).right()
    }

    private fun render(data: DashboardData): ByteArray {
        val charts = DashboardCharts.build(data, props.stage3, VizPalette.PRINT)
        val ctx = Context(Locale.ROOT)
        ctx.setVariable("data", data)
        ctx.setVariable("charts", charts)
        ctx.setVariable("generatedAt", stamp.format(Instant.now()))
        ctx.setVariable(
            "topFacts",
            data.factPoints.sortedWith(compareByDescending { it.belief }).take(10),
        )
        ctx.setVariable(
            "flaggedFacts",
            data.factPoints
                .filter { it.belief < props.stage3.tierMedium || it.selfOnly }
                .sortedBy { it.belief }
                .take(10),
        )
        val html = templates.process("report/profile-pdf", ctx)
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .useSVGDrawer(BatikSVGDrawer())
            .useFont(classpathFont("fonts/DejaVuSans.ttf"), "DejaVu Sans", 400, NORMAL, true)
            .useFont(classpathFont("fonts/DejaVuSans-Bold.ttf"), "DejaVu Sans", 700, NORMAL, true)
            .withHtmlContent(html, null)
            .toStream(out)
            .run()
        return out.toByteArray()
    }

    private fun classpathFont(path: String): FSSupplier<InputStream> = FSSupplier {
        ClassPathResource(path).inputStream
    }

    companion object {
        private val NORMAL = BaseRendererBuilder.FontStyle.NORMAL

        /** The D6 fixed path — one report per subject, overwritten on regenerate. */
        fun objectPath(subjectId: String): String = "reports/$subjectId/profile.pdf"
    }
}

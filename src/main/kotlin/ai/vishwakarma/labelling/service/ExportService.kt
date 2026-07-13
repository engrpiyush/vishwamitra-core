package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.domain.ExportRecord
import ai.vishwakarma.labelling.domain.Stage4Run
import ai.vishwakarma.labelling.domain.ToolEncoding
import ai.vishwakarma.labelling.gcs.Exporter
import ai.vishwakarma.labelling.persistence.ExportRepository
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.serialization.ContentsPartsSerializer
import ai.vishwakarma.labelling.serialization.DatasetLineValidator
import ai.vishwakarma.labelling.serialization.DpoSerializer
import ai.vishwakarma.labelling.serialization.SftValidator
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.stereotype.Service

/**
 * Filters APPROVED examples by tags, serializes them to `contents`/`parts` (SFT) or preference
 * (DPO) JSONL, writes a timestamped snapshot to the training bucket, records an `exports` doc, and
 * stamps `exportedIn` on each included example. [exportStage4Run] is the Stage 4 variant (LLD
 * §9.4/§13, VA-58): the filter is the run's frozen stamp rather than tags, and two validator gates
 * run before anything is written.
 */
@Service
class ExportService(
    private val sft: SftService,
    private val dpo: DpoService,
    private val sftExamples: SftExampleRepository,
    private val sftSerializer: ContentsPartsSerializer,
    private val dpoSerializer: DpoSerializer,
    private val sftValidator: SftValidator,
    private val lineValidator: DatasetLineValidator,
    private val exporter: Exporter,
    private val exports: ExportRepository,
) {

    private val tsFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun history(): List<ExportRecord> = exports.findAll()

    /** Delete an export: its JSONL blob from the bucket (best-effort) and its Firestore record. */
    fun delete(id: String): Either<DomainError, Unit> {
        val record =
            exports.findById(id) ?: return DomainError.NotFound("Export $id not found").left()
        runCatching { exporter.deleteUri(record.gcsUri) }
        exports.delete(id)
        return Unit.right()
    }

    fun export(
        kind: ExportKind,
        claimType: ClaimType?,
        authenticityTier: AuthenticityTier?,
        label: String?,
        actor: String?,
        toolEncoding: ToolEncoding = ToolEncoding.DEFAULT,
    ): Either<DomainError, ExportRecord> {
        val labelFilter = label?.ifBlank { null }

        val (ids, lines) =
            when (kind) {
                ExportKind.SFT -> {
                    val items =
                        sft.list(ExampleStatus.APPROVED).filter {
                            it.tags.matches(claimType, authenticityTier, labelFilter)
                        }
                    items.map { it.id } to items.map { sftSerializer.toJsonl(it, toolEncoding) }
                }
                ExportKind.DPO -> {
                    val items =
                        dpo.list(ExampleStatus.APPROVED).filter {
                            it.tags.matches(claimType, authenticityTier, labelFilter)
                        }
                    items.map { it.id } to items.map { dpoSerializer.toJsonl(it, toolEncoding) }
                }
            }

        if (lines.isEmpty())
            return DomainError.Invalid("No approved ${kind.name} examples match the filter").left()

        val objectPath = "data/${kind.dir}/${tsFormat.format(Instant.now())}.jsonl"
        val uri = exporter.write(objectPath, lines.joinToString("\n"))

        val record =
            ExportRecord(
                id = exports.newId(),
                kind = kind,
                gcsUri = uri,
                exampleIds = ids,
                count = ids.size,
                createdBy = actor,
                createdAt = Instant.now(),
            )
        exports.save(record)

        ids.forEach { id ->
            when (kind) {
                ExportKind.SFT -> sft.markExported(id, record.id)
                ExportKind.DPO -> dpo.markExported(id, record.id)
            }
        }
        return record.right()
    }

    private fun ExampleTags.matches(
        claimType: ClaimType?,
        tier: AuthenticityTier?,
        label: String?,
    ): Boolean =
        (claimType == null || claimType == this.claimType) &&
            (tier == null || tier == authenticityTier) &&
            (label == null || labels.any { it.equals(label, ignoreCase = true) })

    /**
     * The Stage 4 export (LLD §9.4/§13, VA-58): exactly the run's APPROVED **current-stamp**
     * examples — scoreRunId and personaHash match the run's frozen pair — so DRAFT, SUBMITTED,
     * NEEDS_CHANGES, ARCHIVED and stale-stamp rows never reach the dataset. [SftValidator] gates
     * the in-memory conversations and [DatasetLineValidator] the serialized JSONL; either failing
     * aborts with the offending exampleIds attached and leaves no blob and no [ExportRecord]. Text
     * turns only (the v1 advocate has no tools — the ToolEncoding seam stays dormant).
     */
    fun exportStage4Run(run: Stage4Run, actor: String?): Either<DomainError, ExportRecord> {
        val included =
            sftExamples
                .findByStampSubject(run.subjectId)
                .filter {
                    it.status == ExampleStatus.APPROVED &&
                        it.stamp?.scoreRunId == run.scoreRunId &&
                        it.stamp?.personaHash == run.personaHash
                }
                .sortedBy { it.id }
        if (included.isEmpty())
            return DomainError.Invalid(
                    "No APPROVED current-stamp examples to export for run ${run.id} — approve " +
                        "reviewed conversations first"
                )
                .left()

        val structural =
            included.flatMap { example ->
                sftValidator.validate(example).map { "${example.id}: $it" }
            }
        if (structural.isNotEmpty())
            return DomainError.Invalid(
                    "Export blocked — fix or regenerate: ${structural.joinToString("; ")}"
                )
                .left()

        val lines = included.map { sftSerializer.toJsonl(it, ToolEncoding.DEFAULT) }
        val lineErrors =
            lineValidator.validate(lines.joinToString("\n"), ExportKind.SFT).map { err ->
                val exampleId = included.getOrNull(err.line - 1)?.id ?: "line ${err.line}"
                "$exampleId: ${err.message}"
            }
        if (lineErrors.isNotEmpty())
            return DomainError.Invalid(
                    "Export blocked — fix or regenerate: ${lineErrors.joinToString("; ")}"
                )
                .left()

        val objectPath =
            "data/${ExportKind.SFT.dir}/${tsFormat.format(Instant.now())}-stage4-${run.id}.jsonl"
        val uri = exporter.write(objectPath, lines.joinToString("\n"))
        val record =
            ExportRecord(
                id = exports.newId(),
                kind = ExportKind.SFT,
                gcsUri = uri,
                exampleIds = included.map { it.id },
                count = included.size,
                createdBy = actor,
                createdAt = Instant.now(),
            )
        exports.save(record)
        included.forEach { sft.markExported(it.id, record.id) }
        return record.right()
    }
}

package ai.vishwakarma.labelling.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.domain.ExportRecord
import ai.vishwakarma.labelling.gcs.Exporter
import ai.vishwakarma.labelling.persistence.ExportRepository
import ai.vishwakarma.labelling.serialization.ContentsPartsSerializer
import ai.vishwakarma.labelling.serialization.DpoSerializer
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Filters APPROVED examples by tags, serializes them to `contents`/`parts` (SFT) or preference (DPO)
 * JSONL, writes a timestamped snapshot to the training bucket, records an `exports` doc, and stamps
 * `exportedIn` on each included example.
 */
@Service
class ExportService(
    private val sft: SftService,
    private val dpo: DpoService,
    private val sftSerializer: ContentsPartsSerializer,
    private val dpoSerializer: DpoSerializer,
    private val exporter: Exporter,
    private val exports: ExportRepository,
) {

    private val tsFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun history(): List<ExportRecord> = exports.findAll()

    fun export(
        kind: ExportKind,
        skill: String?,
        intent: String?,
        language: String?,
        actor: String?,
    ): Either<DomainError, ExportRecord> {
        val filter = ExampleTags(skill?.ifBlank { null }, intent?.ifBlank { null }, language?.ifBlank { null })

        val (ids, lines) = when (kind) {
            ExportKind.SFT -> {
                val items = sft.list(ExampleStatus.APPROVED).filter { it.tags.matches(filter) }
                items.map { it.id } to items.map { sftSerializer.toJsonl(it) }
            }

            ExportKind.DPO -> {
                val items = dpo.list(ExampleStatus.APPROVED).filter { it.tags.matches(filter) }
                items.map { it.id } to items.map { dpoSerializer.toJsonl(it) }
            }
        }

        if (lines.isEmpty()) return DomainError.Invalid("No approved ${kind.name} examples match the filter").left()

        val objectPath = "data/${kind.dir}/${tsFormat.format(Instant.now())}.jsonl"
        val uri = exporter.write(objectPath, lines.joinToString("\n"))

        val record = ExportRecord(
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

    private fun ExampleTags.matches(filter: ExampleTags): Boolean =
        (filter.skill == null || filter.skill == skill) &&
            (filter.intent == null || filter.intent == intent) &&
            (filter.language == null || filter.language == language)
}

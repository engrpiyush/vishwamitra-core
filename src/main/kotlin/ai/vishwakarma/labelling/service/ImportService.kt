package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.domain.ImportRecord
import ai.vishwakarma.labelling.domain.ValidationStatus
import ai.vishwakarma.labelling.gcs.Exporter
import ai.vishwakarma.labelling.persistence.ImportRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * Imports an externally-produced JSONL dataset: stores it (renamed, timestamped) under a dated
 * `imports/` folder in the training bucket, records an `imports` doc, and kicks off asynchronous
 * structural validation. A VALID import is then selectable as a tuning dataset in the Training
 * form.
 */
@Service
class ImportService(
    private val imports: ImportRepository,
    private val exporter: Exporter,
    private val validationRunner: ImportValidationRunner,
) {

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private val tsFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun list(): List<ImportRecord> = imports.findAll()

    fun listValid(): List<ImportRecord> =
        imports.findAll().filter { it.status == ValidationStatus.VALID }

    fun findById(id: String): ImportRecord? = imports.findById(id)

    fun import(
        kind: ExportKind,
        file: MultipartFile,
        actor: String?,
    ): Either<DomainError, ImportRecord> {
        if (file.isEmpty) return DomainError.Invalid("No file uploaded").left()
        val original = file.originalFilename?.trim().orEmpty()
        if (!original.endsWith(".jsonl", ignoreCase = true))
            return DomainError.Invalid("File must be a .jsonl file").left()

        val now = Instant.now()
        val objectPath =
            "imports/${dateFormat.format(now)}/${tsFormat.format(now)}-${kind.name}.jsonl"
        val content = String(file.bytes, Charsets.UTF_8)
        val uri = exporter.write(objectPath, content)

        val record =
            ImportRecord(
                id = imports.newId(),
                kind = kind,
                originalFilename = original,
                storedObjectPath = objectPath,
                gcsUri = uri,
                status = ValidationStatus.PENDING,
                createdBy = actor,
                createdAt = now,
            )
        imports.save(record)
        validationRunner.run(record.id)
        return record.right()
    }

    /** Delete an import: its JSONL blob from the bucket (best-effort) and its Firestore record. */
    fun delete(id: String): Either<DomainError, Unit> {
        val record =
            imports.findById(id) ?: return DomainError.NotFound("Import $id not found").left()
        runCatching { exporter.deleteUri(record.gcsUri) }
        imports.delete(id)
        return Unit.right()
    }

    /** Re-run validation against the stored object (after a restart, or once a file is fixed). */
    fun revalidate(id: String): Either<DomainError, Unit> {
        imports.findById(id) ?: return DomainError.NotFound("Import $id not found").left()
        validationRunner.run(id)
        return Unit.right()
    }
}

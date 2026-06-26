package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ImportError
import ai.vishwakarma.labelling.domain.ValidationStatus
import ai.vishwakarma.labelling.gcs.Exporter
import ai.vishwakarma.labelling.persistence.ImportRepository
import ai.vishwakarma.labelling.serialization.DatasetLineValidator
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Runs structural validation of an imported dataset off the request thread. Kept in its own bean so
 * the Spring `@Async` proxy applies (self-invocation from [ImportService] would bypass it). Reads
 * the stored object back from the bucket so the validated bytes are exactly what tuning would use,
 * and so a record can be re-validated after a restart without re-uploading.
 */
@Component
class ImportValidationRunner(
    private val imports: ImportRepository,
    private val exporter: Exporter,
    private val validator: DatasetLineValidator,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    fun run(recordId: String) {
        val record = imports.findById(recordId) ?: return
        imports.save(record.copy(status = ValidationStatus.VALIDATING))
        try {
            val content = exporter.read(record.storedObjectPath)
            val lineCount = content.split("\n").count { it.isNotBlank() }
            val errors = validator.validate(content, record.kind)
            imports.save(
                record.copy(
                    status =
                        if (errors.isEmpty()) ValidationStatus.VALID else ValidationStatus.INVALID,
                    errors = errors,
                    lineCount = lineCount,
                    validatedAt = Instant.now(),
                )
            )
            log.info("Validated import {} ({} lines, {} errors)", recordId, lineCount, errors.size)
        } catch (e: Exception) {
            log.warn("Import validation failed for {}: {}", recordId, e.message)
            imports.save(
                record.copy(
                    status = ValidationStatus.INVALID,
                    errors = listOf(ImportError(0, "Could not read/validate file: ${e.message}")),
                    validatedAt = Instant.now(),
                )
            )
        }
    }
}

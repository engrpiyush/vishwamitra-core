package ai.vishwakarma.labelling.domain

import java.time.Instant

/** Lifecycle of an imported dataset's structural validation (runs async after upload). */
enum class ValidationStatus {
    PENDING,
    VALIDATING,
    VALID,
    INVALID
}

/** A single validation finding. [line] is 1-based; `0` denotes a file-level error. */
data class ImportError(val line: Int, val message: String)

/**
 * An externally-produced JSONL dataset uploaded into the training bucket. The original upload name
 * is kept ([originalFilename]); the stored object is renamed with a timestamp under a dated
 * `imports/` folder. Validation runs asynchronously and lands in [status] + [errors]. A VALID
 * import is selectable as a tuning dataset in the Training form (its [gcsUri] is fed to Vertex).
 */
data class ImportRecord(
    val id: String,
    val kind: ExportKind,
    val originalFilename: String,
    val storedObjectPath: String,
    val gcsUri: String,
    val lineCount: Int = 0,
    val status: ValidationStatus = ValidationStatus.PENDING,
    val errors: List<ImportError> = emptyList(),
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val validatedAt: Instant? = null,
)

package ai.vishwakarma.labelling.domain

import java.time.Instant

enum class TuningMethod {
    SFT,
    DPO
}

/** Foundation = tune from the stock base; continuation = tune on top of a prior version. */
enum class BaseKind {
    FOUNDATION,
    CONTINUATION
}

/** Where a tuning dataset comes from: a tool-produced export, or an externally-uploaded import. */
enum class DatasetSource {
    EXPORT,
    IMPORT
}

enum class JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}

enum class VersionStatus {
    TRAINING,
    READY,
    FAILED,
    /**
     * Tuning job SUCCEEDED on Vertex but no weights checkpoint was confirmed yet (needs manual
     * fix).
     */
    WEIGHTS_NOT_FOUND
}

/**
 * Single global blessed model (`current`); others `archived` or `none` (candidate deferred to
 * eval).
 */
enum class Promotion {
    CURRENT,
    ARCHIVED,
    NONE
}

data class Hyperparams(
    val epochCount: Int = 3,
    val adapterSize: String = "ADAPTER_SIZE_FOUR",
    val learningRate: Double = 0.0002,
)

/** A managed-OSS tuning job submitted to Vertex (v1beta1 tuningJobs). */
data class TuningJob(
    val id: String,
    val vertexJobName: String? = null,
    val method: TuningMethod,
    val baseKind: BaseKind,
    val baseModelId: String,
    val parentVersionId: String? = null,
    val datasetExportId: String,
    val hyperparams: Hyperparams = Hyperparams(),
    val status: JobStatus = JobStatus.PENDING,
    val outputUri: String,
    val vertexModelResource: String? = null,
    val modelVersionId: String? = null,
    val error: String? = null,
    val submittedBy: String? = null,
    val submittedAt: Instant? = null,
    val finishedAt: Instant? = null,
)

/**
 * A tuned model version in a per-base-family `vMAJOR.MINOR` lineage. Foundation → new major;
 * continuation → minor bump within the parent's major.
 */
data class ModelVersion(
    val id: String,
    val baseModelId: String,
    val family: String,
    val version: String,
    val method: TuningMethod,
    val baseKind: BaseKind,
    val parentVersionId: String? = null,
    val datasetExportIds: List<String> = emptyList(),
    val tuningJobId: String? = null,
    val gcsCheckpointUri: String? = null,
    val vertexModelResource: String? = null,
    val status: VersionStatus = VersionStatus.TRAINING,
    val promotion: Promotion = Promotion.NONE,
    val displayName: String = "",
    val createdBy: String? = null,
    val createdAt: Instant? = null,
) {
    /** Numeric (major, minor) parsed from `version` like "v1.2". */
    val majorMinor: Pair<Int, Int>
        get() =
            version.removePrefix("v").split(".").let {
                (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
            }
}

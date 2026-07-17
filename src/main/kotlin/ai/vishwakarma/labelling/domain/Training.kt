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

/**
 * Lifecycle of a version's serving deployment (VA-67 / advocate serving). NONE = not deployed;
 * DEPLOYING/TEARING_DOWN = an in-flight Vertex LRO the poll advances; LIVE = serving traffic (the
 * only state that bills a replica); FAILED = a leg errored (retry from NONE via a fresh serve).
 * Deploy is multi-step (upload model → deploy to endpoint) but collapses to one DEPLOYING state —
 * the backend owns the sub-steps.
 */
enum class ServingState {
    NONE,
    DEPLOYING,
    LIVE,
    TEARING_DOWN,
    FAILED
}

data class Hyperparams(
    val epochCount: Int = 3,
    val adapterSize: String = "ADAPTER_SIZE_FOUR",
    val learningRate: Double = 0.0002,
    /**
     * `TUNING_MODE_FULL` requests full fine-tuning (VA-59, verified live 2026-07-12): SFT-only —
     * the preferenceOptimizationSpec has no mode field — and [adapterSize] must then be omitted
     * from the request (PEFT-only field). Blank = the API's PEFT default, i.e. the legacy verified
     * LoRA shape with no tuningMode sent.
     */
    val tuningMode: String = "",
) {
    val fullTune: Boolean
        get() = tuningMode == TUNING_MODE_FULL

    companion object {
        const val TUNING_MODE_FULL = "TUNING_MODE_FULL"
    }
}

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
    // ---- serving deployment (VA-67) — distinct from the tuning-side vertexModelResource ----
    val servingState: ServingState = ServingState.NONE,
    /** The serving-container Vertex Model uploaded for deployment (≠ the tuning-registered one). */
    val servingModelResource: String? = null,
    val servingEndpointId: String? = null,
    val servingDeployedModelId: String? = null,
    /** The in-flight Vertex LRO name while DEPLOYING/TEARING_DOWN; null when settled. */
    val servingOperation: String? = null,
    val servedAt: Instant? = null,
    val servingError: String? = null,
    // ---- post-tune behavioral eval (VA-60, LLD §14) ----
    /** The latest completed eval run behind [evalReport] — links to its probe transcripts. */
    val evalRunId: String? = null,
    /**
     * The §14 report JSON (behavior match rate, per-row pass rates, safety table, advisory
     * warnings), frozen when its eval run completes. Advisory — never blocks anything.
     */
    val evalReport: String? = null,
) {
    /** Numeric (major, minor) parsed from `version` like "v1.2". */
    val majorMinor: Pair<Int, Int>
        get() =
            version.removePrefix("v").split(".").let {
                (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
            }
}

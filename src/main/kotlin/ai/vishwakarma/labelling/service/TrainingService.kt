package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.BaseKind
import ai.vishwakarma.labelling.domain.Hyperparams
import ai.vishwakarma.labelling.domain.JobStatus
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.Promotion
import ai.vishwakarma.labelling.domain.TuningJob
import ai.vishwakarma.labelling.domain.TuningMethod
import ai.vishwakarma.labelling.domain.VersionStatus
import ai.vishwakarma.labelling.persistence.BaseModelRepository
import ai.vishwakarma.labelling.persistence.ExportRepository
import ai.vishwakarma.labelling.persistence.ModelVersionRepository
import ai.vishwakarma.labelling.persistence.TuningJobRepository
import ai.vishwakarma.labelling.vertex.TuningService
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Orchestrates tuning: guards (tuning enabled, 1-concurrent), per-family version numbering, the
 * Vertex submit, status polling + finalization, and promotion. Display name
 * `vishwakarma-ai-<family>-vMAJOR.MINOR`; output `gs://<serving>/tuned/<family>-<version>/`.
 */
@Service
class TrainingService(
    private val jobs: TuningJobRepository,
    private val versions: ModelVersionRepository,
    private val baseModels: BaseModelRepository,
    private val exports: ExportRepository,
    private val vertex: TuningService,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun jobs(): List<TuningJob> = jobs.findAll()

    fun version(id: String): ModelVersion? = versions.findById(id)

    fun serveCommand(version: ModelVersion): String = ServeCommand.build(version)

    /** Versions grouped by base family, each lineage sorted by (major, minor). */
    fun versionsByFamily(): Map<String, List<ModelVersion>> =
        versions
            .findAll()
            .groupBy { it.family }
            .mapValues { (_, vs) ->
                vs.sortedWith(compareBy({ it.majorMinor.first }, { it.majorMinor.second }))
            }

    /** Default continue-from base = latest successful (READY) version. */
    fun latestReady(): ModelVersion? =
        versions
            .findAll()
            .filter { it.status == VersionStatus.READY }
            .maxByOrNull { it.createdAt ?: Instant.MIN }

    fun readyVersions(): List<ModelVersion> =
        versions.findAll().filter { it.status == VersionStatus.READY }

    /** The single global blessed (`current`) version, if any. */
    fun currentVersion(): ModelVersion? =
        versions.findAll().firstOrNull { it.promotion == Promotion.CURRENT }

    fun submit(
        baseKind: BaseKind,
        baseModelId: String?,
        parentVersionId: String?,
        datasetExportId: String,
        method: TuningMethod,
        hp: Hyperparams,
        actor: String?,
    ): Either<DomainError, ModelVersion> {
        if (!props.tuning.enabled)
            return DomainError.Invalid("Tuning is disabled in this environment").left()
        if (jobs.anyActive())
            return DomainError.Invalid("A tuning job is already running (1 concurrent max)").left()

        // Form <select>s post "" (not null) for an unselected option; coerce blanks to null so the
        // guards below yield friendly errors instead of Firestore's "path must be non-empty".
        val exportId =
            datasetExportId.ifBlank { null }
                ?: return DomainError.Invalid("Select a dataset export to tune on").left()
        val baseId = baseModelId?.ifBlank { null }
        val nullableParentVersionId = parentVersionId?.ifBlank { null }

        val export =
            exports.findById(exportId)
                ?: return DomainError.NotFound("Dataset export $exportId not found").left()

        // Resolve base model, family, customBaseModel and the next version.
        val resolved =
            when (baseKind) {
                BaseKind.FOUNDATION -> {
                    val base =
                        baseId?.let { baseModels.findById(it) }
                            ?: return DomainError.Invalid("Select a base model").left()
                    Resolved(base.publisherModel, base.id, base.family, null)
                }
                BaseKind.CONTINUATION -> {
                    val parent =
                        nullableParentVersionId?.let { versions.findById(it) }
                            ?: return DomainError.Invalid("Select a parent version").left()
                    if (
                        parent.status != VersionStatus.READY ||
                            parent.gcsCheckpointUri.isNullOrBlank()
                    ) {
                        return DomainError.Invalid("Parent version is not READY with a checkpoint")
                            .left()
                    }
                    val base =
                        baseModels.findById(parent.baseModelId)
                            ?: return DomainError.NotFound("Parent's base model not found").left()
                    Resolved(base.publisherModel, base.id, parent.family, parent.gcsCheckpointUri)
                }
            }

        val existing = versions.findByFamily(resolved.family).map { it.version }
        val newVersion =
            Versioning.nextVersion(
                existing,
                baseKind,
                nullableParentVersionId?.let { versions.findById(it)?.version }
            )
        val displayName = "vishwakarma-ai-${resolved.family}-$newVersion"
        val outputUri = "gs://${props.gcp.servingBucket}/tuned/${resolved.family}-$newVersion/"

        // Create version + job in pre-submit state.
        val versionId = versions.newId()
        val jobId = jobs.newId()
        val now = Instant.now()
        var version =
            ModelVersion(
                id = versionId,
                baseModelId = resolved.baseModelId,
                family = resolved.family,
                version = newVersion,
                method = method,
                baseKind = baseKind,
                parentVersionId = nullableParentVersionId,
                datasetExportIds = listOf(exportId),
                tuningJobId = jobId,
                status = VersionStatus.TRAINING,
                displayName = displayName,
                createdBy = actor,
                createdAt = now,
            )
        var job =
            TuningJob(
                id = jobId,
                method = method,
                baseKind = baseKind,
                baseModelId = resolved.baseModelId,
                parentVersionId = nullableParentVersionId,
                datasetExportId = exportId,
                hyperparams = hp,
                status = JobStatus.PENDING,
                outputUri = outputUri,
                modelVersionId = versionId,
                submittedBy = actor,
                submittedAt = now,
            )
        versions.save(version)
        jobs.save(job)

        // Dev/test: simulate a successful tune without calling Vertex.
        if (props.tuning.dryRun) {
            val checkpoint = "$outputUri" + "custom-trained/dry-run/"
            jobs.save(
                job.copy(
                    vertexJobName = "dry-run/$jobId",
                    status = JobStatus.SUCCEEDED,
                    vertexModelResource = "dry-run-model",
                    finishedAt = Instant.now()
                )
            )
            val ready =
                version.copy(
                    status = VersionStatus.READY,
                    gcsCheckpointUri = checkpoint,
                    vertexModelResource = "dry-run-model"
                )
            versions.save(ready)
            return ready.right()
        }

        return try {
            val jobName =
                vertex.submit(
                    baseModel = resolved.publisherModel,
                    customBaseModel = resolved.customBaseModel,
                    tunedModelDisplayName = displayName,
                    outputUri = outputUri,
                    trainingDatasetUri = export.gcsUri,
                    method = method,
                    hp = hp,
                )
            job = job.copy(vertexJobName = jobName, status = JobStatus.RUNNING)
            jobs.save(job)
            version.right()
        } catch (e: Exception) {
            log.warn("Tuning submit failed: {}", e.message)
            jobs.save(
                job.copy(status = JobStatus.FAILED, error = e.message, finishedAt = Instant.now())
            )
            versions.save(version.copy(status = VersionStatus.FAILED))
            DomainError.Invalid("Tuning submit failed: ${e.message}").left()
        }
    }

    /** Poll Vertex and finalize the version on terminal states. */
    fun pollJob(jobId: String): Either<DomainError, TuningJob> {
        val job = jobs.findById(jobId) ?: return DomainError.NotFound("Job $jobId not found").left()
        val jobName =
            job.vertexJobName ?: return DomainError.Invalid("Job has no Vertex job name").left()
        return try {
            val info = vertex.status(jobName)
            var updated = job.copy(status = info.status)
            when (info.status) {
                JobStatus.SUCCEEDED -> {
                    updated =
                        updated.copy(
                            vertexModelResource = info.modelResource,
                            finishedAt = Instant.now()
                        )
                    job.modelVersionId?.let { vid ->
                        versions.findById(vid)?.let { v ->
                            versions.save(
                                v.copy(
                                    status = VersionStatus.READY,
                                    gcsCheckpointUri = info.checkpointUri ?: job.outputUri,
                                    vertexModelResource = info.modelResource,
                                ),
                            )
                        }
                    }
                }
                JobStatus.FAILED -> {
                    updated = updated.copy(finishedAt = Instant.now())
                    job.modelVersionId?.let { vid ->
                        versions.findById(vid)?.let {
                            versions.save(it.copy(status = VersionStatus.FAILED))
                        }
                    }
                }
                else -> {}
            }
            jobs.save(updated)
            updated.right()
        } catch (e: Exception) {
            DomainError.Invalid("Status poll failed: ${e.message}").left()
        }
    }

    fun promote(versionId: String, target: Promotion): Either<DomainError, ModelVersion> {
        val v =
            versions.findById(versionId) ?: return DomainError.NotFound("Version not found").left()
        if (target == Promotion.CURRENT) {
            if (v.status != VersionStatus.READY)
                return DomainError.Invalid("Only READY versions can be promoted to current").left()
            versions
                .findAll()
                .filter { it.promotion == Promotion.CURRENT }
                .forEach { versions.save(it.copy(promotion = Promotion.NONE)) }
        }
        val updated = v.copy(promotion = target)
        versions.save(updated)
        return updated.right()
    }

    private data class Resolved(
        val publisherModel: String,
        val baseModelId: String,
        val family: String,
        val customBaseModel: String?,
    )
}

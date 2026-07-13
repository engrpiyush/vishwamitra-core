package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.ServingState
import ai.vishwakarma.labelling.domain.VersionStatus
import ai.vishwakarma.labelling.persistence.ModelVersionRepository
import ai.vishwakarma.labelling.serving.ServeRequest
import ai.vishwakarma.labelling.serving.ServingBackend
import ai.vishwakarma.labelling.serving.ServingHandle
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Serving control plane (VA-67): initiate serving of a tuned [ModelVersion], tear it down, and
 * advance the in-flight Vertex LRO on poll — the submit-then-poll idiom (deploy is a 25–35 min
 * operation, no scheduler; the UI polls). Backend-agnostic: the [ServingBackend] owns the substrate
 * particulars, this service owns the guards, one-live invariant, and `ModelVersion` persistence.
 *
 * Invariants: only a READY version with a checkpoint can be served; **at most one version is
 * DEPLOYING/LIVE/TEARING_DOWN at a time** (single shared endpoint, one V100 replica); teardown is
 * always reachable from LIVE (the cost guard — a deployed replica bills until torn down).
 */
@Service
class AdvocateServingService(
    private val versions: ModelVersionRepository,
    backends: List<ServingBackend>,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val backendsById = backends.associateBy { it.id }

    /** The configured backend, or null when misconfigured (guarded before any live call). */
    fun backend(): ServingBackend? = backendsById[props.serving.backend]

    /** Any version currently occupying the shared endpoint (DEPLOYING/LIVE/TEARING_DOWN). */
    fun activeServe(): ModelVersion? =
        versions.findAll().firstOrNull { it.servingState in OCCUPYING }

    fun serve(versionId: String): Either<DomainError, ModelVersion> {
        if (!props.serving.enabled)
            return DomainError.Conflict("Serving is disabled (app.serving.enabled)").left()
        val v =
            versions.findById(versionId)
                ?: return DomainError.NotFound("Version $versionId not found").left()
        if (v.status != VersionStatus.READY)
            return DomainError.Invalid("Only a READY version can be served").left()
        val checkpoint =
            v.gcsCheckpointUri?.takeIf { it.isNotBlank() }
                ?: return DomainError.Invalid("Version has no checkpoint to serve").left()
        if (v.servingState in OCCUPYING)
            return DomainError.Conflict("Version is already ${v.servingState}").left()
        versions
            .findAll()
            .firstOrNull { it.id != v.id && it.servingState in OCCUPYING }
            ?.let {
                return DomainError.Conflict(
                        "${it.displayName} is ${it.servingState} on the shared endpoint — tear it " +
                            "down first (one advocate serves at a time)"
                    )
                    .left()
            }

        if (props.serving.dryRun) {
            val live =
                v.copy(
                    servingState = ServingState.LIVE,
                    servingModelResource = "dry-run-model",
                    servingEndpointId = props.serving.endpointId.ifBlank { "dry-run-endpoint" },
                    servingDeployedModelId = "dry-run-deployed",
                    servingOperation = null,
                    servedAt = Instant.now(),
                    servingError = null,
                )
            versions.save(live)
            return live.right()
        }

        val backend =
            backend()
                ?: return DomainError.Invalid(
                        "No serving backend '${props.serving.backend}' available"
                    )
                    .left()
        val handle = backend.beginServe(ServeRequest(v.displayName, checkpoint))
        val updated = apply(v.copy(servedAt = null), handle)
        versions.save(updated)
        return if (handle.state == ServingState.FAILED)
            DomainError.Invalid("Serve failed: ${handle.error}").left()
        else updated.right()
    }

    fun teardown(versionId: String): Either<DomainError, ModelVersion> {
        val v =
            versions.findById(versionId)
                ?: return DomainError.NotFound("Version $versionId not found").left()
        if (v.servingState != ServingState.LIVE && v.servingState != ServingState.FAILED)
            return DomainError.Invalid(
                    "Only a LIVE (or FAILED) serve can be torn down (state is ${v.servingState})"
                )
                .left()

        if (props.serving.dryRun || v.servingDeployedModelId == null) {
            // Nothing really deployed (dry-run, or a serve that failed before deploy) — just reset.
            val cleared = clearServe(v)
            versions.save(cleared)
            return cleared.right()
        }

        val backend = backend() ?: return DomainError.Invalid("No serving backend available").left()
        val handle = backend.beginTeardown(handleOf(v))
        val updated = apply(v, handle)
        versions.save(updated)
        return if (handle.state == ServingState.FAILED)
            DomainError.Invalid("Teardown failed: ${handle.error}").left()
        else updated.right()
    }

    /** Advance an in-flight DEPLOYING/TEARING_DOWN serve; a settled state is a no-op. */
    fun poll(versionId: String): Either<DomainError, ModelVersion> {
        val v =
            versions.findById(versionId)
                ?: return DomainError.NotFound("Version $versionId not found").left()
        if (v.servingState != ServingState.DEPLOYING && v.servingState != ServingState.TEARING_DOWN)
            return v.right()
        if (props.serving.dryRun) {
            // Dry-run settles instantly at serve/teardown; a lingering in-flight state just
            // resolves.
            val settled =
                if (v.servingState == ServingState.DEPLOYING)
                    v.copy(servingState = ServingState.LIVE, servedAt = Instant.now())
                else clearServe(v)
            versions.save(settled)
            return settled.right()
        }
        val backend = backend() ?: return DomainError.Invalid("No serving backend available").left()
        val updated = apply(v, backend.advance(handleOf(v)))
        versions.save(updated)
        return updated.right()
    }

    private fun handleOf(v: ModelVersion) =
        ServingHandle(
            state = v.servingState,
            modelResource = v.servingModelResource,
            endpointId = v.servingEndpointId,
            deployedModelId = v.servingDeployedModelId,
            operation = v.servingOperation,
            error = v.servingError,
        )

    private fun apply(v: ModelVersion, h: ServingHandle): ModelVersion =
        v.copy(
            servingState = h.state,
            servingModelResource = h.modelResource,
            servingEndpointId = h.endpointId,
            servingDeployedModelId = h.deployedModelId,
            servingOperation = h.operation,
            servingError = h.error,
            servedAt =
                if (h.state == ServingState.LIVE && v.servedAt == null) Instant.now()
                else v.servedAt,
        )

    private fun clearServe(v: ModelVersion): ModelVersion =
        v.copy(
            servingState = ServingState.NONE,
            servingModelResource = null,
            servingEndpointId = null,
            servingDeployedModelId = null,
            servingOperation = null,
            servedAt = null,
            servingError = null,
        )

    private companion object {
        val OCCUPYING = setOf(ServingState.DEPLOYING, ServingState.LIVE, ServingState.TEARING_DOWN)
    }
}

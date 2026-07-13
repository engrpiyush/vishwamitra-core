package ai.vishwakarma.labelling.serving

import ai.vishwakarma.labelling.domain.ServingState

/** What to serve: the tuned checkpoint dir + a display name for the deployed resources. */
data class ServeRequest(val displayName: String, val artifactUri: String)

/**
 * Backend-agnostic snapshot of a serving deployment. The service persists this onto the
 * `ModelVersion` and hands it back to [ServingBackend.advance]/[beginTeardown] to progress — so the
 * multi-step particulars (Vertex: upload → deploy; AWS: its own steps) stay inside the backend and
 * the service stays a generic state pump. A backend never throws for a business outcome: a failed
 * kick-off returns `state = FAILED` with [error]; a transient poll error returns the handle
 * unchanged so the next poll retries.
 */
data class ServingHandle(
    val state: ServingState,
    val modelResource: String? = null,
    val endpointId: String? = null,
    val deployedModelId: String? = null,
    val operation: String? = null,
    val error: String? = null,
)

/**
 * The serving substrate seam (VA-67): deploy a tuned model somewhere it answers `rawPredict`, and
 * tear it down. One impl today (`VertexServingBackend`); an `aws` impl slots in behind the same
 * contract (product LLD W3 substrate-agnostic serving). Selected by `app.serving.backend`.
 */
interface ServingBackend {
    /** Selector matched against `app.serving.backend`. */
    val id: String

    /** Human-readable deploy target (endpoint id / cluster) for display; blank if unconfigured. */
    fun target(): String

    /** Begin serving; returns a `DEPLOYING` handle, or `FAILED` if the kick-off could not start. */
    fun beginServe(req: ServeRequest): ServingHandle

    /**
     * Advance an in-flight `DEPLOYING`/`TEARING_DOWN` handle by one step (settle to `LIVE`/`NONE`,
     * kick the next sub-step, or pass through unchanged while still pending). Terminal states are
     * returned unchanged.
     */
    fun advance(handle: ServingHandle): ServingHandle

    /** Begin teardown of a `LIVE` handle; returns a `TEARING_DOWN` handle (or `FAILED`). */
    fun beginTeardown(handle: ServingHandle): ServingHandle
}

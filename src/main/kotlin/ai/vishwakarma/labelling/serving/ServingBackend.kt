package ai.vishwakarma.labelling.serving

import ai.vishwakarma.labelling.domain.ServingState

/**
 * What to serve: the tuned checkpoint dir + a display name for the deployed resources.
 * [imageOverride] is the family's `BaseModel.servingImage` when set (VA-86) — a per-lineage serving
 * container replacing the global `app.serving.image` pin; null/blank = global.
 */
data class ServeRequest(
    val displayName: String,
    val artifactUri: String,
    val imageOverride: String? = null,
)

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

/** One chat turn for [ServingBackend.chat] — OpenAI role vocabulary (system/user/assistant). */
data class ChatMessage(val role: String, val content: String)

/** A chat call against whatever is LIVE on the substrate (VA-38; consumed by §7.5 in W4). */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int = 512,
    val temperature: Double = 0.7,
)

/** The substrate truth of what is deployed right now — the sweep's reconcile input (§7.4). */
data class Deployment(val id: String, val displayName: String?)

/**
 * The serving substrate seam (VA-67): deploy a tuned model somewhere it answers chat completions,
 * and tear it down. One impl today (`VertexServingBackend`); an `aws` impl slots in behind the same
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

    /** What is actually deployed on the target right now; throws on a transport failure. */
    fun deployments(): List<Deployment>

    /**
     * One chat completion against the currently LIVE deployment; returns the assistant reply text.
     * Throws on transport/parse failures — the caller (AdvocateChatService, §7.5) owns the "not
     * available right now" mapping so raw errors never reach subjects.
     */
    fun chat(req: ChatRequest): String
}

package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.serving.ChatRequest
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** Where the eval's serving stands after a begin/advance/release tick (VA-67). */
enum class EvalTransportState {
    /** The transport is making the version answerable (endpoint: the deploy LRO). */
    PREPARING,
    /** [Stage4EvalTransport.chat] will answer. */
    READY,
    /** The transport is releasing (endpoint: the undeploy LRO). */
    RELEASING,
    /** Nothing remains deployed — the cost guard's settled state. */
    RELEASED,
    /** A leg errored; the reason is on the version's servingError (endpoint) or thrown. */
    FAILED,
}

/**
 * The §14 eval serving seam (VA-67): how the behavioral eval reaches a tuned checkpoint. Two
 * sanctioned transports, selected by `app.serving.eval-transport`:
 * - `endpoint` — a short-lived deploy onto the shared Vertex endpoint through the VA-80 serving
 *   control plane (same one-live invariant, same models/serve page truth, same monitoring); the
 *   eval releases it unconditionally when probing ends, success or failure.
 * - `vllm` — a dev vLLM box serving the checkpoint per the ServeCommand recipe, reached at
 *   `app.serving.eval-vllm-base-url`; its lifecycle is the operator's (begin/release are no-ops),
 *   so the eval only ever chats.
 *
 * All calls are per-version and idempotent per state — the eval poll loop re-invokes freely.
 */
interface Stage4EvalTransport {
    /** Selector matched against `app.serving.eval-transport`. */
    val id: String

    /** Start making [version] answerable; returns the resulting state or throws a clear error. */
    fun begin(version: ModelVersion): EvalTransportState

    /** Advance/observe an in-flight [begin]; settled states are returned unchanged. */
    fun advance(version: ModelVersion): EvalTransportState

    /** One chat completion against the served [version]; throws on transport failures. */
    fun chat(version: ModelVersion, request: ChatRequest): String

    /**
     * The unconditional release pump (the cost guard): whatever state the serve is in, move it
     * toward RELEASED — kick the teardown, advance its LRO, or report it settled. Idempotent;
     * called every poll until it answers [EvalTransportState.RELEASED] (or FAILED, which leaves the
     * retryable truth on the models/serve page).
     */
    fun release(version: ModelVersion): EvalTransportState
}

/**
 * Dev vLLM transport (VA-67): the checkpoint is already served off-GCP per the ServeCommand recipe
 * (`--served-model-name <family>-<version>`); this transport just speaks OpenAI chat completions at
 * the configured base URL. No lifecycle — the box is rented, served and torn down by the operator
 * (ServeCommand step 3), so begin is READY and release is RELEASED.
 */
@Component
class VllmEvalTransport(private val props: AppProperties) : Stage4EvalTransport {

    private val rest = RestClient.create()

    override val id = "vllm"

    override fun begin(version: ModelVersion): EvalTransportState {
        check(props.serving.evalVllmBaseUrl.isNotBlank()) {
            "No vLLM base URL configured (app.serving.eval-vllm-base-url) — serve the " +
                "checkpoint per the model's Serve page and point the URL at it"
        }
        return EvalTransportState.READY
    }

    override fun advance(version: ModelVersion): EvalTransportState = EvalTransportState.READY

    @Suppress("UNCHECKED_CAST")
    override fun chat(version: ModelVersion, request: ChatRequest): String {
        val base = props.serving.evalVllmBaseUrl.trimEnd('/')
        val body =
            mapOf(
                // ServeCommand pins --served-model-name to "<family>-<version>".
                "model" to "${version.family}-${version.version}",
                "messages" to
                    request.messages.map { mapOf("role" to it.role, "content" to it.content) },
                "max_tokens" to request.maxTokens,
                "temperature" to request.temperature,
                // Qwen3 templates default to thinking mode → a stray </think> opens replies.
                "chat_template_kwargs" to mapOf("enable_thinking" to false),
            )
        val response =
            rest
                .post()
                .uri("$base/v1/chat/completions")
                .header("Content-Type", "application/json")
                .body(Json.writeLine(body))
                .retrieve()
                .body(String::class.java) ?: error("empty vLLM response")
        val map =
            Json.parse(response) as? Map<String, Any?> ?: error("bad chat response: $response")
        val choices = map["choices"] as? List<Map<String, Any?>>
        val message = choices?.firstOrNull()?.get("message") as? Map<String, Any?>
        return message?.get("content") as? String
            ?: error("chat response carried no message content: $response")
    }

    override fun release(version: ModelVersion): EvalTransportState = EvalTransportState.RELEASED
}

/**
 * VA-62-style scripted reply for the dry-run posture: deterministic, no live endpoint. The reply
 * names the intent it is imitating so a dev walk reads sensibly in the transcript table.
 */
object DryRunEvalReplies {
    fun reply(question: String): String =
        "[dry-run advocate] Grounded reply to: ${question.take(160)}"
}

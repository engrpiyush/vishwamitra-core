package ai.vishwakarma.labelling.serving

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ServingState
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.vertex.VertexEndpointClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Vertex Prediction impl of [ServingBackend] (VA-74 pins). A serve is two chained LROs collapsed
 * into one `DEPLOYING` state: **upload** a serving-container model wrapping the checkpoint, then
 * **deploy** it onto the shared endpoint. Teardown is one `undeployModel` LRO. [advance] owns the
 * upload→deploy hand-off, keyed off which fields the completed operation returned.
 */
@Component
class VertexServingBackend(
    private val client: VertexEndpointClient,
    private val props: AppProperties,
) : ServingBackend {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = "vertex"

    override fun target(): String = props.serving.endpointId

    override fun beginServe(req: ServeRequest): ServingHandle {
        val endpointId = props.serving.endpointId
        if (endpointId.isBlank())
            return failed("No serving endpoint configured (app.serving.endpoint-id)")
        if (props.serving.image.isBlank())
            return failed("No serving image configured (app.serving.image)")
        return try {
            val op = client.uploadModel(req.displayName, req.artifactUri)
            ServingHandle(
                state = ServingState.DEPLOYING,
                endpointId = endpointId,
                operation = op,
            )
        } catch (e: Exception) {
            log.warn("uploadModel kick-off failed: {}", e.message)
            failed("Model upload failed: ${e.message}")
        }
    }

    override fun advance(handle: ServingHandle): ServingHandle =
        when (handle.state) {
            ServingState.DEPLOYING -> advanceDeploy(handle)
            ServingState.TEARING_DOWN -> advanceTeardown(handle)
            else -> handle
        }

    private fun advanceDeploy(handle: ServingHandle): ServingHandle {
        val opName = handle.operation ?: return failed("Deploying handle lost its operation")
        val op =
            try {
                client.operation(opName)
            } catch (e: Exception) {
                // Transient — hold the handle so the next poll retries rather than failing a
                // 25–35 min deploy on a network blip.
                log.warn("operation poll failed (holding): {}", e.message)
                return handle
            }
        if (!op.done) return handle
        op.error?.let {
            return handle.copy(state = ServingState.FAILED, error = it)
        }
        // Upload finished → kick the deploy step.
        if (handle.modelResource == null && op.model != null) {
            return try {
                val deployOp =
                    client.deployModel(handle.endpointId!!, op.model, deployName(op.model))
                handle.copy(modelResource = op.model, operation = deployOp)
            } catch (e: Exception) {
                log.warn("deployModel kick-off failed: {}", e.message)
                handle.copy(
                    state = ServingState.FAILED,
                    modelResource = op.model,
                    error = "Deploy failed: ${e.message}",
                )
            }
        }
        // Deploy finished → LIVE.
        if (op.deployedModelId != null) {
            return handle.copy(
                state = ServingState.LIVE,
                deployedModelId = op.deployedModelId,
                operation = null,
                error = null,
            )
        }
        return handle.copy(
            state = ServingState.FAILED,
            error = "Operation completed without a model or deployedModelId",
        )
    }

    private fun advanceTeardown(handle: ServingHandle): ServingHandle {
        val opName = handle.operation ?: return failed("Tearing-down handle lost its operation")
        val op =
            try {
                client.operation(opName)
            } catch (e: Exception) {
                log.warn("teardown poll failed (holding): {}", e.message)
                return handle
            }
        if (!op.done) return handle
        op.error?.let {
            // Keep deployedModelId so the operator can retry teardown.
            return handle.copy(state = ServingState.FAILED, operation = null, error = it)
        }
        return ServingHandle(state = ServingState.NONE)
    }

    override fun beginTeardown(handle: ServingHandle): ServingHandle {
        val endpointId = handle.endpointId ?: props.serving.endpointId
        val deployedModelId =
            handle.deployedModelId ?: return failed("No deployed model to tear down")
        return try {
            val op = client.undeployModel(endpointId, deployedModelId)
            handle.copy(state = ServingState.TEARING_DOWN, operation = op, error = null)
        } catch (e: Exception) {
            log.warn("undeployModel kick-off failed: {}", e.message)
            handle.copy(state = ServingState.FAILED, error = "Teardown failed: ${e.message}")
        }
    }

    override fun deployments(): List<Deployment> {
        val endpointId = props.serving.endpointId
        if (endpointId.isBlank()) return emptyList()
        return client.deployedModels(endpointId).map { Deployment(it.id, it.displayName) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun chat(req: ChatRequest): String {
        val endpointId = props.serving.endpointId
        require(endpointId.isNotBlank()) { "No serving endpoint configured" }
        val body =
            mapOf(
                // --served-model-name pin (VA-74): every deployed advocate answers as "advocate".
                "model" to "advocate",
                "messages" to
                    req.messages.map { mapOf("role" to it.role, "content" to it.content) },
                "max_tokens" to req.maxTokens,
                "temperature" to req.temperature,
                // Qwen3 templates default to thinking mode → a stray </think> opens replies.
                "chat_template_kwargs" to mapOf("enable_thinking" to false),
            )
        val response = client.rawPredict(endpointId, body)
        val map =
            Json.parse(response) as? Map<String, Any?> ?: error("bad chat response: $response")
        val choices = map["choices"] as? List<Map<String, Any?>>
        val message = choices?.firstOrNull()?.get("message") as? Map<String, Any?>
        return message?.get("content") as? String
            ?: error("chat response carried no message content: $response")
    }

    private fun deployName(modelResource: String) = "serve-${modelResource.substringAfterLast('/')}"

    private fun failed(msg: String) = ServingHandle(state = ServingState.FAILED, error = msg)
}

package ai.vishwakarma.labelling.vertex

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.serialization.Json
import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Result of an `operations` GET. [model] is set when a completed `uploadModel` op resolves;
 * [deployedModelId] when a completed `deployModel` op resolves; [error] carries a failed op's
 * message (a done op with an error field).
 */
data class VertexOperation(
    val done: Boolean,
    val model: String?,
    val deployedModelId: String?,
    val error: String?,
)

/** One `deployedModels[]` entry from an endpoint GET — the substrate truth the sweep reconciles. */
data class VertexDeployedModel(
    val id: String,
    val model: String?,
    val displayName: String?,
)

/**
 * Raw Vertex AI Prediction REST for the serving control plane (VA-67): upload a serving-container
 * model, deploy/undeploy it on a shared endpoint, and poll the resulting long-running operations.
 * Mirrors [TuningService]'s posture — v1 REST authed with the app SA's ADC bearer token, region
 * from `app.serving.region`. Kept dumb: no state, no retries; the backend/service own
 * orchestration.
 */
@Component
class VertexEndpointClient(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.create()

    private fun token(): String =
        GoogleCredentials.getApplicationDefault()
            .createScoped("https://www.googleapis.com/auth/cloud-platform")
            .also { it.refreshIfExpired() }
            .accessToken
            .tokenValue

    private fun region() = props.serving.region

    private fun base() = "https://${region()}-aiplatform.googleapis.com/v1"

    private fun parent() = "projects/${props.gcp.projectId}/locations/${region()}"

    /**
     * Upload a serving model wrapping [artifactUri] (the tuned checkpoint dir) with the configured
     * vLLM container. Returns the `uploadModel` operation name; poll [operation] until done, then
     * read `model` for the registered resource.
     */
    fun uploadModel(displayName: String, artifactUri: String): String {
        val s = props.serving
        val body =
            mapOf(
                "model" to
                    mapOf(
                        "displayName" to displayName,
                        "artifactUri" to artifactUri,
                        "containerSpec" to
                            mapOf(
                                "imageUri" to s.image,
                                "args" to s.servedModelArgs,
                                "ports" to listOf(mapOf("containerPort" to 8080)),
                                "predictRoute" to "/v1/chat/completions",
                                "healthRoute" to "/health",
                            ),
                    )
            )
        return post("${base()}/${parent()}/models:upload", body, "uploadModel")
    }

    /**
     * Deploy [modelResource] onto [endpointId] with the configured V100 dedicated resources.
     * Returns the `deployModel` operation name; poll [operation] for `deployedModelId`.
     */
    fun deployModel(endpointId: String, modelResource: String, displayName: String): String {
        val s = props.serving
        val body =
            mapOf(
                "deployedModel" to
                    mapOf(
                        "model" to modelResource,
                        "displayName" to displayName,
                        "dedicatedResources" to
                            mapOf(
                                "machineSpec" to
                                    mapOf(
                                        "machineType" to s.machineType,
                                        "acceleratorType" to s.acceleratorType,
                                        "acceleratorCount" to s.acceleratorCount,
                                    ),
                                "minReplicaCount" to 1,
                                "maxReplicaCount" to 1,
                            ),
                    ),
                "trafficSplit" to mapOf("0" to 100),
            )
        return post(
            "${base()}/${parent()}/endpoints/$endpointId:deployModel",
            body,
            "deployModel",
        )
    }

    /** Undeploy [deployedModelId] from [endpointId]. Returns the `undeployModel` operation name. */
    fun undeployModel(endpointId: String, deployedModelId: String): String =
        post(
            "${base()}/${parent()}/endpoints/$endpointId:undeployModel",
            mapOf("deployedModelId" to deployedModelId),
            "undeployModel",
        )

    /** What is actually deployed on [endpointId] right now (empty list when nothing is). */
    @Suppress("UNCHECKED_CAST")
    fun deployedModels(endpointId: String): List<VertexDeployedModel> {
        val response =
            rest
                .get()
                .uri("${base()}/${parent()}/endpoints/$endpointId")
                .header("Authorization", "Bearer ${token()}")
                .retrieve()
                .body(String::class.java) ?: error("empty endpoint response")
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad endpoint response")
        val deployed = map["deployedModels"] as? List<Map<String, Any?>> ?: emptyList()
        return deployed.mapNotNull { dm ->
            val id = dm["id"] as? String ?: return@mapNotNull null
            VertexDeployedModel(
                id = id,
                model = dm["model"] as? String,
                displayName = dm["displayName"] as? String,
            )
        }
    }

    /**
     * Pass [body] (an OpenAI-style chat-completions JSON payload) through to the endpoint's
     * container via `rawPredict` and return the raw response JSON. IAM/ADC replaces the v1.2 bearer
     * wall (VA-74); the vLLM route is pinned at model upload (`/v1/chat/completions`).
     */
    fun rawPredict(endpointId: String, body: Map<String, Any?>): String =
        rest
            .post()
            .uri("${base()}/${parent()}/endpoints/$endpointId:rawPredict")
            .header("Authorization", "Bearer ${token()}")
            .header("Content-Type", "application/json")
            .body(Json.writeLine(body))
            .retrieve()
            .body(String::class.java) ?: error("empty rawPredict response")

    /** Poll an operation by its resource name (host derived from the name's region segment). */
    @Suppress("UNCHECKED_CAST")
    fun operation(opName: String): VertexOperation {
        val response =
            rest
                .get()
                .uri("${hostFor(opName)}/$opName")
                .header("Authorization", "Bearer ${token()}")
                .retrieve()
                .body(String::class.java) ?: error("empty operation response")
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad operation response")
        val done = map["done"] as? Boolean ?: false
        val error = (map["error"] as? Map<*, *>)?.get("message") as? String
        val resp = map["response"] as? Map<*, *>
        return VertexOperation(
            done = done,
            model = resp?.get("model") as? String,
            deployedModelId = (resp?.get("deployedModel") as? Map<*, *>)?.get("id") as? String,
            error = error,
        )
    }

    private fun post(url: String, body: Any, label: String): String {
        log.info("Vertex {} → {}", label, url)
        val response =
            rest
                .post()
                .uri(url)
                .header("Authorization", "Bearer ${token()}")
                .body(body)
                .retrieve()
                .body(String::class.java) ?: error("empty $label response")
        val map = Json.parse(response) as? Map<*, *> ?: error("bad $label response: $response")
        return map["name"] as? String ?: error("$label response missing operation name: $response")
    }

    /** The regional API host serving [resourceName] (`…/locations/<loc>/…`). */
    private fun hostFor(resourceName: String): String {
        val loc =
            resourceName.substringAfter("/locations/", "").substringBefore('/').ifBlank { region() }
        return "https://$loc-aiplatform.googleapis.com/v1"
    }
}

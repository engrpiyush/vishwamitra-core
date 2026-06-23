package ai.vishwakarma.labelling.vertex

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Hyperparams
import ai.vishwakarma.labelling.domain.JobStatus
import ai.vishwakarma.labelling.domain.TuningMethod
import ai.vishwakarma.labelling.serialization.Json
import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** Outcome of a tuningJobs GET. */
data class VertexJobInfo(
    val status: JobStatus,
    val modelResource: String?,
    val checkpointUri: String?,
    val raw: String,
)

/**
 * Reproduces the verified Vertex Managed OSS Tuning call (see continue-tune.sh): a v1beta1
 * `tuningJobs` POST with baseModel (+ customBaseModel for continuation) + outputUri + learningRate +
 * supervised/preference spec, authed with the app SA's ADC bearer token.
 */
@Component
class TuningService(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.create()

    private fun token(): String =
        GoogleCredentials.getApplicationDefault()
            .createScoped("https://www.googleapis.com/auth/cloud-platform")
            .also { it.refreshIfExpired() }
            .accessToken.tokenValue

    private fun base() = "https://${props.gcp.region}-aiplatform.googleapis.com/v1beta1"

    /**
     * Submit a tuning job. Returns the Vertex job resource name (projects/.../tuningJobs/123).
     * [customBaseModel] is null for a foundation tune, or the prior export's merged-weights root for
     * a continuation. [outputUri] is required for OSS.
     */
    fun submit(
        baseModel: String,
        customBaseModel: String?,
        tunedModelDisplayName: String,
        outputUri: String,
        trainingDatasetUri: String,
        method: TuningMethod,
        hp: Hyperparams,
    ): String {
        val specKey = if (method == TuningMethod.SFT) "supervisedTuningSpec" else "preferenceOptimizationSpec"
        val body = buildMap {
            put("baseModel", baseModel)
            if (!customBaseModel.isNullOrBlank()) put("customBaseModel", customBaseModel)
            put("tunedModelDisplayName", tunedModelDisplayName)
            put("outputUri", outputUri)
            put(
                specKey,
                mapOf(
                    "trainingDatasetUri" to trainingDatasetUri,
                    "hyperParameters" to mapOf(
                        "epochCount" to hp.epochCount.toString(),
                        "adapterSize" to hp.adapterSize,
                        "learningRate" to hp.learningRate,
                    ),
                ),
            )
        }
        val url = "${base()}/projects/${props.gcp.projectId}/locations/${props.gcp.region}/tuningJobs"
        log.info("Submitting {} tuning job '{}' (continuation={})", method, tunedModelDisplayName, customBaseModel != null)
        val response = rest.post().uri(url)
            .header("Authorization", "Bearer ${token()}")
            .body(body)
            .retrieve()
            .body(String::class.java) ?: error("empty tuningJobs response")
        val map = Json.parse(response) as? Map<*, *> ?: error("bad tuningJobs response: $response")
        return map["name"] as? String ?: error("tuningJobs response missing name: $response")
    }

    @Suppress("UNCHECKED_CAST")
    fun status(jobName: String): VertexJobInfo {
        val response = rest.get().uri("${base()}/$jobName")
            .header("Authorization", "Bearer ${token()}")
            .retrieve()
            .body(String::class.java) ?: error("empty status response")
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad status response")
        val state = map["state"] as? String ?: "JOB_STATE_UNSPECIFIED"
        val tuned = map["tunedModel"] as? Map<String, Any?>
        return VertexJobInfo(
            status = mapState(state),
            modelResource = tuned?.get("model") as? String,
            checkpointUri = (map["outputUri"] as? String) ?: (tuned?.get("checkpointUri") as? String),
            raw = response,
        )
    }

    private fun mapState(state: String): JobStatus = when (state) {
        "JOB_STATE_SUCCEEDED" -> JobStatus.SUCCEEDED
        "JOB_STATE_FAILED", "JOB_STATE_CANCELLED", "JOB_STATE_EXPIRED" -> JobStatus.FAILED
        else -> JobStatus.RUNNING
    }
}

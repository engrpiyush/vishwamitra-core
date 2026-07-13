package ai.vishwakarma.labelling.vertex

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Hyperparams
import ai.vishwakarma.labelling.domain.JobStatus
import ai.vishwakarma.labelling.domain.TuningMethod
import ai.vishwakarma.labelling.serialization.Json
import com.google.auth.oauth2.GoogleCredentials
import java.math.BigDecimal
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
 * `tuningJobs` POST with baseModel (+ customBaseModel for continuation) + outputUri +
 * learningRate + supervised/preference spec, authed with the app SA's ADC bearer token.
 */
@Component
class TuningService(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.create()

    private fun token(): String =
        GoogleCredentials.getApplicationDefault()
            .createScoped("https://www.googleapis.com/auth/cloud-platform")
            .also { it.refreshIfExpired() }
            .accessToken
            .tokenValue

    /** tuningJobs location: `app.tuning.region` when set (regional catalogs), else home region. */
    private fun region() = props.tuning.region.ifBlank { props.gcp.region }

    private fun base() = "https://${region()}-aiplatform.googleapis.com/v1beta1"

    /**
     * Submit a tuning job. Returns the Vertex job resource name (projects/.../tuningJobs/123).
     * [customBaseModel] is null for a foundation tune, or the prior export's merged-weights root
     * for a continuation. [outputUri] is required for OSS.
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
        val specKey =
            if (method == TuningMethod.SFT) "supervisedTuningSpec" else "preferenceOptimizationSpec"
        val body = buildMap {
            put("baseModel", baseModel)
            if (!customBaseModel.isNullOrBlank()) put("customBaseModel", customBaseModel)
            put("tunedModelDisplayName", tunedModelDisplayName)
            put("outputUri", outputUri)
            put(
                specKey,
                buildMap {
                    put("trainingDatasetUri", trainingDatasetUri)
                    // tuningMode is a supervisedTuningSpec-only field (the PO spec has none), and
                    // it is only sent when explicitly requested — a blank mode keeps the legacy
                    // verified LoRA shape byte-for-byte.
                    if (method == TuningMethod.SFT && hp.tuningMode.isNotBlank()) {
                        put("tuningMode", hp.tuningMode)
                    }
                    put(
                        "hyperParameters",
                        buildMap {
                            put("epochCount", hp.epochCount.toString())
                            // adapterSize is PEFT-only: a TUNING_MODE_FULL submit omits it.
                            if (!hp.fullTune) put("adapterSize", hp.adapterSize)
                            // Jackson renders a raw Double like 0.0002 in scientific notation
                            // ("2.0E-4"), which Vertex's tuningJobs parser rejects with an opaque
                            // 500 INTERNAL. A BigDecimal from the plain-decimal string serializes
                            // as a plain JSON number (e.g. 0.00020) that the API accepts.
                            put("learningRate", BigDecimal(hp.learningRate.toString()))
                        },
                    )
                },
            )
        }
        val url = "${base()}/projects/${props.gcp.projectId}/locations/${region()}/tuningJobs"
        log.info(
            "Submitting {} tuning job '{}' (continuation={}) dataset={} body={}",
            method,
            tunedModelDisplayName,
            customBaseModel != null,
            trainingDatasetUri,
            Json.writeLine(body),
        )
        val response =
            rest
                .post()
                .uri(url)
                .header("Authorization", "Bearer ${token()}")
                .body(body)
                .retrieve()
                .body(String::class.java) ?: error("empty tuningJobs response")
        val map = Json.parse(response) as? Map<*, *> ?: error("bad tuningJobs response: $response")
        return map["name"] as? String ?: error("tuningJobs response missing name: $response")
    }

    /**
     * The regional API host serving [jobName] (`projects/…/locations/<loc>/tuningJobs/…`), so a job
     * stays pollable even when `app.tuning.region` differs from the region it was submitted in
     * (e.g. a later boot without TUNING_REGION set).
     */
    private fun hostFor(jobName: String): String {
        val loc =
            jobName.substringAfter("/locations/", "").substringBefore('/').ifBlank { region() }
        return "https://$loc-aiplatform.googleapis.com/v1beta1"
    }

    @Suppress("UNCHECKED_CAST")
    fun status(jobName: String): VertexJobInfo {
        val response =
            rest
                .get()
                .uri("${hostFor(jobName)}/$jobName")
                .header("Authorization", "Bearer ${token()}")
                .retrieve()
                .body(String::class.java) ?: error("empty status response")
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad status response")
        val state = map["state"] as? String ?: "JOB_STATE_UNSPECIFIED"
        val tuned = map["tunedModel"] as? Map<String, Any?>
        return VertexJobInfo(
            status = mapState(state),
            modelResource = tuned?.get("model") as? String,
            checkpointUri =
                (map["outputUri"] as? String) ?: (tuned?.get("checkpointUri") as? String),
            raw = response,
        )
    }

    private fun mapState(state: String): JobStatus =
        when (state) {
            "JOB_STATE_SUCCEEDED" -> JobStatus.SUCCEEDED
            "JOB_STATE_FAILED",
            "JOB_STATE_CANCELLED",
            "JOB_STATE_EXPIRED" -> JobStatus.FAILED
            else -> JobStatus.RUNNING
        }
}

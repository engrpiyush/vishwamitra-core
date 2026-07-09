package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.serialization.Json
import com.google.auth.oauth2.GoogleCredentials
import kotlin.math.abs
import kotlin.math.sqrt
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * Embedding task type (Vertex `task_type`). Claims embed as [SEMANTIC_SIMILARITY] (kNN blocking,
 * LLD §11.4); entity surfaces as [CLASSIFICATION] (resolution, §11.3).
 */
enum class EmbeddingTaskType {
    SEMANTIC_SIMILARITY,
    CLASSIFICATION,
}

/**
 * The Stage 3 embedding seam (LLD §11.4): one text in, one vector out. Real implementation = Vertex
 * `gemini-embedding-001`; dry-run = deterministic pseudo-vectors (§11.12) so dev needs no GCP.
 * [versionStamp] identifies the vector space — it is stamped on every embedded node
 * (`embeddingModelVersion`) and any mismatch marks the vector stale (LLD §15 #6: never mix spaces).
 * The stamp covers model + dimensions, and differs between real and pseudo vectors, so flipping
 * dry-run (or resizing dims) forces a re-embed.
 */
interface EmbeddingService {
    val versionStamp: String

    val dimensions: Int

    fun embed(text: String, taskType: EmbeddingTaskType): List<Double>
}

/**
 * Vertex `gemini-embedding-001` via ADC — same auth posture as
 * [ai.vishwakarma.labelling.drafting.GeminiDrafting], no API keys. The model accepts a **single
 * input per request** (API constraint, LLD §11.4), so callers batch by looping; the EMBED phase
 * bounds work per poll tick instead.
 *
 * Native 3072-dim output is unit-normalized by the service; MRL-truncated outputs (dims < 3072) are
 * NOT, so those are re-normalized client-side before storage (LLD §8.2).
 */
class VertexEmbeddingService(private val props: AppProperties) : EmbeddingService {

    private val rest = RestClient.create()

    override val dimensions: Int
        get() = props.stage3.embeddingDimensions

    override val versionStamp: String
        get() = "${props.stage3.embeddingModel}:$dimensions"

    override fun embed(text: String, taskType: EmbeddingTaskType): List<Double> =
        try {
            predict(text, taskType)
        } catch (e: RestClientResponseException) {
            // LLD §15 #1: transient provider errors get one same-poll retry; anything that fails
            // again propagates verbatim and fails the run (Retry resumes from the cursor).
            if (e.statusCode.is5xxServerError || e.statusCode.value() == 429)
                predict(text, taskType)
            else throw e
        }

    private fun predict(text: String, taskType: EmbeddingTaskType): List<Double> {
        val s3 = props.stage3
        // Embedding models are served regionally (unlike generateContent's "global" alias) —
        // blank falls back to the app region; override via app.stage3.embedding-location.
        val location = s3.embeddingLocation.ifBlank { props.gcp.region }
        val host =
            if (location == "global") "aiplatform.googleapis.com"
            else "$location-aiplatform.googleapis.com"
        val url =
            "https://$host/v1/projects/${props.gcp.projectId}" +
                "/locations/$location/publishers/google/models/${s3.embeddingModel}:predict"
        val token =
            GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform")
                .also { it.refreshIfExpired() }
                .accessToken
                .tokenValue
        val body =
            mapOf(
                // task_type is snake_case, outputDimensionality camelCase — the documented shapes.
                "instances" to listOf(mapOf("task_type" to taskType.name, "content" to text)),
                "parameters" to mapOf("outputDimensionality" to dimensions),
            )
        val response =
            rest
                .post()
                .uri(url)
                .header("Authorization", "Bearer $token")
                .body(body)
                .retrieve()
                .body(String::class.java) ?: error("empty embedding response")
        val values = extractValues(response)
        check(values.size == dimensions) {
            "embedding has ${values.size} dims, expected $dimensions"
        }
        return if (dimensions < NATIVE_DIMENSIONS) renormalize(values) else values
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractValues(response: String): List<Double> {
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad embedding response")
        val predictions =
            map["predictions"] as? List<Map<String, Any?>> ?: error("no predictions in response")
        val embeddings =
            predictions.firstOrNull()?.get("embeddings") as? Map<String, Any?>
                ?: error("no embeddings in response")
        val values = embeddings["values"] as? List<Number> ?: error("no embedding values")
        return values.map { it.toDouble() }
    }

    companion object {
        /** gemini-embedding-001's full output; only truncated (MRL) outputs need re-normalizing. */
        const val NATIVE_DIMENSIONS = 3072
    }
}

/**
 * The §11.4 embedded-text composition: `"{type}: {text} ({claimedDate})"`. The type prefix
 * separates "knows Kotlin" (SKILL) from "used Kotlin once" (EPISODE) in vector space; missing parts
 * are simply omitted.
 */
fun ClaimToEmbed.embeddingText(): String = buildString {
    type?.let { append(it).append(": ") }
    append(text)
    claimedDate?.let { append(" (").append(it).append(")") }
}

/** Scale [values] to unit L2 norm (client-side re-normalization for MRL-truncated dims). */
internal fun renormalize(values: List<Double>): List<Double> {
    val norm = sqrt(values.sumOf { it * it })
    if (norm == 0.0) return values
    return values.map { it / norm }
}

/**
 * Dry-run embeddings (LLD §11.12): seeded character-trigram hashing — deterministic across runs,
 * similar text ⇒ similar vector, zero GCP. Each trigram hashes to a (bucket, sign) pair; the
 * accumulated vector is unit-normalized. The distinct [versionStamp] ("pseudo:…") means turning
 * dry-run off marks every pseudo vector stale, so real embeddings replace them automatically.
 */
class PseudoEmbeddingService(override val dimensions: Int) : EmbeddingService {

    override val versionStamp: String
        get() = "pseudo:$dimensions"

    override fun embed(text: String, taskType: EmbeddingTaskType): List<Double> {
        val v = DoubleArray(dimensions)
        val normalized = text.lowercase().replace(Regex("\\s+"), " ").trim()
        val padded = "^$normalized$"
        for (i in 0..padded.length - 3) {
            // Deterministic 32-bit FNV-1a over the trigram — stable across JVMs (unlike
            // String.hashCode it is seed-controlled here and documented).
            var h = SEED
            for (c in padded.substring(i, i + 3)) {
                h = (h xor c.code) * FNV_PRIME
            }
            val bucket = abs(h % dimensions)
            v[bucket] += if ((h shr 31) == 0) 1.0 else -1.0
        }
        return renormalize(v.toList())
    }

    companion object {
        private const val SEED = 0x811C9DC5.toInt()
        private const val FNV_PRIME = 16777619
    }
}

/**
 * Picks the embedding implementation: pseudo vectors in dry-run (dev default), Vertex otherwise —
 * per-leg flag (LLD §11.12), so pseudo embeddings can pair with a real judge and vice versa.
 */
@Configuration
class EmbeddingConfig {

    private val log = LoggerFactory.getLogger(EmbeddingConfig::class.java)

    @Bean
    fun embeddingService(props: AppProperties): EmbeddingService =
        if (props.stage3.embeddingsDryRun) {
            log.info(
                "Stage 3 dry-run: pseudo embeddings ({} dims)",
                props.stage3.embeddingDimensions
            )
            PseudoEmbeddingService(props.stage3.embeddingDimensions)
        } else {
            VertexEmbeddingService(props)
        }
}

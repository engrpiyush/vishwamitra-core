package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.StageConfigService
import ai.vishwakarma.labelling.vertex.VertexBackoff
import com.google.auth.oauth2.GoogleCredentials
import kotlin.math.abs
import kotlin.math.sqrt
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

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
class VertexEmbeddingService(private val config: StageConfigService) : EmbeddingService {

    private val rest = RestClient.create()

    override val dimensions: Int
        get() = config.stage3().embeddingDimensions

    override val versionStamp: String
        get() = "${config.stage3().embeddingModel}:$dimensions"

    override fun embed(text: String, taskType: EmbeddingTaskType): List<Double> =
        // LLD §15 #1, hardened 2026-07-11: gemini-embedding takes ONE text per request, so a
        // phase burst hits the per-minute RPM quota fast — 429/5xx back off into the next quota
        // window (ladder from app.gcp.vertex-backoff-ms; empty = fail fast). A still-failing
        // call propagates verbatim and fails the run (Retry resumes from the cursor).
        VertexBackoff.retrying(
            config.boot.gcp.vertexBackoffMs,
            "embed ${config.stage3().embeddingModel}"
        ) {
            predict(text, taskType)
        }

    private fun predict(text: String, taskType: EmbeddingTaskType): List<Double> {
        val s3 = config.stage3()
        // Embedding models are served regionally (unlike generateContent's "global" alias) —
        // blank falls back to the app region; override via app.stage3.embedding-location.
        val location = s3.embeddingLocation.ifBlank { config.boot.gcp.region }
        val host =
            if (location == "global") "aiplatform.googleapis.com"
            else "$location-aiplatform.googleapis.com"
        val url =
            "https://$host/v1/projects/${config.boot.gcp.projectId}" +
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
 * The same `gemini-embedding-001` through the Gemini Developer API
 * (`generativelanguage.googleapis.com`, API-key auth) — the 2026-07-11 quota workaround: this
 * project's Vertex `gemini-embedding` quota is 5 RPM in every region (not increasable), while the
 * Developer API's paid tier serves 3000+ RPM. Same model + same dimensions ⇒ the SAME
 * [versionStamp] as [VertexEmbeddingService], deliberately: vectors are interchangeable and
 * flipping transports never marks anything stale. Trade-offs: key-based auth (env `GEMINI_API_KEY`;
 * never logged/serialized) and global processing (no in-region residency).
 */
class GeminiApiEmbeddingService(private val config: StageConfigService) : EmbeddingService {

    private val rest = RestClient.create()

    override val dimensions: Int
        get() = config.stage3().embeddingDimensions

    override val versionStamp: String
        get() = "${config.stage3().embeddingModel}:$dimensions"

    override fun embed(text: String, taskType: EmbeddingTaskType): List<Double> =
        VertexBackoff.retrying(
            config.boot.gcp.vertexBackoffMs,
            "embedContent ${config.stage3().embeddingModel}",
        ) {
            embedContent(text, taskType)
        }

    private fun embedContent(text: String, taskType: EmbeddingTaskType): List<Double> {
        val model = config.stage3().embeddingModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent"
        val body =
            mapOf(
                "model" to "models/$model",
                "content" to mapOf("parts" to listOf(mapOf("text" to text))),
                "taskType" to taskType.name,
                "outputDimensionality" to dimensions,
            )
        val response =
            rest
                .post()
                .uri(url)
                .header("x-goog-api-key", config.stage3().geminiApiKey)
                .body(body)
                .retrieve()
                .body(String::class.java) ?: error("empty embedContent response")
        val values = extractValues(response)
        check(values.size == dimensions) {
            "embedding has ${values.size} dims, expected $dimensions"
        }
        return if (dimensions < VertexEmbeddingService.NATIVE_DIMENSIONS) renormalize(values)
        else values
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractValues(response: String): List<Double> {
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad embedContent response")
        val embedding = map["embedding"] as? Map<String, Any?> ?: error("no embedding in response")
        val values = embedding["values"] as? List<Number> ?: error("no embedding values")
        return values.map { it.toDouble() }
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

    // Selection reads the BOOTSTRAP props: transport/dry-run posture is bean-wired at startup
    // (read-only in the admin console); the constructed services read live knobs via config.
    @Bean
    fun embeddingService(props: AppProperties, config: StageConfigService): EmbeddingService =
        when {
            props.stage3.embeddingsDryRun -> {
                log.info(
                    "Stage 3 dry-run: pseudo embeddings ({} dims)",
                    props.stage3.embeddingDimensions
                )
                PseudoEmbeddingService(props.stage3.embeddingDimensions)
            }
            props.stage3.embeddingTransport.equals("gemini-api", ignoreCase = true) -> {
                // Fail at boot, not three phases into a run: the transport is useless keyless.
                check(props.stage3.geminiApiKey.isNotBlank()) {
                    "embedding-transport=gemini-api needs GEMINI_API_KEY set"
                }
                log.info(
                    "Stage 3 embeddings: {} via the Gemini Developer API (quota workaround)",
                    props.stage3.embeddingModel,
                )
                GeminiApiEmbeddingService(config)
            }
            else -> VertexEmbeddingService(config)
        }
}

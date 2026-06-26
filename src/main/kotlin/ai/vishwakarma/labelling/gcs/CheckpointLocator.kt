package ai.vishwakarma.labelling.gcs

import ai.vishwakarma.labelling.config.AppProperties
import com.google.cloud.storage.Blob
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Locates a tuned model's weights directory in the serving bucket by *content* (which files a
 * folder actually contains) rather than assuming a model-specific path layout. A finished Vertex
 * tune writes its merged weights somewhere under the job's `outputUri`; the exact subpath differs
 * by model/mode (Gemma LoRA → `…/postprocess/node-0/checkpoints/final/`, Qwen → a
 * `final_output`-style dir, …), so we scan for the directory that holds weight files and prefer one
 * whose name contains `final`.
 *
 * Used both to **suggest** a checkpoint path on a successful poll and to **validate** a path the
 * user confirms in the UI.
 */
@Component
class CheckpointLocator(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Per-directory facts the pure [select] rule needs. */
    data class DirInfo(val hasWeights: Boolean, val latestUpdateMillis: Long)

    /**
     * File extensions that indicate model weights (sharded safetensors, LoRA adapters, TF
     * SavedModel).
     */
    private val weightExts = setOf(".safetensors", ".pt", ".bin", ".pb", ".ckpt", ".h5", ".gguf")

    /**
     * Best-guess weights directory under [outputUri], as `gs://…/<dir>/` (trailing slash), or null
     * when nothing is found / no real bucket is configured.
     */
    fun suggest(outputUri: String): String? {
        val (bucket, prefix) = parseGs(outputUri) ?: return null
        val storage = storage() ?: return null
        val dirs = mutableMapOf<String, DirInfo>()
        for (blob in storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll()) {
            val name = blob.name
            val slash = name.lastIndexOf('/')
            if (slash < 0) continue
            val dir = name.substring(0, slash + 1) // keep trailing slash
            val file = name.substring(slash + 1)
            if (file.isBlank()) continue // directory placeholder object
            val hasWeight = weightExts.any { file.endsWith(it, ignoreCase = true) }
            val prev = dirs[dir]
            dirs[dir] =
                DirInfo(
                    hasWeights = (prev?.hasWeights ?: false) || hasWeight,
                    latestUpdateMillis = maxOf(prev?.latestUpdateMillis ?: 0L, blob.updateMillis()),
                )
        }
        val chosen = select(dirs) ?: return null
        return "gs://$bucket/$chosen"
    }

    /**
     * True iff [folderUri] directly contains a weight file. No real bucket → true (can't validate).
     */
    fun containsWeights(folderUri: String): Boolean {
        val (bucket, rawPrefix) = parseGs(folderUri) ?: return true
        val storage = storage() ?: return true
        val prefix =
            if (rawPrefix.isEmpty() || rawPrefix.endsWith("/")) rawPrefix else "$rawPrefix/"
        return storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll().any { blob
            ->
            val rest = blob.name.removePrefix(prefix)
            rest.isNotBlank() && !rest.contains('/') && weightExts.any { rest.endsWith(it, true) }
        }
    }

    /**
     * Pure selection over discovered directories — testable without GCS:
     * 1. keep dirs that have weights;
     * 2. among those whose leaf segment contains `final` (case-insensitive), prefer a path with
     *    `/node-0/`, then the shallowest, then the most recently written;
     * 3. otherwise the most recently written weights dir;
     * 4. otherwise null.
     */
    fun select(dirs: Map<String, DirInfo>): String? {
        val candidates = dirs.filterValues { it.hasWeights }.keys
        if (candidates.isEmpty()) return null
        val finals = candidates.filter { leaf(it).contains("final", ignoreCase = true) }
        if (finals.isNotEmpty()) {
            return finals.minWithOrNull(
                compareByDescending<String> { it.contains("/node-0/") }
                    .thenBy { it.trimEnd('/').count { c -> c == '/' } }
                    .thenByDescending { dirs[it]?.latestUpdateMillis ?: 0L }
            )
        }
        return candidates.maxByOrNull { dirs[it]?.latestUpdateMillis ?: 0L }
    }

    /** The last path segment of a `a/b/c/` prefix → `c`. */
    private fun leaf(dir: String): String = dir.trimEnd('/').substringAfterLast('/')

    private fun storage(): Storage? {
        val bucket = props.gcp.servingBucket
        if (bucket.isBlank()) return null
        return runCatching {
                StorageOptions.newBuilder().setProjectId(props.gcp.projectId).build().service
            }
            .onFailure { log.warn("Storage client unavailable: {}", it.message) }
            .getOrNull()
    }

    /** `gs://bucket/some/prefix` → (bucket, "some/prefix"); null for non-gs/blank URIs. */
    private fun parseGs(uri: String): Pair<String, String>? {
        if (!uri.startsWith("gs://")) return null
        val rest = uri.removePrefix("gs://")
        val slash = rest.indexOf('/')
        return if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
    }

    private fun Blob.updateMillis(): Long =
        updateTimeOffsetDateTime?.toInstant()?.toEpochMilli() ?: 0L
}

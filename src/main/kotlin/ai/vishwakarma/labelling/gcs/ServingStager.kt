package ai.vishwakarma.labelling.gcs

import ai.vishwakarma.labelling.config.AppProperties
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Stages a tuned checkpoint into the serving-region bucket at registration (LLD §7.1 v1.3):
 * `gs://{staging}/advocates/{subjectId}/model/…`. The copy is a one-time server-side rewrite
 * (multi-GB safetensors shards; `Storage.copy` drives the rewrite-token loop), after which every
 * window's cold deploy fetches weights in-region instead of paying cross-region egress per start.
 * No staging bucket configured (dev) = no copy — the source URI is returned unchanged.
 */
@Component
class ServingStager(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Copy every object under [checkpointUri] to this subject's staging prefix (replacing any
     * previous registration) and return the staged `gs://…/` URI. Throws on GCS failures — the
     * caller surfaces them to the operator (registration is an operator act).
     */
    fun stage(subjectId: String, checkpointUri: String): String {
        val staging = props.serving.stagingBucket
        if (staging.isBlank()) return checkpointUri
        val (srcBucket, rawPrefix) = parseGs(checkpointUri) ?: error("Not a gs:// URI")
        val srcPrefix =
            if (rawPrefix.isEmpty() || rawPrefix.endsWith("/")) rawPrefix else "$rawPrefix/"
        val targetPrefix = "advocates/$subjectId/model/"
        val storage = storage()

        // A re-registration replaces the staged model wholesale — no stale shards left behind.
        storage.list(staging, Storage.BlobListOption.prefix(targetPrefix)).iterateAll().forEach {
            storage.delete(it.blobId)
        }

        var copied = 0
        for (blob in
            storage.list(srcBucket, Storage.BlobListOption.prefix(srcPrefix)).iterateAll()) {
            val rest = blob.name.removePrefix(srcPrefix)
            if (rest.isBlank()) continue // directory placeholder object
            storage
                .copy(
                    Storage.CopyRequest.newBuilder()
                        .setSource(blob.blobId)
                        .setTarget(BlobId.of(staging, targetPrefix + rest))
                        .build()
                )
                .result
            copied++
        }
        check(copied > 0) { "No objects found under $checkpointUri" }
        log.info(
            "Staged {} checkpoint objects for {} into gs://{}/{}",
            copied,
            subjectId,
            staging,
            targetPrefix
        )
        return "gs://$staging/$targetPrefix"
    }

    private fun storage(): Storage =
        StorageOptions.newBuilder().setProjectId(props.gcp.projectId).build().service

    /** `gs://bucket/some/prefix` → (bucket, "some/prefix"); null for non-gs URIs. */
    private fun parseGs(uri: String): Pair<String, String>? {
        if (!uri.startsWith("gs://")) return null
        val rest = uri.removePrefix("gs://")
        val slash = rest.indexOf('/')
        return if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
    }
}

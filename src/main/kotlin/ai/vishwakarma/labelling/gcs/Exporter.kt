package ai.vishwakarma.labelling.gcs

import ai.vishwakarma.labelling.config.AppProperties
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Writes export blobs. When the training bucket is configured, writes to GCS (`gs://…`); otherwise
 * (local dev / no bucket) falls back to a local file under `var/exports/` and returns a `file://`
 * URI so the export flow is fully exercisable without GCP.
 */
@Component
class Exporter(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun write(objectPath: String, content: String): String {
        val bucket = props.gcp.trainingBucket
        if (bucket.isBlank()) {
            val target = Path.of("var", "exports").resolve(objectPath)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
            log.info("Export written locally (no training bucket configured): {}", target.toUri())
            return target.toUri().toString()
        }
        val storage = StorageOptions.newBuilder().setProjectId(props.gcp.projectId).build().service
        val blobInfo =
            BlobInfo.newBuilder(BlobId.of(bucket, objectPath))
                .setContentType("application/x-ndjson")
                .build()
        storage.create(blobInfo, content.toByteArray())
        val uri = "gs://$bucket/$objectPath"
        log.info("Export written to {}", uri)
        return uri
    }

    /**
     * Reads back a previously [write]-ten object as a UTF-8 string. Mirrors the bucket-vs-local
     * branching so the stored file is the single source of truth (used by async import validation
     * and re-validation). [objectPath] must match what was passed to [write].
     */
    fun read(objectPath: String): String {
        val bucket = props.gcp.trainingBucket
        if (bucket.isBlank()) {
            val target = Path.of("var", "exports").resolve(objectPath)
            return Files.readString(target)
        }
        val storage = StorageOptions.newBuilder().setProjectId(props.gcp.projectId).build().service
        val bytes =
            storage.get(BlobId.of(bucket, objectPath))?.getContent()
                ?: error("object not found: gs://$bucket/$objectPath")
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Deletes a blob previously produced by [write], addressed by the URI we stored on the record
     * (`gs://…` in real envs, `file://…` locally). Best-effort: a missing object is not an error.
     */
    fun deleteUri(uri: String) {
        when {
            uri.startsWith("gs://") -> {
                val rest = uri.removePrefix("gs://")
                val slash = rest.indexOf('/')
                if (slash < 0) return
                val storage =
                    StorageOptions.newBuilder().setProjectId(props.gcp.projectId).build().service
                storage.delete(BlobId.of(rest.substring(0, slash), rest.substring(slash + 1)))
                log.info("Deleted {}", uri)
            }
            uri.startsWith("file:") -> {
                Files.deleteIfExists(Path.of(URI.create(uri)))
                log.info("Deleted local {}", uri)
            }
            else -> log.warn("Unrecognized URI, not deleting: {}", uri)
        }
    }
}

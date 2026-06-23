package ai.vishwakarma.labelling.gcs

import ai.vishwakarma.labelling.config.AppProperties
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

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
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectPath))
            .setContentType("application/x-ndjson")
            .build()
        storage.create(blobInfo, content.toByteArray())
        val uri = "gs://$bucket/$objectPath"
        log.info("Export written to {}", uri)
        return uri
    }
}

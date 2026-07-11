package ai.vishwakarma.labelling.gcs

import ai.vishwakarma.labelling.config.AppProperties
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.HttpMethod
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A descriptor the browser uses to upload an intake asset's bytes directly to storage. The client
 * issues a single `PUT` of the raw bytes to [url] with the given [headers]. For real GCS this is a
 * V4 signed URL straight to the bucket (never through the app, so multi-GB A/V bypasses Cloud Run's
 * request-size cap). For local dev ([local] == true) it points at the app's own dev upload
 * endpoint.
 */
data class SignedUpload(
    val url: String,
    val objectPath: String,
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap(),
    val local: Boolean = false,
)

/**
 * Storage for Stage 1 intake assets. Kept separate from [Exporter] (which is JSONL/training
 * specific): the intake bucket holds large binary media with its own lifecycle.
 *
 * When [AppProperties.Gcp.intakeBucket] is configured, uploads use **V4 signed PUT URLs** direct to
 * the bucket. When it is blank (local dev), [signedUploadUrl] returns a URL to the app's dev upload
 * endpoint and bytes land under `var/intake/`, so the whole register → upload → complete flow is
 * exercisable without GCP.
 */
@Component
class IntakeStorage(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val bucket: String
        get() = props.gcp.intakeBucket

    private fun storage(): Storage =
        StorageOptions.newBuilder().setProjectId(props.gcp.projectId).build().service

    private fun localPath(objectPath: String): Path = Path.of("var", "intake").resolve(objectPath)

    /** The durable URI we store on the asset record (`gs://…` in real envs, `file://…` locally). */
    fun uriFor(objectPath: String): String =
        if (bucket.isBlank()) localPath(objectPath).toUri().toString()
        else "gs://$bucket/$objectPath"

    /**
     * Mint an upload descriptor for [objectPath]. The signed URL is bound to [contentType], so the
     * client must send the same `Content-Type` header on its PUT.
     *
     * NOTE (infra): minting a V4 signed URL requires signing credentials. On Cloud Run the runtime
     * service account must be able to sign (a key, or `iam.serviceAccounts.signBlob`); see the
     * Stage 1 infra dependencies.
     */
    fun signedUploadUrl(objectPath: String, contentType: String): SignedUpload {
        if (bucket.isBlank()) {
            val encoded = URLEncoder.encode(objectPath, StandardCharsets.UTF_8)
            return SignedUpload(
                url = "/api/intake/dev/upload?path=$encoded",
                objectPath = objectPath,
                headers = mapOf("Content-Type" to contentType),
                local = true,
            )
        }
        val blobInfo =
            BlobInfo.newBuilder(BlobId.of(bucket, objectPath)).setContentType(contentType).build()
        val url =
            storage()
                .signUrl(
                    blobInfo,
                    15,
                    TimeUnit.MINUTES,
                    Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                    Storage.SignUrlOption.withContentType(),
                    Storage.SignUrlOption.withV4Signature(),
                )
                .toString()
        log.info("Issued signed upload URL for gs://{}/{}", bucket, objectPath)
        return SignedUpload(
            url = url,
            objectPath = objectPath,
            headers = mapOf("Content-Type" to contentType),
            local = false,
        )
    }

    /**
     * Mint a short-lived V4 signed GET URL so the UI can preview/download a stored asset. Locally
     * (no bucket) returns the `file://` URI of the stored bytes.
     */
    fun signedDownloadUrl(objectPath: String): String {
        // Local dev: serve the bytes over http via the dev sink. A file:// URI can't be a redirect
        // target (browsers block http→file://). objectPath is URL-safe ([A-Za-z0-9._/-]) so it
        // needs
        // no encoding — and must stay unencoded here: this URL is followed via a Spring redirect
        // (IntakeController.preview), which would double-encode a %2F into %252F.
        if (bucket.isBlank()) return "/api/intake/dev/download?path=$objectPath"
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectPath)).build()
        return storage()
            .signUrl(
                blobInfo,
                15,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature(),
            )
            .toString()
    }

    /** Size in bytes of a stored object, or null if it isn't present yet. */
    fun objectSize(objectPath: String): Long? {
        if (bucket.isBlank()) {
            val target = localPath(objectPath)
            return if (Files.exists(target)) Files.size(target) else null
        }
        return storage().get(BlobId.of(bucket, objectPath))?.size
    }

    /**
     * Content hash (base64-encoded MD5) of a stored object, or null if it isn't present yet. Used
     * to catch the same bytes uploaded under two content types (§12.7 cross-asset dedup). Prod
     * returns the MD5 GCS already computed for the object; local dev hashes the file. Checksums are
     * only ever compared within one environment, so the shared base64-MD5 shape is all that
     * matters.
     */
    fun objectChecksum(objectPath: String): String? {
        if (bucket.isBlank()) {
            val target = localPath(objectPath)
            if (!Files.exists(target)) return null
            val digest = MessageDigest.getInstance("MD5")
            Files.newInputStream(target).use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    digest.update(buf, 0, read)
                }
            }
            return Base64.getEncoder().encodeToString(digest.digest())
        }
        return storage().get(BlobId.of(bucket, objectPath))?.md5
    }

    /** Best-effort delete of a stored object (missing object is not an error). */
    fun deleteObject(objectPath: String) {
        if (bucket.isBlank()) {
            Files.deleteIfExists(localPath(objectPath))
            return
        }
        storage().delete(BlobId.of(bucket, objectPath))
        log.info("Deleted gs://{}/{}", bucket, objectPath)
    }

    /**
     * Server-side write of a small generated artifact (the Stage 3.5 profile PDF) — overwrites in
     * place, which is what keeps "one current report per object path" true by construction. Blank
     * bucket (local dev) falls back to `var/intake/`.
     */
    fun writeBytes(objectPath: String, bytes: ByteArray, contentType: String) {
        if (bucket.isBlank()) {
            writeLocalBytes(objectPath, bytes)
            return
        }
        val blobInfo =
            BlobInfo.newBuilder(BlobId.of(bucket, objectPath)).setContentType(contentType).build()
        storage().create(blobInfo, bytes)
        log.info("Stored gs://{}/{} ({} bytes)", bucket, objectPath, bytes.size)
    }

    /** Local-dev only: persist bytes uploaded to the dev endpoint under `var/intake/`. */
    fun writeLocalBytes(objectPath: String, bytes: ByteArray) {
        val target = localPath(objectPath)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        log.info("Stored intake asset locally: {}", target.toUri())
    }

    /**
     * Local-dev only: the on-disk file for a stored object, or null if absent. Guards against path
     * traversal — a `path` resolving outside `var/intake/` returns null rather than being read.
     */
    fun localObjectFile(objectPath: String): Path? {
        val root = Path.of("var", "intake").toAbsolutePath().normalize()
        val target = localPath(objectPath).toAbsolutePath().normalize()
        if (!target.startsWith(root)) return null
        return if (Files.exists(target)) target else null
    }
}

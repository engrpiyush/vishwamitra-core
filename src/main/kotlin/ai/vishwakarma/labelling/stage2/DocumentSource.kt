package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.StorageOptions
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Bytes + declared mimeType handed to the multimodal extraction call. */
class DocumentPayload(val bytes: ByteArray, val mimeType: String)

/**
 * Stored bytes for the IMAGE/DOCUMENT lane: gates what Gemini can take inline
 * ([SUPPORTED_MIME_TYPES], [AppProperties.Stage2.maxDocumentBytes]) and reads the asset's bytes
 * from wherever intake put them (`gs://` in real envs, `file://` locally). Dry-run substitutes a
 * bundled sample certificate PDF — the STT dry-run idiom: canned input, real Gemini extraction.
 * Document AI, if extraction quality ever demands it, swaps in behind this seam.
 */
@Component
class DocumentSource(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Null when the asset can go to Gemini document extraction; else the verbatim job error.
     * Checked at job creation (the job is born FAILED — immediate visibility) and again before
     * extraction (covers Retry).
     */
    fun supportError(asset: Asset): String? {
        val mime = normalize(asset.mimeType)
        if (mime.isBlank() || mime !in SUPPORTED_MIME_TYPES)
            return "Unsupported document type for Gemini extraction: " +
                "${mime.ifBlank { "unknown" }} (supported: pdf, png, jpeg, webp, heic, heif)"
        val size = asset.sizeBytes
        if (size != null && size > props.stage2.maxDocumentBytes)
            return "Document is $size bytes — over the ${props.stage2.maxDocumentBytes}-byte " +
                "inline limit for Gemini extraction"
        return null
    }

    /**
     * The stored bytes for extraction. Throws on a missing object or a payload over the inline cap
     * (re-checked against actual size — [Asset.sizeBytes] can be null); the caller lands the
     * message on the job.
     */
    fun read(asset: Asset): DocumentPayload {
        if (props.stage2.dryRun) {
            log.info(
                "Stage 2 dry-run: substituting the bundled certificate PDF for asset {}",
                asset.id,
            )
            val bytes =
                javaClass.getResourceAsStream(DRY_RUN_RESOURCE)?.use { it.readBytes() }
                    ?: error("bundled dry-run document missing: $DRY_RUN_RESOURCE")
            return DocumentPayload(bytes, "application/pdf")
        }
        val uri = asset.gcsUri
        require(!uri.isNullOrBlank()) { "asset ${asset.id} has no stored bytes uri" }
        val bytes =
            when {
                uri.startsWith("gs://") -> readGcsObject(uri)
                uri.startsWith("file://") -> Files.readAllBytes(Path.of(URI(uri)))
                else -> error("unsupported stored-bytes uri scheme: $uri")
            }
        check(bytes.size <= props.stage2.maxDocumentBytes) {
            "Document is ${bytes.size} bytes — over the ${props.stage2.maxDocumentBytes}-byte " +
                "inline limit for Gemini extraction"
        }
        return DocumentPayload(bytes, normalize(asset.mimeType))
    }

    private fun readGcsObject(uri: String): ByteArray {
        val path = uri.removePrefix("gs://")
        val slash = path.indexOf('/')
        require(slash > 0) { "not a gs:// object uri: $uri" }
        val storage = StorageOptions.newBuilder().setProjectId(props.gcp.projectId).build().service
        return storage
            .get(BlobId.of(path.substring(0, slash), path.substring(slash + 1)))
            ?.getContent() ?: error("stored document not found: $uri")
    }

    /** `image/png; charset=…` → `image/png`. */
    private fun normalize(mimeType: String?): String =
        mimeType.orEmpty().substringBefore(';').trim().lowercase()

    companion object {
        /** What Gemini takes inline: PDFs + common raster images (no docx/pptx/rtf/odt/tiff). */
        val SUPPORTED_MIME_TYPES =
            setOf(
                "application/pdf",
                "image/png",
                "image/jpeg",
                "image/webp",
                "image/heic",
                "image/heif",
            )

        const val DRY_RUN_RESOURCE = "/stage2/dry-run-certificate.pdf"
    }
}

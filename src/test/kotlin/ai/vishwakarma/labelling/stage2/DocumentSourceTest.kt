package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.liveConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DocumentSource]'s gating (mime + size) and byte reading (dry-run, `file://`). */
class DocumentSourceTest {

    private fun props(maxBytes: Long = 14L * 1024 * 1024, dryRun: Boolean = false) =
        AppProperties(stage2 = AppProperties.Stage2(maxDocumentBytes = maxBytes, dryRun = dryRun))

    private fun asset(
        mimeType: String? = "image/png",
        sizeBytes: Long? = 1_234,
        gcsUri: String? = "gs://intake/s1/d1.png",
    ) =
        Asset(
            id = "d1",
            subjectId = "s1",
            title = "Certificate",
            modality = AssetModality.IMAGE,
            sourceClass = ContentType.CERTIFICATE.sourceClass,
            contentType = ContentType.CERTIFICATE,
            relationship = Relationship.SELF,
            authenticityPrior = AuthenticityTier.HIGH,
            gcsUri = gcsUri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
        )

    @Test
    fun `pdf and image mime types under the cap are supported`() {
        val source = DocumentSource(liveConfig(props()))

        assertNull(source.supportError(asset(mimeType = "application/pdf")))
        assertNull(source.supportError(asset(mimeType = "image/png")))
        assertNull(source.supportError(asset(mimeType = "image/jpeg")))
    }

    @Test
    fun `mime parameters and case are normalized away`() {
        val source = DocumentSource(liveConfig(props()))

        assertNull(source.supportError(asset(mimeType = "IMAGE/PNG; charset=binary")))
    }

    @Test
    fun `office formats and unknown mime types are refused verbatim`() {
        val source = DocumentSource(liveConfig(props()))

        val docx =
            source.supportError(
                asset(
                    mimeType =
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        assertTrue(docx!!.contains("Unsupported document type"))
        assertTrue(docx.contains("wordprocessingml"))
        assertTrue(source.supportError(asset(mimeType = "image/tiff"))!!.contains("image/tiff"))
        assertTrue(source.supportError(asset(mimeType = null))!!.contains("unknown"))
    }

    @Test
    fun `declared sizes over the cap are refused but unknown sizes defer to read`() {
        val source = DocumentSource(liveConfig(props(maxBytes = 100)))

        val error = source.supportError(asset(sizeBytes = 101))
        assertTrue(error!!.contains("101"))
        assertTrue(error.contains("100"))
        assertNull(source.supportError(asset(sizeBytes = null)))
    }

    @Test
    fun `dry-run substitutes the bundled certificate pdf`() {
        val payload = DocumentSource(liveConfig(props(dryRun = true))).read(asset(gcsUri = null))

        assertEquals("application/pdf", payload.mimeType)
        assertEquals("%PDF", payload.bytes.decodeToString(0, 4))
    }

    @Test
    fun `reads file uris and enforces the cap on actual bytes`() {
        val file = Files.createTempFile("doc-source", ".png")
        Files.write(file, ByteArray(64))
        val uri = file.toUri().toString()

        val payload = DocumentSource(liveConfig(props())).read(asset(gcsUri = uri))
        assertEquals(64, payload.bytes.size)
        assertEquals("image/png", payload.mimeType)

        // sizeBytes was unknown at gate time; the post-read check still enforces the cap.
        assertFailsWith<IllegalStateException> {
            DocumentSource(liveConfig(props(maxBytes = 10)))
                .read(asset(gcsUri = uri, sizeBytes = null))
        }
    }
}

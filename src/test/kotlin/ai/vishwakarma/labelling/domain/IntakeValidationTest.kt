package ai.vishwakarma.labelling.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntakeValidationTest {

    // ---- isAllowedFilename / allowedExtensions -----------------------------

    @Test
    fun `each modality accepts its documented extensions`() {
        assertTrue(AssetModality.AUDIO.isAllowedFilename("interview.mp3"))
        assertTrue(AssetModality.VIDEO.isAllowedFilename("talk.mp4"))
        assertTrue(AssetModality.IMAGE.isAllowedFilename("cert.png"))
        assertTrue(AssetModality.DOCUMENT.isAllowedFilename("resume.pdf"))
        assertTrue(AssetModality.TEXT.isAllowedFilename("story.md"))
        assertTrue(AssetModality.ARCHIVE.isAllowedFilename("export.zip"))
    }

    @Test
    fun `extension check is case-insensitive`() {
        assertTrue(AssetModality.AUDIO.isAllowedFilename("interview.MP3"))
        assertTrue(AssetModality.IMAGE.isAllowedFilename("photo.JPG"))
    }

    @Test
    fun `mismatched or missing extensions are rejected for binary text modalities`() {
        assertFalse(AssetModality.AUDIO.isAllowedFilename("video.mp4"))
        assertFalse(AssetModality.IMAGE.isAllowedFilename("cert.exe"))
        assertFalse(AssetModality.DOCUMENT.isAllowedFilename(null))
        assertFalse(AssetModality.TEXT.isAllowedFilename("story"))
    }

    @Test
    fun `LINK modality accepts any filename including none`() {
        assertTrue(AssetModality.LINK.isAllowedFilename(null))
        assertTrue(AssetModality.LINK.isAllowedFilename("whatever.exe"))
    }

    // ---- isValidExternalUrl ------------------------------------------------

    @Test
    fun `valid http and https URLs with a host pass`() {
        assertTrue(isValidExternalUrl("https://example.com/path"))
        assertTrue(isValidExternalUrl("http://example.com"))
        assertTrue(isValidExternalUrl("  https://example.com  "))
    }

    @Test
    fun `URLs without a host or with the wrong scheme are rejected`() {
        assertFalse(isValidExternalUrl("https://"))
        assertFalse(isValidExternalUrl("javascript:alert(1)"))
        assertFalse(isValidExternalUrl("ftp://example.com"))
        assertFalse(isValidExternalUrl("not a url"))
        assertFalse(isValidExternalUrl(""))
    }
}

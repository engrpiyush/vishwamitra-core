package ai.vishwakarma.labelling.domain

import java.net.URI

/**
 * Stage 1 asset validation helpers. Pure functions over the taxonomy so they're testable without
 * standing up any service/repository.
 */

/**
 * Case-insensitive allowed file extensions per modality, mirroring the KDoc lists on
 * [AssetModality].
 */
val AssetModality.allowedExtensions: Set<String>
    get() =
        when (this) {
            AssetModality.AUDIO -> setOf("mp3", "wav", "m4a", "aac", "flac", "ogg")
            AssetModality.VIDEO -> setOf("mp4", "mov", "webm", "mkv")
            AssetModality.IMAGE -> setOf("jpg", "jpeg", "png", "heic", "webp", "tiff", "tif")
            AssetModality.DOCUMENT -> setOf("pdf", "docx", "doc", "rtf", "odt", "pptx")
            AssetModality.TEXT -> setOf("md", "txt")
            AssetModality.ARCHIVE -> setOf("zip")
            AssetModality.LINK -> emptySet()
        }

/**
 * True if [filename]'s extension (case-insensitive) is allowed for this modality. LINK has no bytes
 * so any filename (including none) is accepted; every other modality requires a recognized
 * extension.
 */
fun AssetModality.isAllowedFilename(filename: String?): Boolean {
    if (this == AssetModality.LINK) return true
    val ext = filename?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
    if (ext.isNullOrBlank()) return false
    return ext in allowedExtensions
}

/** True if [raw] parses as an http(s) URL with a non-blank host. */
fun isValidExternalUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}

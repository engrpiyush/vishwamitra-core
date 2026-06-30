package ai.vishwakarma.labelling.domain

import java.time.Instant
import java.time.LocalDate

/**
 * Where an asset is in its storage lifecycle.
 * - [AWAITING_UPLOAD] — registered; a signed upload URL was issued, bytes not yet confirmed.
 * - [STORED] — bytes confirmed present in the bucket.
 * - [REGISTERED] — a LINK asset (external URL); no bytes to store.
 * - [FAILED] — upload was registered but never completed / object missing.
 */
enum class AssetUploadStatus {
    AWAITING_UPLOAD,
    STORED,
    REGISTERED,
    FAILED;

    companion object {
        fun fromOrNull(raw: String?): AssetUploadStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * One piece of raw material about a [Subject] — a manifest row. Carries the full Stage 1 taxonomy
 * plus provenance, consent, and storage pointers. Either it holds bytes
 * ([storedObjectPath]/[gcsUri] for binary/text modalities) or it points at an [externalUrl] (LINK
 * modality).
 *
 * [authenticityPrior] is the value Stage 3 consumes. It is [defaultPrior]-derived from
 * [contentType] + [relationship] unless [authenticityPriorOverridden] is true (a human set it).
 */
data class Asset(
    val id: String,
    val subjectId: String,
    val title: String,
    // --- classification ---
    val modality: AssetModality,
    val sourceClass: SourceClass,
    val contentType: ContentType,
    val relationship: Relationship = Relationship.UNKNOWN,
    val authenticityPrior: AuthenticityTier = AuthenticityTier.LOW,
    val authenticityPriorOverridden: Boolean = false,
    // --- storage (binary/text) OR link ---
    val storedObjectPath: String? = null,
    val gcsUri: String? = null,
    val externalUrl: String? = null,
    val originalFilename: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val checksum: String? = null,
    // --- provenance ---
    /** Who/what produced it: endorser name, issuing body, publication. */
    val sourceName: String? = null,
    /** When the asset itself was recorded/created. */
    val captureDate: LocalDate? = null,
    /** When the event the asset documents actually happened. */
    val claimedEventDate: LocalDate? = null,
    // --- consent ---
    val consentStatus: ConsentStatus = ConsentStatus.PENDING,
    val consentDate: Instant? = null,
    val consentNote: String? = null,
    // --- lifecycle / metadata ---
    val uploadStatus: AssetUploadStatus = AssetUploadStatus.AWAITING_UPLOAD,
    val labels: List<String> = emptyList(),
    val notes: String = "",
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

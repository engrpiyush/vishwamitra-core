package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.defaultPrior
import ai.vishwakarma.labelling.gcs.IntakeStorage
import ai.vishwakarma.labelling.gcs.SignedUpload
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.stereotype.Service

/**
 * Register-an-asset input (binary/text). [contentType] decides the source class + default prior.
 */
data class AssetRegistration(
    val title: String,
    val modality: AssetModality,
    val contentType: ContentType,
    val relationship: Relationship = Relationship.UNKNOWN,
    val originalFilename: String? = null,
    val mimeType: String? = null,
    val sourceName: String? = null,
    val captureDate: LocalDate? = null,
    val claimedEventDate: LocalDate? = null,
    val consentStatus: ConsentStatus = ConsentStatus.PENDING,
    val consentNote: String? = null,
    val labels: List<String> = emptyList(),
    val notes: String = "",
)

/** Register-a-link input (PUBLIC_PROFILE / external URL; no bytes to store). */
data class LinkRegistration(
    val title: String,
    val contentType: ContentType,
    val externalUrl: String,
    val relationship: Relationship = Relationship.UNKNOWN,
    val sourceName: String? = null,
    val captureDate: LocalDate? = null,
    val claimedEventDate: LocalDate? = null,
    val consentStatus: ConsentStatus = ConsentStatus.NOT_REQUIRED,
    val consentNote: String? = null,
    val labels: List<String> = emptyList(),
    val notes: String = "",
)

/** Partial edit of an asset. A non-null [authenticityPrior] is treated as a manual override. */
data class AssetPatch(
    val title: String? = null,
    val contentType: ContentType? = null,
    val relationship: Relationship? = null,
    val authenticityPrior: AuthenticityTier? = null,
    val sourceName: String? = null,
    val captureDate: LocalDate? = null,
    val claimedEventDate: LocalDate? = null,
    val consentStatus: ConsentStatus? = null,
    val consentNote: String? = null,
    val labels: List<String>? = null,
    val notes: String? = null,
)

/**
 * What [registerAsset] hands back: the persisted record plus the descriptor the browser uploads to.
 */
data class AssetUpload(val asset: Asset, val upload: SignedUpload)

/**
 * Stage 1 asset + manifest operations. Owns the register → upload → complete lifecycle, link
 * registration, edits (with prior re-derivation), deletion (bytes + record), and the per-subject
 * [IntakeManifest] rollup that Stage 2 consumes.
 */
@Service
class IntakeService(
    private val assets: AssetRepository,
    private val subjects: SubjectRepository,
    private val manifests: IntakeManifestRepository,
    private val storage: IntakeStorage,
) {

    fun listAssets(subjectId: String): List<Asset> = assets.findBySubject(subjectId)

    fun getAsset(id: String): Asset? = assets.findById(id)

    /**
     * Create an `AWAITING_UPLOAD` asset and mint a signed upload URL for its bytes. The object path
     * is `intake/{subjectId}/{contentType}/{date}/{assetId}-{file}`.
     */
    fun registerAsset(
        subjectId: String,
        actor: String?,
        reg: AssetRegistration,
    ): Either<DomainError, AssetUpload> {
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        if (reg.title.isBlank()) return DomainError.Invalid("Asset title is required").left()
        if (reg.modality == AssetModality.LINK)
            return DomainError.Invalid("Use the links endpoint for LINK assets").left()

        val now = Instant.now()
        val id = assets.newId()
        val objectPath = objectPath(subjectId, reg.contentType, id, reg.originalFilename)
        val contentType = reg.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val upload = storage.signedUploadUrl(objectPath, contentType)

        val asset =
            Asset(
                id = id,
                subjectId = subjectId,
                title = reg.title.trim(),
                modality = reg.modality,
                sourceClass = reg.contentType.sourceClass,
                contentType = reg.contentType,
                relationship = reg.relationship,
                authenticityPrior = defaultPrior(reg.contentType, reg.relationship),
                authenticityPriorOverridden = false,
                storedObjectPath = objectPath,
                gcsUri = storage.uriFor(objectPath),
                originalFilename = reg.originalFilename,
                mimeType = contentType,
                sourceName = reg.sourceName,
                captureDate = reg.captureDate,
                claimedEventDate = reg.claimedEventDate,
                consentStatus = reg.consentStatus,
                consentDate = if (reg.consentStatus != ConsentStatus.PENDING) now else null,
                consentNote = reg.consentNote,
                uploadStatus = AssetUploadStatus.AWAITING_UPLOAD,
                labels = reg.labels,
                notes = reg.notes,
                createdBy = actor,
                createdAt = now,
                updatedAt = now,
            )
        assets.save(asset)
        recomputeManifest(subjectId)
        return AssetUpload(asset, upload).right()
    }

    /** Confirm the bytes landed: flip to STORED and record the size. */
    fun completeAsset(id: String): Either<DomainError, Asset> {
        val asset = assets.findById(id) ?: return DomainError.NotFound("Asset $id not found").left()
        val path =
            asset.storedObjectPath
                ?: return DomainError.Invalid("Asset $id has no stored object path").left()
        val size =
            storage.objectSize(path)
                ?: return DomainError.Invalid("No uploaded bytes found for asset $id").left()
        val updated =
            asset.copy(
                sizeBytes = size,
                uploadStatus = AssetUploadStatus.STORED,
                updatedAt = Instant.now(),
            )
        assets.save(updated)
        recomputeManifest(asset.subjectId)
        return updated.right()
    }

    /** Register an external link (PUBLIC_PROFILE) — no bytes, immediately `REGISTERED`. */
    fun registerLink(
        subjectId: String,
        actor: String?,
        reg: LinkRegistration,
    ): Either<DomainError, Asset> {
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        if (reg.title.isBlank()) return DomainError.Invalid("Link title is required").left()
        if (!reg.externalUrl.startsWith("http://") && !reg.externalUrl.startsWith("https://"))
            return DomainError.Invalid("externalUrl must be an http(s) URL").left()

        val now = Instant.now()
        val asset =
            Asset(
                id = assets.newId(),
                subjectId = subjectId,
                title = reg.title.trim(),
                modality = AssetModality.LINK,
                sourceClass = reg.contentType.sourceClass,
                contentType = reg.contentType,
                relationship = reg.relationship,
                authenticityPrior = defaultPrior(reg.contentType, reg.relationship),
                authenticityPriorOverridden = false,
                externalUrl = reg.externalUrl.trim(),
                sourceName = reg.sourceName,
                captureDate = reg.captureDate,
                claimedEventDate = reg.claimedEventDate,
                consentStatus = reg.consentStatus,
                consentDate = if (reg.consentStatus != ConsentStatus.PENDING) now else null,
                consentNote = reg.consentNote,
                uploadStatus = AssetUploadStatus.REGISTERED,
                labels = reg.labels,
                notes = reg.notes,
                createdBy = actor,
                createdAt = now,
                updatedAt = now,
            )
        assets.save(asset)
        recomputeManifest(subjectId)
        return asset.right()
    }

    fun updateAsset(id: String, patch: AssetPatch): Either<DomainError, Asset> {
        val asset = assets.findById(id) ?: return DomainError.NotFound("Asset $id not found").left()
        val contentType = patch.contentType ?: asset.contentType
        val relationship = patch.relationship ?: asset.relationship
        // Prior: an explicit value overrides; otherwise re-derive only while not already
        // overridden.
        val overridden = asset.authenticityPriorOverridden || patch.authenticityPrior != null
        val prior =
            patch.authenticityPrior
                ?: if (asset.authenticityPriorOverridden) asset.authenticityPrior
                else defaultPrior(contentType, relationship)
        val consentStatus = patch.consentStatus ?: asset.consentStatus
        val updated =
            asset.copy(
                title = patch.title?.trim()?.takeIf { it.isNotBlank() } ?: asset.title,
                contentType = contentType,
                sourceClass = contentType.sourceClass,
                relationship = relationship,
                authenticityPrior = prior,
                authenticityPriorOverridden = overridden,
                sourceName = patch.sourceName ?: asset.sourceName,
                captureDate = patch.captureDate ?: asset.captureDate,
                claimedEventDate = patch.claimedEventDate ?: asset.claimedEventDate,
                consentStatus = consentStatus,
                consentDate =
                    if (patch.consentStatus != null && patch.consentStatus != ConsentStatus.PENDING)
                        Instant.now()
                    else asset.consentDate,
                consentNote = patch.consentNote ?: asset.consentNote,
                labels = patch.labels ?: asset.labels,
                notes = patch.notes ?: asset.notes,
                updatedAt = Instant.now(),
            )
        assets.save(updated)
        recomputeManifest(asset.subjectId)
        return updated.right()
    }

    fun deleteAsset(id: String): Either<DomainError, Unit> {
        val asset = assets.findById(id) ?: return DomainError.NotFound("Asset $id not found").left()
        asset.storedObjectPath?.let { storage.deleteObject(it) }
        assets.delete(id)
        recomputeManifest(asset.subjectId)
        return Unit.right()
    }

    /**
     * Remove every asset (bytes + record) and the manifest for a subject. Used on subject delete.
     */
    fun purgeSubject(subjectId: String) {
        assets.findBySubject(subjectId).forEach { a ->
            a.storedObjectPath?.let { storage.deleteObject(it) }
            assets.delete(a.id)
        }
        manifests.delete(subjectId)
    }

    /** Local-dev only: persist bytes uploaded to the dev endpoint (intake-bucket blank). */
    fun storeLocalBytes(objectPath: String, bytes: ByteArray) =
        storage.writeLocalBytes(objectPath, bytes)

    /**
     * A retrievable URL for an asset's content: its external URL for links, otherwise a short-lived
     * signed GET URL for the stored bytes. Null when there is nothing to retrieve yet.
     */
    fun downloadUrl(id: String): Either<DomainError, String> {
        val asset = assets.findById(id) ?: return DomainError.NotFound("Asset $id not found").left()
        asset.externalUrl?.let {
            return it.right()
        }
        val path =
            asset.storedObjectPath?.takeIf { asset.uploadStatus == AssetUploadStatus.STORED }
                ?: return DomainError.Invalid("Asset $id has no stored content yet").left()
        return storage.signedDownloadUrl(path).right()
    }

    /** The live manifest (counts recomputed from the current asset set). */
    fun manifest(subjectId: String): IntakeManifest = recomputeManifest(subjectId)

    fun sealManifest(subjectId: String, actor: String?): Either<DomainError, IntakeManifest> {
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        val now = Instant.now()
        val sealed =
            recomputeManifest(subjectId)
                .copy(sealed = true, sealedBy = actor, sealedAt = now, updatedAt = now)
        manifests.save(sealed)
        return sealed.right()
    }

    // ---- helpers ----------------------------------------------------------
    private fun recomputeManifest(subjectId: String): IntakeManifest {
        val all = assets.findBySubject(subjectId)
        val existing = manifests.findBySubject(subjectId)
        val manifest =
            IntakeManifest(
                id = subjectId,
                subjectId = subjectId,
                assetIds = all.map { it.id },
                countsByModality = all.groupingBy { it.modality.name }.eachCount(),
                countsByContentType = all.groupingBy { it.contentType.name }.eachCount(),
                consentSummary = all.groupingBy { it.consentStatus.name }.eachCount(),
                // Preserve any existing seal; the seal action re-stamps it explicitly.
                sealed = existing?.sealed ?: false,
                sealedBy = existing?.sealedBy,
                sealedAt = existing?.sealedAt,
                updatedAt = Instant.now(),
            )
        manifests.save(manifest)
        return manifest
    }

    private fun objectPath(
        subjectId: String,
        contentType: ContentType,
        assetId: String,
        originalFilename: String?,
    ): String {
        val date = DATE.format(Instant.now())
        val safe = safeFilename(originalFilename)
        return "intake/$subjectId/${contentType.name.lowercase()}/$date/$assetId-$safe"
    }

    private fun safeFilename(name: String?): String {
        val base = name?.trim()?.takeIf { it.isNotBlank() } ?: "asset"
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}

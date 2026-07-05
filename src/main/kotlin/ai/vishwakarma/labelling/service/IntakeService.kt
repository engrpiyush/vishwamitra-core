package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SealAction
import ai.vishwakarma.labelling.domain.SealEvent
import ai.vishwakarma.labelling.domain.defaultPrior
import ai.vishwakarma.labelling.domain.isAllowedFilename
import ai.vishwakarma.labelling.domain.isValidExternalUrl
import ai.vishwakarma.labelling.gcs.IntakeStorage
import ai.vishwakarma.labelling.gcs.SignedUpload
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
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
    /**
     * Client-declared byte size (e.g. `File.size` in the browser). Advisory only — bytes never pass
     * through the app for real GCS uploads, so this is a fast-fail UX check, not enforcement.
     */
    val declaredSizeBytes: Long? = null,
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
    private val props: AppProperties,
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
        sealedGuard(subjectId)?.let {
            return it.left()
        }
        if (reg.title.isBlank()) return DomainError.Invalid("Asset title is required").left()
        if (reg.modality == AssetModality.LINK)
            return DomainError.Invalid("Use the links endpoint for LINK assets").left()
        if (!reg.modality.isAllowedFilename(reg.originalFilename))
            return DomainError.Invalid(
                    "File extension not allowed for modality ${reg.modality}: " +
                        (reg.originalFilename ?: "(no filename)")
                )
                .left()
        if (reg.declaredSizeBytes != null && reg.declaredSizeBytes > props.intake.maxAssetSizeBytes)
            return DomainError.Invalid(
                    "Declared size ${reg.declaredSizeBytes} exceeds the " +
                        "${props.intake.maxAssetSizeBytes}-byte limit"
                )
                .left()

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
        // An object that exists but is empty means the PUT reached storage with no body — accepting
        // it would mark the asset STORED and only surface far downstream as an opaque provider
        // error
        // (Speech-to-Text: "Provided file is empty"). Reject it here so the upload can be retried.
        if (size == 0L)
            return DomainError.Invalid(
                    "Uploaded object for asset $id is empty (0 bytes) — the file body did not reach " +
                        "storage; retry the upload."
                )
                .left()
        if (size > props.intake.maxAssetSizeBytes) {
            storage.deleteObject(path)
            val failed =
                asset.copy(uploadStatus = AssetUploadStatus.FAILED, updatedAt = Instant.now())
            assets.save(failed)
            recomputeManifest(asset.subjectId)
            return DomainError.Invalid(
                    "Asset $id exceeds max size of ${props.intake.maxAssetSizeBytes} bytes " +
                        "($size uploaded); rejected"
                )
                .left()
        }
        val updated =
            asset.copy(
                sizeBytes = size,
                checksum = storage.objectChecksum(path),
                uploadStatus = AssetUploadStatus.STORED,
                updatedAt = Instant.now(),
            )
        assets.save(updated)
        recomputeManifest(asset.subjectId)
        return updated.right()
    }

    /**
     * Re-issue a signed upload URL for an asset stuck in `AWAITING_UPLOAD` or `FAILED` (expired
     * URL, dropped connection, …). Re-arms a `FAILED` asset back to `AWAITING_UPLOAD`. Refused for
     * LINK assets (no bytes) and already-`STORED` assets (delete + re-register to replace stored
     * bytes).
     */
    fun reissueUploadUrl(id: String): Either<DomainError, AssetUpload> {
        val asset = assets.findById(id) ?: return DomainError.NotFound("Asset $id not found").left()
        sealedGuard(asset.subjectId)?.let {
            return it.left()
        }
        if (asset.modality == AssetModality.LINK)
            return DomainError.Invalid("LINK assets have no upload URL").left()
        if (asset.uploadStatus == AssetUploadStatus.STORED)
            return DomainError.Invalid(
                    "Asset $id is already stored; delete and re-register to replace it"
                )
                .left()
        val path =
            asset.storedObjectPath
                ?: return DomainError.Invalid("Asset $id has no stored object path").left()
        val contentType = asset.mimeType ?: "application/octet-stream"
        val upload = storage.signedUploadUrl(path, contentType)
        val current =
            if (asset.uploadStatus == AssetUploadStatus.FAILED) {
                val reArmed =
                    asset.copy(
                        uploadStatus = AssetUploadStatus.AWAITING_UPLOAD,
                        updatedAt = Instant.now()
                    )
                assets.save(reArmed)
                recomputeManifest(asset.subjectId)
                reArmed
            } else asset
        return AssetUpload(current, upload).right()
    }

    /**
     * Re-checks one stuck asset's bytes and completes or fails it. No-op for LINK/STORED assets.
     */
    fun reconcileAsset(id: String): Either<DomainError, Asset> {
        val asset = assets.findById(id) ?: return DomainError.NotFound("Asset $id not found").left()
        return reconcileInternal(asset).right()
    }

    /** Reconciles every stuck asset for a subject; returns the ones whose status changed. */
    fun reconcileSubject(subjectId: String): List<Asset> {
        val stale =
            assets.findBySubject(subjectId).filter {
                it.uploadStatus == AssetUploadStatus.AWAITING_UPLOAD ||
                    it.uploadStatus == AssetUploadStatus.FAILED
            }
        val changed =
            stale.mapNotNull { before ->
                reconcileInternal(before).takeIf { it.uploadStatus != before.uploadStatus }
            }
        if (changed.isNotEmpty()) recomputeManifest(subjectId)
        return changed
    }

    private fun reconcileInternal(asset: Asset): Asset {
        if (asset.modality == AssetModality.LINK) return asset
        if (
            asset.uploadStatus != AssetUploadStatus.AWAITING_UPLOAD &&
                asset.uploadStatus != AssetUploadStatus.FAILED
        )
            return asset
        val path = asset.storedObjectPath ?: return asset
        val size = storage.objectSize(path)
        val now = Instant.now()
        return when {
            size != null && size > props.intake.maxAssetSizeBytes -> {
                storage.deleteObject(path)
                val updated = asset.copy(uploadStatus = AssetUploadStatus.FAILED, updatedAt = now)
                assets.save(updated)
                updated
            }
            // A present-but-empty object is not a completed upload — leave it AWAITING (or let it
            // age out to FAILED below) so a retry re-mints and overwrites it, rather than marking a
            // 0-byte asset STORED and failing later in Stage 2 ("Provided file is empty").
            size != null && size > 0L -> {
                val updated =
                    asset.copy(
                        sizeBytes = size,
                        uploadStatus = AssetUploadStatus.STORED,
                        updatedAt = now
                    )
                assets.save(updated)
                updated
            }
            isStale(asset, now) -> {
                val updated = asset.copy(uploadStatus = AssetUploadStatus.FAILED, updatedAt = now)
                assets.save(updated)
                updated
            }
            else -> asset
        }
    }

    private fun isStale(asset: Asset, now: Instant): Boolean {
        val created = asset.createdAt ?: return false
        return created.isBefore(now.minus(Duration.ofHours(props.intake.staleUploadHours)))
    }

    /** Register an external link (PUBLIC_PROFILE) — no bytes, immediately `REGISTERED`. */
    fun registerLink(
        subjectId: String,
        actor: String?,
        reg: LinkRegistration,
    ): Either<DomainError, Asset> {
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        sealedGuard(subjectId)?.let {
            return it.left()
        }
        if (reg.title.isBlank()) return DomainError.Invalid("Link title is required").left()
        if (!isValidExternalUrl(reg.externalUrl))
            return DomainError.Invalid("externalUrl must be a valid http(s) URL with a host").left()

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
        sealedGuard(asset.subjectId)?.let {
            return it.left()
        }
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
        sealedGuard(asset.subjectId)?.let {
            return it.left()
        }
        asset.storedObjectPath?.let { storage.deleteObject(it) }
        assets.delete(id)
        recomputeManifest(asset.subjectId)
        return Unit.right()
    }

    /**
     * Revoke consent for a single asset and honor it through a sealed/locked manifest (§12.7
     * hardening): delete the stored bytes, mark the asset REVOKED, and recompute the manifest so
     * the (possibly locked) manifest's consent summary reflects the withdrawal. Deliberately
     * bypasses [sealedGuard] and the permanent Stage 2 lock — consent withdrawal is always
     * honorable, like [purgeSubject] — but never unseals: revoke ≠ unlock. The caller purges the
     * asset's derived claims/jobs (Stage2Service.purgeAssetDerived).
     */
    fun revokeAssetConsent(assetId: String, note: String): Either<DomainError, Asset> {
        if (note.isBlank())
            return DomainError.Invalid("A note is required when revoking consent").left()
        val asset =
            assets.findById(assetId)
                ?: return DomainError.NotFound("Asset $assetId not found").left()
        if (asset.consentStatus == ConsentStatus.REVOKED)
            return DomainError.Conflict("Consent for asset $assetId is already revoked").left()
        asset.storedObjectPath?.let { storage.deleteObject(it) }
        val revoked =
            asset.copy(
                consentStatus = ConsentStatus.REVOKED,
                consentDate = Instant.now(),
                consentNote = note.trim(),
                // Bytes are gone; LINK assets never had any, so leave their status alone.
                uploadStatus =
                    if (asset.modality == AssetModality.LINK) asset.uploadStatus
                    else AssetUploadStatus.FAILED,
                updatedAt = Instant.now(),
            )
        assets.save(revoked)
        recomputeManifest(asset.subjectId)
        return revoked.right()
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

    /** Local-dev byte source backing GET /api/intake/dev/download (mirrors [storeLocalBytes]). */
    fun localFile(objectPath: String): java.nio.file.Path? = storage.localObjectFile(objectPath)

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

    /**
     * Seal the manifest for the Stage 2 handoff. Gated by [IntakeManifest.sealBlockers]; requires a
     * mandatory operator [note] (the confirmation justification), audited in
     * [IntakeManifest.sealEvents].
     */
    fun sealManifest(
        subjectId: String,
        actor: String?,
        note: String,
    ): Either<DomainError, IntakeManifest> {
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        if (note.isBlank())
            return DomainError.Invalid("A note is required when sealing a manifest").left()
        val current = recomputeManifest(subjectId)
        if (current.sealed) return DomainError.Conflict("Manifest is already sealed").left()
        if (current.sealBlockers.isNotEmpty())
            return DomainError.Conflict("Cannot seal: ${current.sealBlockers.joinToString("; ")}")
                .left()
        val now = Instant.now()
        val sealed =
            current.copy(
                sealed = true,
                sealEvents =
                    current.sealEvents + SealEvent(SealAction.SEAL, actor, now, note.trim()),
                updatedAt = now,
            )
        manifests.save(sealed)
        return sealed.right()
    }

    /**
     * Reverse a seal — reopens the manifest (and its assets) for editing and a later re-seal.
     * Restricted to ADMIN at the web layer (seal itself is REVIEWER+). Requires a mandatory [note]
     * (the unseal reason), audited in [IntakeManifest.sealEvents]. Refuses (409) if the manifest is
     * not currently sealed, or — permanently — once Stage 2 has started consuming it.
     */
    fun unsealManifest(
        subjectId: String,
        actor: String?,
        note: String,
    ): Either<DomainError, IntakeManifest> {
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        if (note.isBlank())
            return DomainError.Invalid("A note is required when unsealing a manifest").left()
        val current = recomputeManifest(subjectId)
        if (!current.sealed) return DomainError.Conflict("Manifest is not sealed").left()
        if (current.stage2StartedAt != null)
            return DomainError.Conflict(
                    "Manifest is locked: Stage 2 started consuming it at " +
                        "${current.stage2StartedAt} — the seal is permanent"
                )
                .left()
        val now = Instant.now()
        val unsealed =
            current.copy(
                sealed = false,
                sealEvents =
                    current.sealEvents + SealEvent(SealAction.UNSEAL, actor, now, note.trim()),
                updatedAt = now,
            )
        manifests.save(unsealed)
        return unsealed.right()
    }

    /**
     * A sealed manifest freezes its asset inventory: additions, edits, deletions and upload-URL
     * re-mints are refused until an ADMIN unseals. Subject purge (delete-on-request) intentionally
     * bypasses this — consent withdrawal must always be honorable.
     */
    private fun sealedGuard(subjectId: String): DomainError? {
        val manifest = manifests.findBySubject(subjectId) ?: return null
        return if (manifest.sealed)
            DomainError.Conflict("Manifest is sealed — an ADMIN must unseal it before editing")
        else null
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
                sealBlockers = computeSealBlockers(all),
                // Preserve seal state, its audit history, and the Stage 2 lock; only the explicit
                // seal/unseal actions ever change them.
                sealed = existing?.sealed ?: false,
                sealEvents = existing?.sealEvents ?: emptyList(),
                stage2StartedAt = existing?.stage2StartedAt,
                updatedAt = Instant.now(),
            )
        manifests.save(manifest)
        return manifest
    }

    /**
     * Reasons sealing is blocked: pending consent, bytes not yet stored, or a duplicate upload (the
     * same file under two content types). Empty = ready to seal.
     */
    private fun computeSealBlockers(all: List<Asset>): List<String> {
        val blockers = mutableListOf<String>()
        val pendingConsent = all.filter { it.consentStatus == ConsentStatus.PENDING }
        if (pendingConsent.isNotEmpty())
            blockers +=
                "${pendingConsent.size} asset(s) pending consent: ${pendingConsent.joinToString { it.id }}"
        val notStored =
            all.filter {
                it.modality != AssetModality.LINK &&
                    it.consentStatus != ConsentStatus.REVOKED &&
                    (it.uploadStatus == AssetUploadStatus.AWAITING_UPLOAD ||
                        it.uploadStatus == AssetUploadStatus.FAILED)
            }
        if (notStored.isNotEmpty())
            blockers += "${notStored.size} asset(s) not stored: ${notStored.joinToString { it.id }}"
        // §12.7 cross-asset dedup: the same bytes uploaded under two content types get different
        // assetIds, so per-asset claim replacement can't catch it — left in, a duplicate upload
        // would corroborate itself in Stage 3. Block the seal until one copy is removed.
        all.filter { it.uploadStatus == AssetUploadStatus.STORED && !it.checksum.isNullOrBlank() }
            .groupBy { it.checksum }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                blockers +=
                    "${group.size} assets are the same file (identical bytes): " +
                        "${group.joinToString { it.id }} — remove all but one before sealing"
            }
        return blockers
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

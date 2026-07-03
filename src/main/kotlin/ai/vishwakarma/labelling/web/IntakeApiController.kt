package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.domain.defaultPrior
import ai.vishwakarma.labelling.domain.splitLabels
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.AssetPatch
import ai.vishwakarma.labelling.service.AssetRegistration
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.LinkRegistration
import ai.vishwakarma.labelling.service.Stage2Service
import ai.vishwakarma.labelling.service.SubjectService
import arrow.core.Either
import java.nio.file.Files
import java.time.LocalDate
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Stage 1 (Intake & Manifest) JSON API. Same-origin, authenticated (REVIEWER+), CSRF on — it is
 * driven by the app's own UI, not cross-site. Asset bytes never flow through here: the browser
 * uploads them directly to GCS via the signed URL returned by [registerAsset].
 */
@RestController
@RequestMapping("/api/intake")
@PreAuthorize("hasRole('REVIEWER')")
class IntakeApiController(
    private val subjectService: SubjectService,
    private val intake: IntakeService,
    private val stage2: Stage2Service,
) {

    private fun actor(): String? = CurrentUser.email()

    // ---- Subjects ---------------------------------------------------------
    @GetMapping("/subjects") fun subjects() = ResponseEntity.ok(subjectService.list())

    @PostMapping("/subjects")
    fun createSubject(@RequestBody body: SubjectRequest): ResponseEntity<Any> =
        subjectService
            .create(actor(), body.displayName, body.handle, body.notes ?: "")
            .toResponse(HttpStatus.CREATED)

    @GetMapping("/subjects/{id}")
    fun subject(@PathVariable id: String): ResponseEntity<Any> =
        subjectService.get(id)?.let { ResponseEntity.ok<Any>(it) } ?: notFound("Subject $id")

    @PatchMapping("/subjects/{id}")
    fun updateSubject(
        @PathVariable id: String,
        @RequestBody body: SubjectRequest,
    ): ResponseEntity<Any> =
        subjectService
            .update(
                id,
                body.displayName,
                body.handle,
                body.notes,
                SubjectStatus.fromOrNull(body.status)
            )
            .toResponse()

    @DeleteMapping("/subjects/{id}")
    fun deleteSubject(@PathVariable id: String): ResponseEntity<Any> {
        // Purge derived Stage 2 data (claims + jobs), assets (bytes + records) and the manifest
        // first, then the subject doc — delete-on-request must reach everything derived.
        stage2.purgeSubject(id)
        intake.purgeSubject(id)
        return subjectService.delete(id).toResponse()
    }

    // ---- Assets (signed-URL flow) -----------------------------------------
    @PostMapping("/subjects/{id}/assets")
    fun registerAsset(
        @PathVariable id: String,
        @RequestBody body: AssetRequest,
    ): ResponseEntity<Any> {
        val modality =
            AssetModality.fromOrNull(body.modality) ?: return badRequest("Invalid modality")
        val contentType =
            ContentType.fromOrNull(body.contentType) ?: return badRequest("Invalid contentType")
        val captureDate = parseDate(body.captureDate) ?: return dateError(body.captureDate)
        val claimedEventDate =
            parseDate(body.claimedEventDate) ?: return dateError(body.claimedEventDate)
        val reg =
            AssetRegistration(
                title = body.title,
                modality = modality,
                contentType = contentType,
                relationship = Relationship.fromOrNull(body.relationship) ?: Relationship.UNKNOWN,
                originalFilename = body.originalFilename,
                mimeType = body.mimeType,
                sourceName = body.sourceName,
                captureDate = captureDate.value,
                claimedEventDate = claimedEventDate.value,
                consentStatus =
                    ConsentStatus.fromOrNull(body.consentStatus) ?: ConsentStatus.PENDING,
                consentNote = body.consentNote,
                labels = splitLabels(body.labels),
                notes = body.notes ?: "",
                declaredSizeBytes = body.declaredSizeBytes,
            )
        return intake.registerAsset(id, actor(), reg).toResponse(HttpStatus.CREATED)
    }

    @PostMapping("/assets/{assetId}/complete")
    fun completeAsset(@PathVariable assetId: String): ResponseEntity<Any> =
        intake.completeAsset(assetId).toResponse()

    /** Re-issue a signed upload URL for an asset stuck in AWAITING_UPLOAD/FAILED. */
    @PostMapping("/assets/{assetId}/upload-url")
    fun reissueUploadUrl(@PathVariable assetId: String): ResponseEntity<Any> =
        intake.reissueUploadUrl(assetId).toResponse()

    /** Re-checks one stuck asset's bytes and completes or fails it. */
    @PostMapping("/assets/{assetId}/reconcile")
    fun reconcileAsset(@PathVariable assetId: String): ResponseEntity<Any> =
        intake.reconcileAsset(assetId).toResponse()

    /** Reconciles every stuck asset for a subject; returns the ones that changed. */
    @PostMapping("/subjects/{id}/reconcile")
    fun reconcileSubject(@PathVariable id: String) = ResponseEntity.ok(intake.reconcileSubject(id))

    @PostMapping("/subjects/{id}/links")
    fun registerLink(
        @PathVariable id: String,
        @RequestBody body: LinkRequest,
    ): ResponseEntity<Any> {
        val contentType =
            ContentType.fromOrNull(body.contentType) ?: return badRequest("Invalid contentType")
        val captureDate = parseDate(body.captureDate) ?: return dateError(body.captureDate)
        val claimedEventDate =
            parseDate(body.claimedEventDate) ?: return dateError(body.claimedEventDate)
        val reg =
            LinkRegistration(
                title = body.title,
                contentType = contentType,
                externalUrl = body.externalUrl,
                relationship = Relationship.fromOrNull(body.relationship) ?: Relationship.UNKNOWN,
                sourceName = body.sourceName,
                captureDate = captureDate.value,
                claimedEventDate = claimedEventDate.value,
                consentStatus =
                    ConsentStatus.fromOrNull(body.consentStatus) ?: ConsentStatus.NOT_REQUIRED,
                consentNote = body.consentNote,
                labels = splitLabels(body.labels),
                notes = body.notes ?: "",
            )
        return intake.registerLink(id, actor(), reg).toResponse(HttpStatus.CREATED)
    }

    @GetMapping("/subjects/{id}/assets")
    fun assets(@PathVariable id: String) = ResponseEntity.ok(intake.listAssets(id))

    @PatchMapping("/assets/{assetId}")
    fun updateAsset(
        @PathVariable assetId: String,
        @RequestBody body: AssetPatchRequest,
    ): ResponseEntity<Any> {
        val captureDate = parseDate(body.captureDate) ?: return dateError(body.captureDate)
        val claimedEventDate =
            parseDate(body.claimedEventDate) ?: return dateError(body.claimedEventDate)
        val patch =
            AssetPatch(
                title = body.title,
                contentType = body.contentType?.let { ContentType.fromOrNull(it) },
                relationship = body.relationship?.let { Relationship.fromOrNull(it) },
                authenticityPrior = body.authenticityPrior?.let { AuthenticityTier.fromOrNull(it) },
                sourceName = body.sourceName,
                captureDate = captureDate.value,
                claimedEventDate = claimedEventDate.value,
                consentStatus = body.consentStatus?.let { ConsentStatus.fromOrNull(it) },
                consentNote = body.consentNote,
                labels = body.labels?.let { splitLabels(it) },
                notes = body.notes,
            )
        return intake.updateAsset(assetId, patch).toResponse()
    }

    @DeleteMapping("/assets/{assetId}")
    fun deleteAsset(@PathVariable assetId: String): ResponseEntity<Any> =
        intake.deleteAsset(assetId).toResponse()

    /** A retrievable URL for the asset (external link, or a short-lived signed GET) for preview. */
    @GetMapping("/assets/{assetId}/download-url")
    fun downloadUrl(@PathVariable assetId: String): ResponseEntity<Any> =
        intake.downloadUrl(assetId).map { mapOf("url" to it) }.toResponse()

    // ---- Manifest ---------------------------------------------------------
    @GetMapping("/subjects/{id}/manifest")
    fun manifest(@PathVariable id: String) = ResponseEntity.ok(intake.manifest(id))

    /** Seal for the Stage 2 handoff. Body: `{"note": "..."}` — the note is mandatory (400). */
    @PostMapping("/subjects/{id}/manifest/seal")
    fun sealManifest(
        @PathVariable id: String,
        @RequestBody(required = false) body: SealNoteRequest?,
    ): ResponseEntity<Any> = intake.sealManifest(id, actor(), body?.note ?: "").toResponse()

    /**
     * Reverse a seal. ADMIN-only (seal is REVIEWER+); reopens the manifest for editing. Body:
     * `{"note": "..."}` — the note is mandatory (400). Refused permanently once Stage 2 has started
     * consuming the manifest (409).
     */
    @PostMapping("/subjects/{id}/manifest/unseal")
    @PreAuthorize("hasRole('ADMIN')")
    fun unsealManifest(
        @PathVariable id: String,
        @RequestBody(required = false) body: SealNoteRequest?,
    ): ResponseEntity<Any> = intake.unsealManifest(id, actor(), body?.note ?: "").toResponse()

    // ---- Taxonomy (drives the future UI's dropdowns) ----------------------
    @GetMapping("/taxonomy")
    fun taxonomy(): ResponseEntity<Any> =
        ResponseEntity.ok(
            mapOf(
                "modalities" to AssetModality.entries.map { it.name },
                "sourceClasses" to SourceClass.entries.map { it.name },
                "contentTypes" to
                    ContentType.entries.map {
                        mapOf(
                            "name" to it.name,
                            "sourceClass" to it.sourceClass.name,
                            "basePrior" to it.basePrior.name,
                        )
                    },
                "relationships" to Relationship.entries.map { it.name },
                "consentStatuses" to ConsentStatus.entries.map { it.name },
                "authenticityTiers" to AuthenticityTier.entries.map { it.name },
            )
        )

    /**
     * Preview the auto-derived authenticity prior for a (contentType, relationship) pair — lets the
     * future form show the derived tier live before the asset is saved.
     */
    @GetMapping("/prior")
    fun prior(
        @RequestParam contentType: String,
        @RequestParam(required = false) relationship: String?,
    ): ResponseEntity<Any> {
        val ct = ContentType.fromOrNull(contentType) ?: return badRequest("Invalid contentType")
        val prior = defaultPrior(ct, Relationship.fromOrNull(relationship))
        return ResponseEntity.ok(mapOf("authenticityPrior" to prior.name))
    }

    // ---- Local-dev byte sink (used only when intake-bucket is blank) ------
    @PutMapping("/dev/upload")
    fun devUpload(
        @RequestParam path: String,
        @RequestBody(required = false) bytes: ByteArray?,
    ): ResponseEntity<Any> {
        intake.storeLocalBytes(path, bytes ?: ByteArray(0))
        return ResponseEntity.ok(mapOf("status" to "ok", "bytes" to (bytes?.size ?: 0)))
    }

    /**
     * Local-dev byte source for previews (used only when intake-bucket is blank; in prod
     * [ai.vishwakarma.labelling.gcs.IntakeStorage.signedDownloadUrl] returns a real signed GCS URL
     * and nothing links here). Streams the stored file inline so a browser can open it — a redirect
     * to a `file://` URI can't be followed.
     */
    @GetMapping("/dev/download")
    fun devDownload(@RequestParam path: String): ResponseEntity<Any> {
        val file = intake.localFile(path) ?: return notFound("Object")
        val contentType =
            runCatching { Files.probeContentType(file) }.getOrNull() ?: "application/octet-stream"
        val resource: Any = FileSystemResource(file)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(resource)
    }

    // ---- helpers ----------------------------------------------------------
    private fun notFound(what: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "$what not found"))

    private fun badRequest(msg: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(mapOf("error" to msg))

    private fun dateError(raw: String?): ResponseEntity<Any> =
        badRequest("Invalid date '$raw' (expected yyyy-MM-dd)")

    /** Wrap a parse result: null = parse failed; Parsed(null) = field absent (both legal here). */
    private data class Parsed<T>(val value: T?)

    private fun parseDate(raw: String?): Parsed<LocalDate>? {
        if (raw.isNullOrBlank()) return Parsed(null)
        return runCatching { Parsed(LocalDate.parse(raw)) }.getOrNull()
    }

    private fun Either<DomainError, Any>.toResponse(
        okStatus: HttpStatus = HttpStatus.OK
    ): ResponseEntity<Any> =
        fold(
            { err -> errorResponse(err) },
            { value -> ResponseEntity.status(okStatus).body(value) }
        )

    private fun errorResponse(err: DomainError): ResponseEntity<Any> {
        val status =
            when (err) {
                is DomainError.NotFound -> HttpStatus.NOT_FOUND
                is DomainError.Conflict -> HttpStatus.CONFLICT
                is DomainError.Invalid -> HttpStatus.BAD_REQUEST
            }
        return ResponseEntity.status(status).body(mapOf("error" to err.message))
    }

    // ---- request DTOs -----------------------------------------------------
    data class SubjectRequest(
        val displayName: String = "",
        val handle: String? = null,
        val notes: String? = null,
        val status: String? = null,
    )

    data class AssetRequest(
        val title: String = "",
        val modality: String = "",
        val contentType: String = "",
        val relationship: String? = null,
        val originalFilename: String? = null,
        val mimeType: String? = null,
        val sourceName: String? = null,
        val captureDate: String? = null,
        val claimedEventDate: String? = null,
        val consentStatus: String? = null,
        val consentNote: String? = null,
        val labels: String? = null,
        val notes: String? = null,
        /** Client-declared byte size (e.g. `File.size`); advisory fast-fail only. */
        val declaredSizeBytes: Long? = null,
    )

    data class LinkRequest(
        val title: String = "",
        val contentType: String = "",
        val externalUrl: String = "",
        val relationship: String? = null,
        val sourceName: String? = null,
        val captureDate: String? = null,
        val claimedEventDate: String? = null,
        val consentStatus: String? = null,
        val consentNote: String? = null,
        val labels: String? = null,
        val notes: String? = null,
    )

    /** Operator note accompanying a seal/unseal action (mandatory at the service layer). */
    data class SealNoteRequest(val note: String? = null)

    data class AssetPatchRequest(
        val title: String? = null,
        val contentType: String? = null,
        val relationship: String? = null,
        val authenticityPrior: String? = null,
        val sourceName: String? = null,
        val captureDate: String? = null,
        val claimedEventDate: String? = null,
        val consentStatus: String? = null,
        val consentNote: String? = null,
        val labels: String? = null,
        val notes: String? = null,
    )
}

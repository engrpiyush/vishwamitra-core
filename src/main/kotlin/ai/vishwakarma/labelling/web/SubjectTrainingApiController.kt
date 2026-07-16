package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.domain.allowedExtensions
import ai.vishwakarma.labelling.gcs.SignedUpload
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.AssetRegistration
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.Stage2Service
import jakarta.servlet.http.HttpServletRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The subject world's slice of the signed-URL upload handshake (VA-32) — the operator `/api/intake`
 * surface is REVIEWER-gated and doesn't exist on subject hosts, so the same three calls live here
 * under the training area (subject chain rule: subject-of-host ∨ operator), each scoped to the
 * host-resolved subject. Deltas from the operator surface: registration takes a curated
 * [SubjectAssetKind] (never a raw taxonomy value), requires the F2 consent attestation (blocks
 * without it; stamps the manifest per batch, §13.1), and error strings are friendly-mapped — the
 * verbatim service message is logged, never returned (§12.3).
 */
@RestController
@RequestMapping("/s/training/api")
class SubjectTrainingApiController(
    private val intake: IntakeService,
    private val stage2: Stage2Service,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun ctx(request: HttpServletRequest): SubjectCtx? = SubjectCtx.of(request)

    data class SubjectAssetRequest(
        val kind: String = "",
        val from: String? = null,
        val title: String? = null,
        val originalFilename: String? = null,
        val mimeType: String? = null,
        val declaredSizeBytes: Long? = null,
        /** The F2 attestation checkbox — registration is refused without it (§13.1). */
        val attested: Boolean = false,
    )

    @PostMapping("/assets")
    fun register(
        request: HttpServletRequest,
        @RequestBody body: SubjectAssetRequest,
    ): ResponseEntity<Any> {
        val ctx = ctx(request) ?: return notFound()
        if (!body.attested)
            return badRequest("Please confirm the consent statement before uploading.")
        val kind =
            SubjectAssetKind.fromOrNull(body.kind)
                ?: return badRequest("Pick what this file is from the list.")
        val filename = body.originalFilename?.trim().orEmpty()
        val modality =
            modalityFor(filename)
                ?: return badRequest(
                    "That file type isn't supported — audio, video, images and documents work."
                )
        val (contentType, relationship) = kind.resolve(SubjectFrom.fromOrNull(body.from))
        val registration =
            AssetRegistration(
                title = body.title?.trim()?.takeIf { it.isNotBlank() } ?: filename,
                modality = modality,
                contentType = contentType,
                relationship = relationship,
                originalFilename = filename,
                mimeType = body.mimeType,
                // The attestation IS the consent record for a self-serve upload (F2, §13.1); the
                // audit stamp lives on the manifest (consentAttestedAt/By).
                consentStatus = ConsentStatus.GRANTED,
                consentNote = "Subject attested the upload-consent statement (self-serve)",
                declaredSizeBytes = body.declaredSizeBytes,
            )
        return intake
            .registerAsset(ctx.subjectId, CurrentUser.email(), registration)
            .fold(
                { err -> friendlyError(err, "register", ctx.subjectId) },
                { result ->
                    intake.attestConsent(ctx.subjectId, CurrentUser.email())
                    ResponseEntity.status(HttpStatus.CREATED)
                        .body(
                            mapOf(
                                "asset" to mapOf("id" to result.asset.id),
                                "upload" to subjectUpload(request, result.upload),
                            )
                        )
                },
            )
    }

    @PostMapping("/assets/{assetId}/complete")
    fun complete(
        request: HttpServletRequest,
        @PathVariable assetId: String,
    ): ResponseEntity<Any> {
        val ctx = ctx(request) ?: return notFound()
        if (!ownedBySubject(assetId, ctx)) return notFound()
        return intake
            .completeAsset(assetId)
            .fold(
                { err -> friendlyError(err, "complete", ctx.subjectId) },
                { ResponseEntity.ok(mapOf("status" to "ok")) },
            )
    }

    @PostMapping("/assets/{assetId}/upload-url")
    fun reissue(
        request: HttpServletRequest,
        @PathVariable assetId: String,
    ): ResponseEntity<Any> {
        val ctx = ctx(request) ?: return notFound()
        if (!ownedBySubject(assetId, ctx)) return notFound()
        return intake
            .reissueUploadUrl(assetId)
            .fold(
                { err -> friendlyError(err, "reissue", ctx.subjectId) },
                { result ->
                    ResponseEntity.ok(
                        mapOf(
                            "asset" to mapOf("id" to result.asset.id),
                            "upload" to subjectUpload(request, result.upload),
                        )
                    )
                },
            )
    }

    /**
     * The S4 open-page poll driver: advance every non-terminal job, answer friendly-state names
     * only (the payload feeds the reload fingerprint — §12.3 vocabulary discipline applies to it
     * too). A job seen transitioning to a failure logs the operator-notification hook.
     */
    @PostMapping("/poll")
    fun poll(request: HttpServletRequest): ResponseEntity<Any> {
        val ctx = ctx(request) ?: return notFound()
        val before = stage2.listJobs(ctx.subjectId).associate { it.id to it.status }
        val after = stage2.pollAll(ctx.subjectId)
        after
            .filter {
                it.status == Stage2JobStatus.FAILED && before[it.id] != Stage2JobStatus.FAILED
            }
            .forEach {
                // Operator notification hook (log-based until §11.3 mail wiring reaches failures).
                log.warn(
                    "OPERATOR ATTENTION: processing failed for subject {} job {} — subject sees " +
                        "the friendly banner only",
                    ctx.subjectId,
                    it.id,
                )
            }
        return ResponseEntity.ok(
            after.map {
                mapOf(
                    "id" to it.id,
                    "state" to SubjectTraining.friendlyJob(it, title = "").state.name,
                )
            }
        )
    }

    /**
     * Local-dev byte sink for subject-host uploads — the operator sink is REVIEWER-gated and 404s
     * on subject hosts, so [subjectUpload] repoints dev signed URLs here. Refuses (404) whenever a
     * real intake bucket is configured: in prod the browser PUTs straight to GCS and this endpoint
     * must look nonexistent.
     */
    @PutMapping("/dev-upload")
    fun devUpload(
        request: HttpServletRequest,
        @RequestParam path: String,
        @RequestBody(required = false) bytes: ByteArray?,
    ): ResponseEntity<Any> {
        val ctx = ctx(request) ?: return notFound()
        if (props.gcp.intakeBucket.isNotBlank()) return notFound()
        // The object path is subject-scoped by construction (intake/{subjectId}/…) — refuse a
        // probe writing outside the host subject's tree.
        if (!path.startsWith("intake/${ctx.subjectId}/")) return notFound()
        intake.storeLocalBytes(path, bytes ?: ByteArray(0))
        return ResponseEntity.ok(mapOf("status" to "ok", "bytes" to (bytes?.size ?: 0)))
    }

    // ---- helpers -------------------------------------------------------------

    private fun ownedBySubject(assetId: String, ctx: SubjectCtx): Boolean =
        intake.getAsset(assetId)?.subjectId == ctx.subjectId

    /**
     * Subject-host view of a [SignedUpload]: real GCS URLs pass through untouched; the local-dev
     * URL is repointed at [devUpload] (the operator sink 404s on subject hosts) and carries the
     * CSRF header — the raw-bytes PUT rides the subject chain, whose CSRF protection stays on.
     */
    private fun subjectUpload(request: HttpServletRequest, upload: SignedUpload): SignedUpload {
        if (!upload.local) return upload
        val encoded = URLEncoder.encode(upload.objectPath, StandardCharsets.UTF_8)
        val csrf = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken
        return upload.copy(
            url = "/training/api/dev-upload?path=$encoded",
            headers =
                upload.headers + (csrf?.let { mapOf(it.headerName to it.token) } ?: emptyMap()),
        )
    }

    /**
     * Server-side twin of the uploader's extension→modality guess, limited to processable lanes.
     */
    private fun modalityFor(filename: String): AssetModality? {
        val ext = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isBlank()) return null
        return SUBJECT_MODALITIES.firstOrNull { ext in it.allowedExtensions }
    }

    /**
     * §12.3: the verbatim service error is logged for the operator; the subject gets capability
     * language. Size problems are the one case worth a specific hint.
     */
    private fun friendlyError(
        err: DomainError,
        action: String,
        subjectId: String,
    ): ResponseEntity<Any> {
        log.warn("Subject upload {} refused for {}: {}", action, subjectId, err.message)
        val friendly =
            when {
                err.message.contains("exceeds", ignoreCase = true) ->
                    "That file is too large — anything up to 5 GB works."
                err.message.contains("empty", ignoreCase = true) ->
                    "The upload didn't finish — try that file again."
                else -> "That upload didn't go through — try again in a moment."
            }
        val status =
            when (err) {
                is DomainError.NotFound -> HttpStatus.NOT_FOUND
                is DomainError.Conflict -> HttpStatus.CONFLICT
                is DomainError.Invalid -> HttpStatus.BAD_REQUEST
            }
        return ResponseEntity.status(status).body(mapOf("error" to friendly))
    }

    private fun badRequest(message: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(mapOf("error" to message))

    private fun notFound(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Not found"))

    companion object {
        /** The lanes a subject can upload — everything Stage 2 can actually process. */
        private val SUBJECT_MODALITIES =
            listOf(
                AssetModality.AUDIO,
                AssetModality.VIDEO,
                AssetModality.IMAGE,
                AssetModality.DOCUMENT,
            )
    }
}

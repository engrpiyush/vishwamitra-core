package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.ClaimReviewService
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.QuestionService
import ai.vishwakarma.labelling.service.Stage2Service
import arrow.core.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** JSON bodies for the §12.6 review endpoints. */
data class ReviewRequest(
    val decision: String,
    val justification: String? = null,
    val corroboratingClaimIds: List<String>? = null,
)

data class PiiRequest(val choice: String)

/**
 * Stage 2 (A/V → Claims) JSON API. Same-origin, authenticated (REVIEWER+), CSRF on — the same
 * conventions as [IntakeApiController]. Submit-then-poll: `process` starts the run (and makes the
 * manifest's seal permanent); `poll` drives each job forward.
 */
@RestController
@RequestMapping("/api/stage2")
@PreAuthorize("hasRole('REVIEWER')")
class Stage2ApiController(
    private val stage2: Stage2Service,
    private val reviewService: ClaimReviewService,
    private val questions: QuestionService,
) {

    private fun actor(): String? = CurrentUser.email()

    /** Start Stage 2 for a sealed subject: stamps the permanent lock and submits transcriptions. */
    @PostMapping("/subjects/{id}/process")
    fun process(@PathVariable id: String): ResponseEntity<Any> =
        stage2.process(id, actor()).toResponse(HttpStatus.CREATED)

    /** Advance one job: check its transcription LRO and extract claims when ready. */
    @PostMapping("/jobs/{id}/poll")
    fun poll(@PathVariable id: String): ResponseEntity<Any> = stage2.poll(id).toResponse()

    /** Poll every active job for the subject once; returns the full updated job list. */
    @PostMapping("/subjects/{id}/poll-all")
    fun pollAll(@PathVariable id: String): ResponseEntity<Any> =
        ResponseEntity.ok(stage2.pollAll(id))

    /** Retry a FAILED job: fresh transcription of the same asset. */
    @PostMapping("/jobs/{id}/retry")
    fun retry(@PathVariable id: String): ResponseEntity<Any> = stage2.retryJob(id).toResponse()

    /**
     * Re-run a COMPLETED job, replacing the asset's claims: re-extract from the stored transcript
     * (default), or [full] re-transcription first.
     */
    @PostMapping("/jobs/{id}/rerun")
    fun rerun(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "false") full: Boolean,
    ): ResponseEntity<Any> = stage2.rerunJob(id, full).toResponse()

    /**
     * §12.4 Phase B: replace a COMPLETED A/V job's speaker→role binding (keyed by diarization
     * label) and re-extract, so operator corrections re-weight the claims.
     */
    @PostMapping("/jobs/{id}/speaker-roles")
    fun updateSpeakerRoles(
        @PathVariable id: String,
        @RequestBody roles: Map<String, SpeakerAssignment>,
    ): ResponseEntity<Any> = stage2.updateSpeakerRoles(id, roles).toResponse()

    /**
     * §12.4 selection gate: resolve an AWAITING_SPEAKER_SELECTION job — [selfLabels] are the
     * diarized labels that are the subject (empty = subject not on the call) — then extract.
     */
    @PostMapping("/jobs/{id}/resolve-speakers")
    fun resolveSpeakers(
        @PathVariable id: String,
        @RequestBody(required = false) selfLabels: List<String>?,
    ): ResponseEntity<Any> = stage2.resolveSpeakers(id, selfLabels ?: emptyList()).toResponse()

    // ---- §12.6 claim review ------------------------------------------------

    /** Start the review flow: freezes the subject's claims (no more re-extraction). */
    @PostMapping("/subjects/{id}/review/start")
    fun startReview(@PathVariable id: String): ResponseEntity<Any> =
        reviewService
            .startReview(id)
            // F11 (§9.2): questions generate at review lock, whichever door locked it.
            .map { manifest ->
                questions.generateForReview(id)
                manifest
            }
            .toResponse()

    /** Record a decision on one claim (approve / sidecar / contest). */
    @PostMapping("/claims/{claimId}/review")
    fun reviewClaim(
        @PathVariable claimId: String,
        @RequestBody body: ReviewRequest,
    ): ResponseEntity<Any> {
        val decision =
            ReviewDecision.fromOrNull(body.decision)
                ?: return badRequest("Invalid decision '${body.decision}'")
        return reviewService
            .reviewClaim(
                claimId,
                decision,
                body.justification,
                body.corroboratingClaimIds ?: emptyList(),
                actor(),
            )
            .toResponse()
    }

    /** Opt a sensitive claim into or out of tuning. */
    @PostMapping("/claims/{claimId}/pii")
    fun reviewPii(
        @PathVariable claimId: String,
        @RequestBody body: PiiRequest,
    ): ResponseEntity<Any> {
        val choice =
            PiiChoice.fromOrNull(body.choice)
                ?: return badRequest("Invalid choice '${body.choice}'")
        return reviewService.setPii(claimId, choice, actor()).toResponse()
    }

    /** Finalize the review — the approved-and-not-contested set becomes the Stage-3 gate. */
    @PostMapping("/subjects/{id}/review/submit")
    fun submitReview(@PathVariable id: String): ResponseEntity<Any> =
        reviewService.submitReview(id).toResponse()

    /** ADMIN: reopen a submitted review for edits (claims stay frozen). */
    @PostMapping("/subjects/{id}/review/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    fun reopenReview(@PathVariable id: String): ResponseEntity<Any> =
        reviewService.reopenReview(id).toResponse()

    /** The Stage-3 read path: approved-and-not-contested claims, each with its sidecar. */
    @GetMapping("/subjects/{id}/approved-claims")
    fun approvedClaims(@PathVariable id: String) =
        ResponseEntity.ok(reviewService.approvedForDownstream(id))

    @GetMapping("/jobs/{id}")
    fun job(@PathVariable id: String): ResponseEntity<Any> =
        stage2.job(id)?.let { ResponseEntity.ok<Any>(it) } ?: notFound("Job $id")

    @GetMapping("/subjects/{id}/jobs")
    fun jobs(@PathVariable id: String) = ResponseEntity.ok(stage2.listJobs(id))

    @GetMapping("/subjects/{id}/claims")
    fun claims(@PathVariable id: String) = ResponseEntity.ok(stage2.listClaims(id))

    // ---- Helpers -----------------------------------------------------------
    private fun notFound(what: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "$what not found"))

    private fun badRequest(msg: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(mapOf("error" to msg))

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
}

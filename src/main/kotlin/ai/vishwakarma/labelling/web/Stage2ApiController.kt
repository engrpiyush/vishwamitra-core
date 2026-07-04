package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.Stage2Service
import arrow.core.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Stage 2 (A/V → Claims) JSON API. Same-origin, authenticated (REVIEWER+), CSRF on — the same
 * conventions as [IntakeApiController]. Submit-then-poll: `process` starts the run (and makes the
 * manifest's seal permanent); `poll` drives each job forward.
 */
@RestController
@RequestMapping("/api/stage2")
@PreAuthorize("hasRole('REVIEWER')")
class Stage2ApiController(private val stage2: Stage2Service) {

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

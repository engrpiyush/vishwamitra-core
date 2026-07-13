package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.Stage4Service
import ai.vishwakarma.labelling.service.Stage4SubmitRequest
import arrow.core.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Stage 4 (published ledger → conversation notebooks) JSON API — LLD §15. Same conventions as
 * [Stage3ApiController]: same-origin, REVIEWER+, CSRF on, submit-then-poll, error model `{"error":
 * "..."}` with 404/409/400. Review of judged conversations rides the existing sft/ pages; the
 * judge-verdict panel arrives with the FE tickets (VA-63…66).
 */
@RestController
@RequestMapping("/api/stage4")
@PreAuthorize("hasRole('REVIEWER')")
class Stage4ApiController(private val stage4: Stage4Service) {

    private fun actor(): String? = CurrentUser.email()

    /**
     * Start a run: enabled/subject/active-run guards, params snapshot (incl. the body's QA-3
     * mix-weight overrides + `fresh`). A parked REVIEW_WAIT run is retired as SUPERSEDED.
     */
    @PostMapping("/subjects/{id}/run")
    fun run(
        @PathVariable id: String,
        @RequestBody(required = false) body: Stage4SubmitRequest?,
    ): ResponseEntity<Any> =
        stage4.submit(id, body ?: Stage4SubmitRequest(), actor()).toResponse(HttpStatus.CREATED)

    /** Advance the run one bounded step (select / plan / generate batch / judge batch). */
    @PostMapping("/runs/{id}/poll")
    fun poll(@PathVariable id: String): ResponseEntity<Any> = stage4.poll(id).toResponse()

    /** FAILED → resume from the failed phase (phases are re-entrant). */
    @PostMapping("/runs/{id}/retry")
    fun retry(@PathVariable id: String): ResponseEntity<Any> = stage4.retry(id).toResponse()

    /**
     * The QA-4 gate's other side: export the parked run's APPROVED current-stamp examples and
     * complete it (REVIEW_WAIT → DONE, exportRecordId journaled). Validator failures come back 400
     * with exampleId pointers; a non-parked run is a 409.
     */
    @PostMapping("/runs/{id}/export")
    fun export(@PathVariable id: String): ResponseEntity<Any> =
        stage4.export(id, actor()).toResponse()

    /** The subject's latest run (poll-loop + run-page read). */
    @GetMapping("/subjects/{id}/run")
    fun latest(@PathVariable id: String): ResponseEntity<Any> =
        stage4.latestForSubject(id)?.let { ResponseEntity.ok<Any>(it) }
            ?: notFound("No Stage 4 run for subject $id")

    @GetMapping("/runs/{id}")
    fun runById(@PathVariable id: String): ResponseEntity<Any> =
        stage4.run(id)?.let { ResponseEntity.ok<Any>(it) } ?: notFound("Run $id")

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

package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.PersonaService
import ai.vishwakarma.labelling.service.PersonaUpdateRequest
import arrow.core.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The persona wizard API (Stage 4 LLD §15): `GET/PUT /api/subjects/{id}/persona`. Same conventions
 * as [Stage3ApiController]: same-origin, REVIEWER+, error model `{"error": "..."}`.
 */
@RestController
@RequestMapping("/api/subjects/{id}/persona")
@PreAuthorize("hasRole('REVIEWER')")
class PersonaApiController(private val personas: PersonaService) {

    @GetMapping
    fun get(@PathVariable id: String): ResponseEntity<Any> = personas.view(id).toResponse()

    @PutMapping
    fun put(
        @PathVariable id: String,
        @RequestBody body: PersonaUpdateRequest,
    ): ResponseEntity<Any> = personas.put(id, body, CurrentUser.email()).toResponse()

    private fun Either<DomainError, Any>.toResponse(): ResponseEntity<Any> =
        fold(
            { err ->
                val status =
                    when (err) {
                        is DomainError.NotFound -> HttpStatus.NOT_FOUND
                        is DomainError.Conflict -> HttpStatus.CONFLICT
                        is DomainError.Invalid -> HttpStatus.BAD_REQUEST
                    }
                ResponseEntity.status(status).body(mapOf("error" to err.message))
            },
            { value -> ResponseEntity.ok(value) },
        )
}

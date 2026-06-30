package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.springframework.stereotype.Service

/**
 * CRUD for [Subject]s — the person a Neo model is built for. Asset/manifest purge on delete is
 * handled by [IntakeService] (which owns storage); see IntakeApiController.
 */
@Service
class SubjectService(private val subjects: SubjectRepository) {

    fun list(): List<Subject> = subjects.findAll()

    fun get(id: String): Subject? = subjects.findById(id)

    fun create(
        actor: String?,
        displayName: String,
        handle: String?,
        notes: String,
    ): Either<DomainError, Subject> {
        if (displayName.isBlank()) return DomainError.Invalid("Subject name is required").left()
        val now = Instant.now()
        val subject =
            Subject(
                id = subjects.newId(),
                displayName = displayName.trim(),
                handle = handle?.trim()?.takeIf { it.isNotBlank() },
                notes = notes.trim(),
                status = SubjectStatus.ACTIVE,
                createdBy = actor,
                createdAt = now,
                updatedAt = now,
            )
        subjects.save(subject)
        return subject.right()
    }

    fun update(
        id: String,
        displayName: String?,
        handle: String?,
        notes: String?,
        status: SubjectStatus?,
    ): Either<DomainError, Subject> {
        val current =
            subjects.findById(id) ?: return DomainError.NotFound("Subject $id not found").left()
        if (displayName != null && displayName.isBlank())
            return DomainError.Invalid("Subject name cannot be blank").left()
        val updated =
            current.copy(
                displayName = displayName?.trim() ?: current.displayName,
                handle = handle?.trim()?.takeIf { it.isNotBlank() } ?: current.handle,
                notes = notes?.trim() ?: current.notes,
                status = status ?: current.status,
                updatedAt = Instant.now(),
            )
        subjects.save(updated)
        return updated.right()
    }

    fun delete(id: String): Either<DomainError, Unit> {
        subjects.findById(id) ?: return DomainError.NotFound("Subject $id not found").left()
        subjects.delete(id)
        return Unit.right()
    }
}

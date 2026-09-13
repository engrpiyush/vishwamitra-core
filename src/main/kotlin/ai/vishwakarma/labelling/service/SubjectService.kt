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
 *
 * VA-31 (LLD §4.5): handles are validated ([HANDLE_REGEX] — 6–32 lowercase letters/digits),
 * reserved-word-checked (§17.2 via [ReservedHandles]), unique (the `handles/{handle}` sentinel doc
 * written transactionally with the subject), and immutable once set (D6) — they become the
 * subject's `<handle>.vishwakarma.ai` host.
 */
@Service
class SubjectService(
    private val subjects: SubjectRepository,
    private val reserved: ReservedHandles,
) {

    fun list(): List<Subject> = subjects.findAll()

    fun get(id: String): Subject? = subjects.findById(id)

    fun create(
        actor: String?,
        displayName: String,
        handle: String?,
        notes: String,
        contactEmail: String? = null,
    ): Either<DomainError, Subject> {
        if (displayName.isBlank()) return DomainError.Invalid("Subject name is required").left()
        val normalized = normalizeHandle(handle)
        normalized?.let { h ->
            handleError(h)?.let {
                return it.left()
            }
        }
        val email = normalizeEmail(contactEmail)
        if (email != null && !EMAIL_REGEX.matches(email))
            return DomainError.Invalid("Contact email '$email' is not a valid address").left()
        val now = Instant.now()
        val subject =
            Subject(
                id = subjects.newId(),
                displayName = displayName.trim(),
                handle = normalized,
                contactEmail = email,
                notes = notes.trim(),
                status = SubjectStatus.ACTIVE,
                createdBy = actor,
                createdAt = now,
                updatedAt = now,
            )
        if (normalized == null) {
            subjects.save(subject)
        } else if (!subjects.createWithHandle(subject)) {
            return DomainError.Invalid("Handle '$normalized' is already taken").left()
        }
        return subject.right()
    }

    fun update(
        id: String,
        displayName: String?,
        handle: String?,
        notes: String?,
        status: SubjectStatus?,
        /** Null = untouched; blank = cleared (the email line then drops from the §9.6 header). */
        contactEmail: String? = null,
    ): Either<DomainError, Subject> {
        val current =
            subjects.findById(id) ?: return DomainError.NotFound("Subject $id not found").left()
        if (displayName != null && displayName.isBlank())
            return DomainError.Invalid("Subject name cannot be blank").left()
        val email = contactEmail?.let { normalizeEmail(it) }
        if (email != null && !EMAIL_REGEX.matches(email))
            return DomainError.Invalid("Contact email '$email' is not a valid address").left()
        val requested = normalizeHandle(handle)
        if (requested != null && current.handle != null && requested != current.handle) {
            return DomainError.Invalid(
                    "Handle is immutable once set — '${current.handle}' is this subject's host"
                )
                .left()
        }
        if (requested != null && current.handle == null) {
            handleError(requested)?.let {
                return it.left()
            }
            if (!subjects.claimHandle(id, requested)) {
                return DomainError.Invalid("Handle '$requested' is already taken").left()
            }
        }
        val updated =
            current.copy(
                displayName = displayName?.trim() ?: current.displayName,
                handle = current.handle ?: requested,
                contactEmail = if (contactEmail == null) current.contactEmail else email,
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

    private fun normalizeHandle(handle: String?): String? =
        handle?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    private fun normalizeEmail(email: String?): String? = email?.trim()?.takeIf { it.isNotBlank() }

    private fun handleError(handle: String): DomainError? =
        when {
            !HANDLE_REGEX.matches(handle) ->
                DomainError.Invalid(
                    "Handle must be 6–32 characters, lowercase letters and digits only"
                )
            reserved.contains(handle) -> DomainError.Invalid("'$handle' is a reserved handle")
            else -> null
        }

    companion object {
        /**
         * Owner rule 2026-07-16 (tightens the LLD §4.5 draft): 6–32 chars, lowercase letters and
         * digits only. Periods are structurally impossible — a handle is exactly one DNS label
         * under {base-domain} (the wildcard cert covers one label; the host filter 404s dots) — and
         * hyphens were dropped by the same decision.
         */
        val HANDLE_REGEX = Regex("^[a-z0-9]{6,32}$")

        /** Shape check only (has a local part, an @, a dotted domain) — not RFC pedantry. */
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.domain.User
import ai.vishwakarma.labelling.persistence.UserRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.springframework.stereotype.Service

/**
 * Allowlist management + the role lookup used during OAuth login. Only active users have a role; an
 * inactive or unknown email resolves to null → login denied.
 */
@Service
class UserService(private val users: UserRepository) {

    /** Role for an allowlisted, active user; null if absent or deactivated (→ deny). */
    fun roleFor(email: String): Role? = activeUser(email)?.role

    /** The full allowlist row for an active user (VA-29: login needs role + subject binding). */
    fun activeUser(email: String): User? = users.findByEmail(email)?.takeIf { it.active }

    /** The allowlist row regardless of active flag (admin surfaces). */
    fun get(email: String): User? = users.findByEmail(email)

    fun list(): List<User> = users.findAll()

    /**
     * VA-31 (LLD §4.5 step 2): provision a member (SUBJECT) login bound to a subject. Fails when
     * the email is already allowlisted (an email is one `users` doc — one person cannot be both
     * operator and subject on the same address), when the subject is ARCHIVED, or when it has no
     * handle yet (the handle IS the member's host — a binding without one is unreachable).
     */
    fun createSubjectLogin(
        email: String,
        subject: Subject,
        addedBy: String?,
    ): Either<DomainError, User> {
        val normalized = email.trim().lowercase()
        if (!EMAIL_REGEX.matches(normalized))
            return DomainError.Invalid("A valid email is required").left()
        users.findByEmail(normalized)?.let {
            return DomainError.Invalid("$normalized is already allowlisted (${it.role})").left()
        }
        if (subject.status == SubjectStatus.ARCHIVED)
            return DomainError.Invalid(
                    "${subject.displayName} is archived — reactivate before binding a login"
                )
                .left()
        if (subject.handle.isNullOrBlank())
            return DomainError.Invalid(
                    "${subject.displayName} needs a handle first — the handle is the member's host"
                )
                .left()
        val user =
            User(
                email = normalized,
                role = Role.SUBJECT,
                active = true,
                subjectId = subject.id,
                addedBy = addedBy,
                addedAt = Instant.now(),
            )
        users.upsert(user)
        return user.right()
    }

    fun upsert(
        email: String,
        role: Role,
        addedBy: String?,
        active: Boolean = true,
        subjectId: String? = null,
    ) {
        val existing = users.findByEmail(email)
        users.upsert(
            User(
                email = email,
                role = role,
                active = active,
                // The SUBJECT binding is immutable after creation (LLD §4.1) — an existing
                // binding always wins over the caller's value.
                subjectId = existing?.subjectId ?: subjectId,
                addedBy = existing?.addedBy ?: addedBy,
                addedAt = existing?.addedAt ?: Instant.now(),
            ),
        )
    }

    fun setActive(email: String, active: Boolean) {
        val existing = users.findByEmail(email) ?: return
        users.upsert(existing.copy(active = active))
    }

    fun remove(email: String) = users.delete(email)

    companion object {
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

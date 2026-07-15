package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.User
import ai.vishwakarma.labelling.persistence.UserRepository
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

    fun list(): List<User> = users.findAll()

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
}

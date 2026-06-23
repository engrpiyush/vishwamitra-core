package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.User
import ai.vishwakarma.labelling.persistence.UserRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Allowlist management + the role lookup used during OAuth login. Only active users have a role;
 * an inactive or unknown email resolves to null → login denied.
 */
@Service
class UserService(private val users: UserRepository) {

    /** Role for an allowlisted, active user; null if absent or deactivated (→ deny). */
    fun roleFor(email: String): Role? = users.findByEmail(email)?.takeIf { it.active }?.role

    fun list(): List<User> = users.findAll()

    fun upsert(email: String, role: Role, addedBy: String?, active: Boolean = true) {
        val existing = users.findByEmail(email)
        users.upsert(
            User(
                email = email,
                role = role,
                active = active,
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

package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * An allowlisted user. Document id in the `users` collection is the email. Identity comes from
 * Google OAuth; role + active flag come from this allowlist.
 */
data class User(
    val email: String,
    val role: Role,
    val active: Boolean = true,
    val addedBy: String? = null,
    val addedAt: Instant? = null,
)

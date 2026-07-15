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
    /**
     * The SUBJECT binding (VA-29, LLD §4.1): required iff [role] == [Role.SUBJECT], immutable after
     * creation. Ties this login to exactly one Subject; a SUBJECT row without it is misprovisioned
     * and login fails closed.
     */
    val subjectId: String? = null,
    val addedBy: String? = null,
    val addedAt: Instant? = null,
)

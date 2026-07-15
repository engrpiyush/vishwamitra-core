package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * `wall_attempts/{bucketKey}` (LLD §5.1, §6.3) — rate-limit counters for the guest wall; `bucketKey
 * = sha256(subjectId + ":" + ip)[:16]`. Rows are TTL-cleaned by the sweeper.
 */
data class WallAttempt(
    val bucketKey: String,
    val count: Int = 0,
    val windowStartsAt: Instant? = null,
    val lockedUntil: Instant? = null,
)

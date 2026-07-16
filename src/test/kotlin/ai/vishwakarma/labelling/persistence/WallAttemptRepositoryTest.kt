package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.WallAttempt
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The pure §6.3 bucket step — the Firestore transaction just persists what this decides. */
class WallAttemptRepositoryTest {

    private val lockout = Duration.ofMinutes(15)
    private val t0 = Instant.parse("2026-07-16T10:00:00Z")

    private fun step(current: WallAttempt?, now: Instant): WallAttempt =
        WallAttemptRepository.decide("bucket", current, 5, lockout, now)

    @Test
    fun `five attempts pass, the sixth starts the lockout`() {
        var bucket: WallAttempt? = null
        repeat(5) {
            bucket = step(bucket, t0.plusSeconds(it.toLong()))
            assertNull(bucket!!.lockedUntil, "attempt ${it + 1} should be allowed")
        }
        bucket = step(bucket, t0.plusSeconds(10))
        assertEquals(t0.plusSeconds(10).plus(lockout), bucket!!.lockedUntil)
    }

    @Test
    fun `a stale window resets the count`() {
        var bucket: WallAttempt? = null
        repeat(5) { bucket = step(bucket, t0) }
        // 61s later the window has rolled — the same bucket allows again.
        bucket = step(bucket, t0.plusSeconds(61))
        assertNull(bucket!!.lockedUntil)
        assertEquals(1, bucket!!.count)
    }

    @Test
    fun `an active lockout carries over untouched and expires on schedule`() {
        var bucket: WallAttempt? = null
        repeat(6) { bucket = step(bucket, t0) }
        assertNotNull(bucket!!.lockedUntil)
        // Mid-lockout: denied, and the lockout end never extends.
        val during = step(bucket, t0.plusSeconds(600))
        assertEquals(bucket!!.lockedUntil, during.lockedUntil)
        // Past the lockout: a fresh window, first attempt allowed.
        val after = step(during, t0.plus(lockout).plusSeconds(1))
        assertNull(after.lockedUntil)
        assertEquals(1, after.count)
    }
}

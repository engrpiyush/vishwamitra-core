package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.WallAttempt
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Repository

/** `wall_attempts` collection — guest-wall rate-limit buckets (LLD §5.1/§6.3). */
@Repository
class WallAttemptRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun find(bucketKey: String): WallAttempt? =
        col.document(bucketKey).get().await().takeIf { it.exists() }?.toAttempt()

    fun save(attempt: WallAttempt) {
        col.document(attempt.bucketKey).set(attempt.toMap()).await()
    }

    fun delete(bucketKey: String) {
        col.document(bucketKey).delete().await()
    }

    /**
     * The §6.3 rate-limit gate, Firestore-transactional so the limit holds across app instances
     * (Cloud Run is multi-instance — in-memory counters don't). False = locked out (either an
     * active lockout, or this attempt tripped the per-minute limit and started one).
     */
    fun tryAttempt(bucketKey: String, limitPerMinute: Int, lockout: Duration): Boolean =
        db.runTransaction { tx ->
                val ref = col.document(bucketKey)
                val current = tx.get(ref).await().takeIf { it.exists() }?.toAttempt()
                val next = decide(bucketKey, current, limitPerMinute, lockout, Instant.now())
                tx.set(ref, next.toMap())
                next.lockedUntil == null
            }
            .await()

    private fun WallAttempt.toMap(): Map<String, Any?> =
        mapOf(
            "count" to count,
            "windowStartsAt" to windowStartsAt.toTimestamp(),
            "lockedUntil" to lockedUntil.toTimestamp(),
        )

    private fun DocumentSnapshot.toAttempt(): WallAttempt =
        WallAttempt(
            bucketKey = id,
            count = getLong("count")?.toInt() ?: 0,
            windowStartsAt = instant("windowStartsAt"),
            lockedUntil = instant("lockedUntil"),
        )

    companion object {
        const val COLLECTION = "wall_attempts"

        /**
         * Pure §6.3 bucket step (unit-tested; the transaction above just persists it): an active
         * lockout carries over untouched; a stale window resets the count; the (limit+1)th attempt
         * inside a window starts the lockout; otherwise the attempt is counted and allowed. The
         * returned row's `lockedUntil == null` IS the allow signal.
         */
        fun decide(
            bucketKey: String,
            current: WallAttempt?,
            limitPerMinute: Int,
            lockout: Duration,
            now: Instant,
        ): WallAttempt {
            val locked = current?.lockedUntil?.takeIf { it.isAfter(now) }
            if (locked != null) return current
            val windowStart = current?.windowStartsAt?.takeIf { it.isAfter(now.minus(WINDOW)) }
            val count = if (windowStart != null) current.count else 0
            return if (count >= limitPerMinute) {
                WallAttempt(bucketKey, count, windowStart ?: now, now.plus(lockout))
            } else {
                WallAttempt(bucketKey, count + 1, windowStart ?: now, null)
            }
        }

        /** The §6.3 rate window. */
        private val WINDOW: Duration = Duration.ofSeconds(60)
    }
}

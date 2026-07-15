package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.WallAttempt
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
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
    }
}

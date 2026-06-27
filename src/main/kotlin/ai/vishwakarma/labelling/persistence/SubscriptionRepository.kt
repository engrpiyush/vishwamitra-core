package ai.vishwakarma.labelling.persistence

import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * `coming_soon_subscriptions` collection — email capture from the public coming-soon page. Kept
 * separate from the labelling collections. Document id = email, so re-subscribing is idempotent.
 */
@Repository
class SubscriptionRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun exists(email: String): Boolean = col.document(email).get().await().exists()

    fun save(email: String, source: String) {
        col.document(email)
            .set(
                mapOf(
                    "email" to email,
                    "source" to source,
                    "createdAt" to Instant.now().toTimestamp(),
                ),
            )
            .await()
    }

    companion object {
        const val COLLECTION = "coming_soon_subscriptions"
    }
}

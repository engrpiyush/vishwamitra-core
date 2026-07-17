package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.MailCounters
import com.google.cloud.Timestamp
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import org.springframework.stereotype.Repository

/**
 * Mail bookkeeping (VA-41, LLD §11): the app-level daily send counter (`mail_counters/{date}`) that
 * makes quota exhaustion structurally impossible, and the poker's one-digest-per-subject- per-day
 * marks (`mail_digests/{date}_{subjectId}`).
 */
@Repository
class MailBookkeepingRepository(private val db: Firestore) {

    /**
     * Transactionally count a send against [date]'s ceiling. False = the cap is hit (nothing
     * incremented) — the caller skips the send loudly.
     */
    fun countSendIfBelow(date: String, cap: Int): Boolean =
        db.runTransaction { tx ->
                val ref = db.collection(COUNTERS).document(date)
                val current = tx.get(ref).get().getLong("count") ?: 0L
                if (current >= cap) {
                    false
                } else {
                    tx.set(
                        ref,
                        mapOf("count" to current + 1, "updatedAt" to Timestamp.now()),
                    )
                    true
                }
            }
            .await()

    /** VA-68 (§14.1): count a transport failure on the day's row (the send was admitted above). */
    fun markSendFailed(date: String) {
        col(date).set(mapOf("failed" to FieldValue.increment(1)), SetOptions.merge()).await()
    }

    /** VA-68 (§14.1): count a send refused at the daily cap. */
    fun markSendSkipped(date: String) {
        col(date).set(mapOf("skipped" to FieldValue.increment(1)), SetOptions.merge()).await()
    }

    /** The day's mail counters — zeros when the doc doesn't exist yet (VA-68 panel read). */
    fun counters(date: String): MailCounters {
        val doc = col(date).get().await()
        if (!doc.exists()) return MailCounters()
        return MailCounters(
            counted = doc.getLong("count") ?: 0,
            failed = doc.getLong("failed") ?: 0,
            skippedCap = doc.getLong("skipped") ?: 0,
        )
    }

    private fun col(date: String) = db.collection(COUNTERS).document(date)

    fun digestSent(date: String, subjectId: String): Boolean =
        db.collection(DIGESTS).document("${date}_$subjectId").get().await().exists()

    fun markDigest(date: String, subjectId: String, template: String) {
        db.collection(DIGESTS)
            .document("${date}_$subjectId")
            .set(mapOf("template" to template, "at" to Timestamp.now()))
            .await()
    }

    /**
     * The provisioning-request nag cap (LLD §8.3: one email per subject per day, however many
     * guests hit the request wall). Separate doc id suffix so it never collides with — or
     * suppresses — the sweep digest mark above.
     */
    fun provisioningRequestSent(date: String, subjectId: String): Boolean =
        db.collection(DIGESTS).document("${date}_${subjectId}_provreq").get().await().exists()

    fun markProvisioningRequest(date: String, subjectId: String) {
        db.collection(DIGESTS)
            .document("${date}_${subjectId}_provreq")
            .set(mapOf("template" to "provisioning-request", "at" to Timestamp.now()))
            .await()
    }

    companion object {
        const val COUNTERS = "mail_counters"
        const val DIGESTS = "mail_digests"
    }
}

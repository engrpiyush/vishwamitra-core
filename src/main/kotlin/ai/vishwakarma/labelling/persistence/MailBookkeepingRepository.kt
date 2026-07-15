package ai.vishwakarma.labelling.persistence

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
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

    fun digestSent(date: String, subjectId: String): Boolean =
        db.collection(DIGESTS).document("${date}_$subjectId").get().await().exists()

    fun markDigest(date: String, subjectId: String, template: String) {
        db.collection(DIGESTS)
            .document("${date}_$subjectId")
            .set(mapOf("template" to template, "at" to Timestamp.now()))
            .await()
    }

    companion object {
        const val COUNTERS = "mail_counters"
        const val DIGESTS = "mail_digests"
    }
}

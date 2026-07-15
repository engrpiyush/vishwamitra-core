package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.PolicyAcceptance
import ai.vishwakarma.labelling.domain.TermsAcceptance
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** `terms_acceptances` collection — doc id = the SUBJECT login email (LLD §5.1, §13.2). */
@Repository
class TermsAcceptanceRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun find(email: String): TermsAcceptance? =
        col.document(email).get().await().takeIf { it.exists() }?.toRecord()

    fun save(record: TermsAcceptance) {
        col.document(record.email).set(record.toMap()).await()
    }

    private fun TermsAcceptance.toMap(): Map<String, Any?> =
        mapOf(
            "acceptances" to
                acceptances.mapValues { (_, a) ->
                    mapOf("version" to a.version, "at" to a.at.toTimestamp())
                },
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toRecord(): TermsAcceptance {
        val raw = get("acceptances") as? Map<String, Map<String, Any?>> ?: emptyMap()
        val acceptances =
            raw.mapNotNull { (policy, fields) ->
                val version = fields["version"] as? String ?: return@mapNotNull null
                val at =
                    (fields["at"] as? Timestamp)?.let {
                        Instant.ofEpochSecond(it.seconds, it.nanos.toLong())
                    } ?: return@mapNotNull null
                policy to PolicyAcceptance(version, at)
            }
        return TermsAcceptance(email = id, acceptances = acceptances.toMap())
    }

    companion object {
        const val COLLECTION = "terms_acceptances"
    }
}

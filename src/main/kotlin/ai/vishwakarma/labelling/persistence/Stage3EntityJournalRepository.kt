package ai.vishwakarma.labelling.persistence

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * One entity merge/split audit row (`stage3_entity_journal`, LLD §9.5) — human repair of the
 * self-grown canon is journaled, never silent. [affectedSubjectIds] records whose MATCH blocking
 * keys went stale (the ticket's re-run suggestion hook; no auto re-run).
 */
data class Stage3EntityJournalEntry(
    val id: String?,
    /** MERGE or SPLIT. */
    val action: String,
    val fromEntityId: String,
    val fromName: String?,
    val entityType: String?,
    /** MERGE only: the surviving target. */
    val intoEntityId: String?,
    val intoName: String?,
    val actor: String?,
    val at: Instant?,
    val mentionsRewired: Int,
    val affectedSubjectIds: List<String>,
    /** Human-readable redistribution notes (split: "surface → TYPE|key"). */
    val details: List<String> = emptyList(),
)

@Repository
class Stage3EntityJournalRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun record(entry: Stage3EntityJournalEntry): String {
        val ref = col.document()
        ref.set(entry.toMap()).await()
        return ref.id
    }

    /** Most-recent-first read for the entity browser's audit panel (VA-25). */
    fun list(limit: Int = 100): List<Stage3EntityJournalEntry> =
        col.orderBy("at", Query.Direction.DESCENDING).limit(limit).get().await().documents.map {
            it.toEntry()
        }

    fun findByEntity(entityId: String): List<Stage3EntityJournalEntry> =
        col.whereEqualTo("fromEntityId", entityId).get().await().documents.map { it.toEntry() }

    private fun Stage3EntityJournalEntry.toMap(): Map<String, Any?> =
        mapOf(
            "action" to action,
            "fromEntityId" to fromEntityId,
            "fromName" to fromName,
            "entityType" to entityType,
            "intoEntityId" to intoEntityId,
            "intoName" to intoName,
            "actor" to actor,
            "at" to at.toTimestamp(),
            "mentionsRewired" to mentionsRewired,
            "affectedSubjectIds" to affectedSubjectIds,
            "details" to details,
        )

    private fun DocumentSnapshot.toEntry(): Stage3EntityJournalEntry =
        Stage3EntityJournalEntry(
            id = id,
            action = getString("action") ?: "",
            fromEntityId = getString("fromEntityId") ?: "",
            fromName = getString("fromName"),
            entityType = getString("entityType"),
            intoEntityId = getString("intoEntityId"),
            intoName = getString("intoName"),
            actor = getString("actor"),
            at = instant("at"),
            mentionsRewired = (getLong("mentionsRewired") ?: 0L).toInt(),
            affectedSubjectIds = stringList("affectedSubjectIds"),
            details = stringList("details"),
        )

    private fun DocumentSnapshot.stringList(field: String): List<String> =
        (get(field) as? List<*>).orEmpty().mapNotNull { it as? String }

    companion object {
        const val COLLECTION = "stage3_entity_journal"
    }
}

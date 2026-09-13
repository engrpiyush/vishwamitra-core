package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.SetOptions
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class SubjectRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Subject? =
        col.document(id).get().await().takeIf { it.exists() }?.toSubject()

    fun findAll(): List<Subject> =
        col.orderBy("updatedAt", Query.Direction.DESCENDING).get().await().documents.map {
            it.toSubject()
        }

    /** Handle → subject (VA-30 host routing). Handles are unique via the sentinel doc (VA-31). */
    fun findByHandle(handle: String): Subject? =
        col.whereEqualTo("handle", handle)
            .limit(1)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.toSubject()

    fun save(subject: Subject) {
        col.document(subject.id).set(subject.toMap()).await()
    }

    /**
     * VA-31 (LLD §4.5): create a handled subject atomically with its `handles/{handle}` sentinel —
     * Firestore has no unique constraint, the sentinel doc IS the constraint. Returns false when
     * the handle is already claimed by a different subject (nothing written).
     */
    fun createWithHandle(subject: Subject): Boolean {
        val handle = requireNotNull(subject.handle) { "createWithHandle needs a handle" }
        return db.runTransaction { tx ->
                val sentinelRef = db.collection(HANDLES_COLLECTION).document(handle)
                val sentinel = tx.get(sentinelRef).get()
                if (sentinel.exists() && sentinel.getString("subjectId") != subject.id) {
                    false
                } else {
                    tx.set(
                        sentinelRef,
                        mapOf("subjectId" to subject.id, "createdAt" to Timestamp.now()),
                    )
                    tx.set(col.document(subject.id), subject.toMap())
                    true
                }
            }
            .await()
    }

    /** The subjectId holding [handle]'s uniqueness sentinel, or null (backfill check). */
    fun sentinelFor(handle: String): String? =
        db.collection(HANDLES_COLLECTION).document(handle).get().await().getString("subjectId")

    /**
     * Claim [handle] for an existing subject (first-time handle assignment — handles are immutable
     * once set, D6). Idempotent for the same subject; false when another subject holds the
     * sentinel.
     */
    fun claimHandle(id: String, handle: String): Boolean =
        db.runTransaction { tx ->
                val sentinelRef = db.collection(HANDLES_COLLECTION).document(handle)
                val sentinel = tx.get(sentinelRef).get()
                if (sentinel.exists() && sentinel.getString("subjectId") != id) {
                    false
                } else {
                    tx.set(sentinelRef, mapOf("subjectId" to id, "createdAt" to Timestamp.now()))
                    tx.set(
                        col.document(id),
                        mapOf(
                            "handle" to handle,
                            "updatedAt" to Instant.now().toTimestamp(),
                        ),
                        SetOptions.merge(),
                    )
                    true
                }
            }
            .await()

    fun delete(id: String) {
        // Free the handle sentinel with the subject doc — a deleted subject releases its handle.
        val handle = findById(id)?.handle
        val batch = db.batch()
        handle?.let { batch.delete(db.collection(HANDLES_COLLECTION).document(it)) }
        batch.delete(col.document(id))
        batch.commit().await()
    }

    private fun Subject.toMap(): Map<String, Any?> =
        mapOf(
            "displayName" to displayName,
            "handle" to handle,
            "contactEmail" to contactEmail,
            "notes" to notes,
            "status" to status.name,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    private fun DocumentSnapshot.toSubject(): Subject =
        Subject(
            id = id,
            displayName = getString("displayName") ?: "",
            handle = getString("handle"),
            contactEmail = getString("contactEmail"),
            notes = getString("notes") ?: "",
            status = SubjectStatus.fromOrNull(getString("status")) ?: SubjectStatus.ACTIVE,
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            updatedAt = instant("updatedAt"),
        )

    companion object {
        const val COLLECTION = "subjects"
        /** Handle-uniqueness sentinels: `handles/{handle}` → `{subjectId}` (LLD §4.5). */
        const val HANDLES_COLLECTION = "handles"
    }
}

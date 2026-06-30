package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
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

    fun save(subject: Subject) {
        col.document(subject.id).set(subject.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun Subject.toMap(): Map<String, Any?> =
        mapOf(
            "displayName" to displayName,
            "handle" to handle,
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
            notes = getString("notes") ?: "",
            status = SubjectStatus.fromOrNull(getString("status")) ?: SubjectStatus.ACTIVE,
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            updatedAt = instant("updatedAt"),
        )

    companion object {
        const val COLLECTION = "subjects"
    }
}

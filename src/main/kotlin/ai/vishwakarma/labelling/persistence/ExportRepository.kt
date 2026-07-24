package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.domain.ExportRecord
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class ExportRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): ExportRecord? =
        col.document(id).get().await().takeIf { it.exists() }?.toRecord()

    fun findAll(): List<ExportRecord> =
        col.orderBy("createdAt", Query.Direction.DESCENDING).get().await().documents.map {
            it.toRecord()
        }

    fun save(record: ExportRecord) {
        col.document(record.id).set(record.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun ExportRecord.toMap(): Map<String, Any?> =
        mapOf(
            "kind" to kind.name,
            "gcsUri" to gcsUri,
            "exampleIds" to exampleIds,
            "count" to count,
            "subjectTag" to subjectTag,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toRecord(): ExportRecord =
        ExportRecord(
            id = id,
            kind =
                runCatching { ExportKind.valueOf(getString("kind") ?: "SFT") }
                    .getOrDefault(ExportKind.SFT),
            gcsUri = getString("gcsUri") ?: "",
            exampleIds = (get("exampleIds") as? List<String>) ?: emptyList(),
            count = (getLong("count") ?: 0L).toInt(),
            subjectTag = getString("subjectTag"),
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
        )

    companion object {
        const val COLLECTION = "exports"
    }
}

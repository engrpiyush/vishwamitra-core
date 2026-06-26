package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.domain.ImportError
import ai.vishwakarma.labelling.domain.ImportRecord
import ai.vishwakarma.labelling.domain.ValidationStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class ImportRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): ImportRecord? =
        col.document(id).get().await().takeIf { it.exists() }?.toRecord()

    fun findAll(): List<ImportRecord> =
        col.orderBy("createdAt", Query.Direction.DESCENDING).get().await().documents.map {
            it.toRecord()
        }

    fun save(record: ImportRecord) {
        col.document(record.id).set(record.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun ImportRecord.toMap(): Map<String, Any?> =
        mapOf(
            "kind" to kind.name,
            "originalFilename" to originalFilename,
            "storedObjectPath" to storedObjectPath,
            "gcsUri" to gcsUri,
            "lineCount" to lineCount,
            "status" to status.name,
            "errors" to errors.map { mapOf("line" to it.line, "message" to it.message) },
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "validatedAt" to validatedAt.toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toRecord(): ImportRecord =
        ImportRecord(
            id = id,
            kind =
                runCatching { ExportKind.valueOf(getString("kind") ?: "SFT") }
                    .getOrDefault(ExportKind.SFT),
            originalFilename = getString("originalFilename") ?: "",
            storedObjectPath = getString("storedObjectPath") ?: "",
            gcsUri = getString("gcsUri") ?: "",
            lineCount = (getLong("lineCount") ?: 0L).toInt(),
            status =
                runCatching { ValidationStatus.valueOf(getString("status") ?: "PENDING") }
                    .getOrDefault(ValidationStatus.PENDING),
            errors =
                ((get("errors") as? List<Map<String, Any?>>) ?: emptyList()).map {
                    ImportError(
                        line = (it["line"] as? Number)?.toInt() ?: 0,
                        message = it["message"] as? String ?: "",
                    )
                },
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            validatedAt = instant("validatedAt"),
        )

    companion object {
        const val COLLECTION = "imports"
    }
}

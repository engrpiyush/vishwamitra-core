package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.StageConfigDoc
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** The per-stage config-override documents (LLD §14A.5), one doc per [StageConfigDoc.stage]. */
@Repository
class StageConfigRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    @Suppress("UNCHECKED_CAST")
    fun find(stageId: String): StageConfigDoc {
        val snap = col.document(stageId).get().await()
        if (!snap.exists()) return StageConfigDoc(stage = stageId)
        return StageConfigDoc(
            stage = stageId,
            overrides = snap.get("overrides") as? Map<String, Any?> ?: emptyMap(),
            version = snap.getLong("version")?.toInt() ?: 0,
            updatedBy = snap.getString("updatedBy"),
            updatedAt = snap.instant("updatedAt"),
        )
    }

    fun save(doc: StageConfigDoc) {
        col.document(doc.stage)
            .set(
                mapOf(
                    "overrides" to doc.overrides,
                    "version" to doc.version,
                    "updatedBy" to doc.updatedBy,
                    "updatedAt" to (doc.updatedAt ?: Instant.now()).toTimestamp(),
                )
            )
            .await()
    }

    fun delete(stageId: String) {
        col.document(stageId).delete().await()
    }

    companion object {
        const val COLLECTION = "stage_config"
    }
}

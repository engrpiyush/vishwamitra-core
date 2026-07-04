package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.ExtractionPrompt
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class ExtractionPromptRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun findById(id: String): ExtractionPrompt? =
        col.document(id).get().await().takeIf { it.exists() }?.toPrompt()

    fun findAll(): List<ExtractionPrompt> =
        col.get().await().documents.map { it.toPrompt() }.sortedBy { it.id }

    fun save(prompt: ExtractionPrompt) {
        col.document(prompt.id).set(prompt.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun ExtractionPrompt.toMap(): Map<String, Any?> =
        mapOf(
            "instructions" to instructions,
            "version" to version,
            "updatedBy" to updatedBy,
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    private fun DocumentSnapshot.toPrompt(): ExtractionPrompt =
        ExtractionPrompt(
            id = id,
            instructions = getString("instructions") ?: "",
            version = getLong("version")?.toInt() ?: 0,
            updatedBy = getString("updatedBy"),
            updatedAt = instant("updatedAt"),
        )

    companion object {
        const val COLLECTION = "extraction_prompts"
    }
}

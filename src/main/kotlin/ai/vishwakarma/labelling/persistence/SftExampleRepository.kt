package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.ExampleSource
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ReviewComment
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class SftExampleRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): SftExample? =
        col.document(id).get().await().takeIf { it.exists() }?.toExample()

    fun findAll(): List<SftExample> =
        col.orderBy("updatedAt", Query.Direction.DESCENDING).get().await().documents.map {
            it.toExample()
        }

    fun findByStatus(status: ExampleStatus): List<SftExample> =
        col.whereEqualTo("status", status.name)
            .get()
            .await()
            .documents
            .map { it.toExample() }
            .sortedByDescending { it.updatedAt }

    fun save(example: SftExample) {
        col.document(example.id).set(example.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun SftExample.toMap(): Map<String, Any?> =
        mapOf(
            "tags" to tags.toTagMap(),
            "turns" to
                turns.map {
                    mapOf(
                        "role" to it.role.name,
                        "kind" to it.kind.name,
                        "text" to it.text,
                        "toolName" to it.toolName,
                        "argsJson" to it.argsJson,
                        "resultJson" to it.resultJson,
                    )
                },
            "status" to status.name,
            "source" to source.name,
            "llmModel" to llmModel,
            "scenarioId" to scenarioId,
            "stamp" to stamp?.toStampMap(),
            "archivedReason" to archivedReason,
            "reviewComments" to
                reviewComments.map {
                    mapOf("by" to it.by, "text" to it.text, "at" to it.at.toTimestamp())
                },
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
            "exportedIn" to exportedIn,
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toExample(): SftExample {
        val tagsMap = get("tags") as? Map<String, Any?> ?: emptyMap()
        val turnsList = get("turns") as? List<Map<String, Any?>> ?: emptyList()
        val commentsList = get("reviewComments") as? List<Map<String, Any?>> ?: emptyList()
        return SftExample(
            id = id,
            tags = tagsMap.toExampleTags(),
            turns =
                turnsList.map {
                    Turn(
                        role =
                            runCatching { TurnRole.valueOf(it["role"] as? String ?: "USER") }
                                .getOrDefault(TurnRole.USER),
                        kind =
                            runCatching { TurnKind.valueOf(it["kind"] as? String ?: "TEXT") }
                                .getOrDefault(TurnKind.TEXT),
                        text = it["text"] as? String ?: "",
                        toolName = it["toolName"] as? String,
                        argsJson = it["argsJson"] as? String,
                        resultJson = it["resultJson"] as? String,
                    )
                },
            status =
                runCatching { ExampleStatus.valueOf(getString("status") ?: "DRAFT") }
                    .getOrDefault(ExampleStatus.DRAFT),
            source =
                runCatching { ExampleSource.valueOf(getString("source") ?: "MANUAL") }
                    .getOrDefault(ExampleSource.MANUAL),
            llmModel = getString("llmModel"),
            scenarioId = getString("scenarioId"),
            stamp = (get("stamp") as? Map<String, Any?>)?.toStage4Stamp(),
            archivedReason = getString("archivedReason"),
            reviewComments =
                commentsList.map {
                    ReviewComment(
                        by = it["by"] as? String,
                        text = it["text"] as? String ?: "",
                        at =
                            (it["at"] as? com.google.cloud.Timestamp)?.let { ts ->
                                Instant.ofEpochSecond(ts.seconds, ts.nanos.toLong())
                            } ?: Instant.now(),
                    )
                },
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            updatedAt = instant("updatedAt"),
            exportedIn = (get("exportedIn") as? List<String>) ?: emptyList(),
        )
    }

    companion object {
        const val COLLECTION = "sft_examples"
    }
}

package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.DpoPair
import ai.vishwakarma.labelling.domain.DpoSource
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ReviewComment
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class DpoPairRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): DpoPair? =
        col.document(id).get().await().takeIf { it.exists() }?.toPair()

    fun findAll(): List<DpoPair> =
        col.orderBy("updatedAt", Query.Direction.DESCENDING).get().await().documents.map {
            it.toPair()
        }

    fun findByStatus(status: ExampleStatus): List<DpoPair> =
        col.whereEqualTo("status", status.name)
            .get()
            .await()
            .documents
            .map { it.toPair() }
            .sortedByDescending { it.updatedAt }

    fun save(pair: DpoPair) {
        col.document(pair.id).set(pair.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun DpoPair.toMap(): Map<String, Any?> =
        mapOf(
            "promptTurns" to
                promptTurns.map {
                    mapOf(
                        "role" to it.role.name,
                        "kind" to it.kind.name,
                        "text" to it.text,
                        "toolName" to it.toolName,
                        "argsJson" to it.argsJson,
                        "resultJson" to it.resultJson
                    )
                },
            "chosenText" to chosenText,
            "rejectedText" to rejectedText,
            "tags" to tags.toTagMap(),
            "status" to status.name,
            "source" to source.name,
            "fromSftId" to fromSftId,
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
    private fun DocumentSnapshot.toPair(): DpoPair {
        val tagsMap = get("tags") as? Map<String, Any?> ?: emptyMap()
        val promptList = get("promptTurns") as? List<Map<String, Any?>> ?: emptyList()
        val commentsList = get("reviewComments") as? List<Map<String, Any?>> ?: emptyList()
        return DpoPair(
            id = id,
            promptTurns =
                promptList.map {
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
            chosenText = getString("chosenText") ?: "",
            rejectedText = getString("rejectedText") ?: "",
            tags = tagsMap.toExampleTags(),
            status =
                runCatching { ExampleStatus.valueOf(getString("status") ?: "DRAFT") }
                    .getOrDefault(ExampleStatus.DRAFT),
            source =
                runCatching { DpoSource.valueOf(getString("source") ?: "MANUAL") }
                    .getOrDefault(DpoSource.MANUAL),
            fromSftId = getString("fromSftId"),
            stamp = (get("stamp") as? Map<String, Any?>)?.toStage4Stamp(),
            archivedReason = getString("archivedReason"),
            reviewComments =
                commentsList.map {
                    ReviewComment(
                        by = it["by"] as? String,
                        text = it["text"] as? String ?: "",
                        at =
                            (it["at"] as? Timestamp)?.let { ts ->
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
        const val COLLECTION = "dpo_pairs"
    }
}

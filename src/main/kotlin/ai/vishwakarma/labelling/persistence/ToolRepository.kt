package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Tool
import ai.vishwakarma.labelling.domain.ToolParam
import ai.vishwakarma.labelling.domain.ToolStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class ToolRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Tool? =
        col.document(id).get().await().takeIf { it.exists() }?.toTool()

    fun findAll(): List<Tool> = col.get().await().documents.map { it.toTool() }.sortedBy { it.name }

    fun save(tool: Tool) {
        col.document(tool.id).set(tool.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun Tool.toMap(): Map<String, Any?> =
        mapOf(
            "name" to name,
            "description" to description,
            "params" to
                params.map {
                    mapOf(
                        "name" to it.name,
                        "type" to it.type,
                        "required" to it.required,
                        "desc" to it.desc
                    )
                },
            "status" to status.name,
            "updatedBy" to updatedBy,
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toTool(): Tool =
        Tool(
            id = id,
            name = getString("name") ?: "",
            description = getString("description") ?: "",
            params =
                (get("params") as? List<Map<String, Any?>> ?: emptyList()).map {
                    ToolParam(
                        name = it["name"] as? String ?: "",
                        type = it["type"] as? String ?: "string",
                        required = it["required"] as? Boolean ?: true,
                        desc = it["desc"] as? String ?: "",
                    )
                },
            status =
                runCatching { ToolStatus.valueOf(getString("status") ?: "ACTIVE") }
                    .getOrDefault(ToolStatus.ACTIVE),
            updatedBy = getString("updatedBy"),
            updatedAt = instant("updatedAt"),
        )

    companion object {
        const val COLLECTION = "tools"
    }
}

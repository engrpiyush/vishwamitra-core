package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Scenario
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class ScenarioRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Scenario? =
        col.document(id).get().await().takeIf { it.exists() }?.toScenario()

    fun findAll(): List<Scenario> =
        col.get().await().documents.map { it.toScenario() }.sortedBy { it.title }

    fun save(scenario: Scenario) {
        col.document(scenario.id).set(scenario.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun Scenario.toMap(): Map<String, Any?> =
        mapOf(
            "title" to title,
            "description" to description,
            "skill" to skill,
            "intent" to intent,
            "promptTemplate" to promptTemplate,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
        )

    private fun DocumentSnapshot.toScenario(): Scenario =
        Scenario(
            id = id,
            title = getString("title") ?: "",
            description = getString("description") ?: "",
            skill = getString("skill"),
            intent = getString("intent"),
            promptTemplate = getString("promptTemplate") ?: "",
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
        )

    companion object {
        const val COLLECTION = "scenarios"
    }
}

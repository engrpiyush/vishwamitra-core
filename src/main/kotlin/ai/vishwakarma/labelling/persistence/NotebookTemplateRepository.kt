package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.DeclaredType
import ai.vishwakarma.labelling.domain.FormatSpec
import ai.vishwakarma.labelling.domain.NotebookTemplate
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class NotebookTemplateRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): NotebookTemplate? =
        col.document(id).get().await().takeIf { it.exists() }?.toTemplate()

    fun findAll(): List<NotebookTemplate> = col.get().await().documents.map { it.toTemplate() }

    fun save(template: NotebookTemplate) {
        col.document(template.id).set(template.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun NotebookTemplate.toMap(): Map<String, Any?> =
        mapOf(
            "category" to category,
            "title" to title,
            "formatSpec" to
                mapOf(
                    "turnShape" to formatSpec.turnShape,
                    "intent" to formatSpec.intent,
                    "personaLens" to formatSpec.personaLens,
                    "expectedBehaviours" to formatSpec.expectedBehaviours,
                    "openingProbe" to formatSpec.openingProbe,
                    "failureModes" to formatSpec.failureModes,
                ),
            "promptTemplate" to promptTemplate,
            "coverageTarget" to coverageTarget,
            "facets" to facets,
            "evidenceGate" to evidenceGate,
            "requiredClaimTypes" to requiredClaimTypes.map { it.name },
            "requiredDeclaredTypes" to requiredDeclaredTypes,
            "outcomes" to outcomes,
            "version" to version,
            "migratedFrom" to migratedFrom,
            "updatedBy" to updatedBy,
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toTemplate(): NotebookTemplate {
        val spec = get("formatSpec") as? Map<String, Any?> ?: emptyMap()
        return NotebookTemplate(
            id = id,
            category = getString("category") ?: "",
            title = getString("title") ?: "",
            formatSpec =
                FormatSpec(
                    turnShape = spec["turnShape"] as? String ?: "",
                    intent = spec["intent"] as? String ?: "",
                    personaLens = spec["personaLens"] as? String ?: "",
                    expectedBehaviours =
                        (spec["expectedBehaviours"] as? List<String>) ?: emptyList(),
                    openingProbe = spec["openingProbe"] as? String ?: "",
                    failureModes = (spec["failureModes"] as? List<String>) ?: emptyList(),
                ),
            promptTemplate = getString("promptTemplate") ?: "",
            coverageTarget = getLong("coverageTarget")?.toInt() ?: 1,
            facets = getString("facets") ?: "",
            evidenceGate = getString("evidenceGate") ?: "",
            requiredClaimTypes =
                ((get("requiredClaimTypes") as? List<String>) ?: emptyList()).mapNotNull {
                    ClaimType.fromOrNull(it)
                },
            // Unrecognised keys are dropped on read: a gate naming a declared type this build does
            // not know would otherwise silently match nothing and mute the template for everyone.
            requiredDeclaredTypes =
                ((get("requiredDeclaredTypes") as? List<String>) ?: emptyList())
                    .map { it.trim().lowercase() }
                    .filter { DeclaredType.recognises(it) },
            outcomes = (get("outcomes") as? List<String>) ?: emptyList(),
            version = getLong("version")?.toInt() ?: 1,
            migratedFrom = getString("migratedFrom"),
            updatedBy = getString("updatedBy"),
            updatedAt = instant("updatedAt"),
        )
    }

    companion object {
        const val COLLECTION = "notebook_templates"
    }
}

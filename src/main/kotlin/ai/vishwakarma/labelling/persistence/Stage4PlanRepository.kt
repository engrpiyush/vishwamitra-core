package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4Plan
import ai.vishwakarma.labelling.domain.VoicingPlan
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * `stage4_plans` (LLD §6) — doc id = [VoicingPlan.planId], the content hash, so saving an unchanged
 * plan is a no-op overwrite and re-planning never duplicates.
 */
@Repository
class Stage4PlanRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun findById(planId: String): Stage4Plan? =
        col.document(planId).get().await().takeIf { it.exists() }?.toPlan()

    fun findBySubject(subjectId: String): List<Stage4Plan> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toPlan() }
            .sortedByDescending { it.createdAt }

    fun save(plan: Stage4Plan) {
        col.document(plan.plan.planId).set(plan.toMap()).await()
    }

    fun delete(planId: String) {
        col.document(planId).delete().await()
    }

    private fun Stage4Plan.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "scoreRunId" to scoreRunId,
            "personaHash" to personaHash,
            "question" to question,
            "rowId" to plan.rowId,
            "voice" to plan.voice,
            "hedgeLevel" to plan.hedgeLevel.name,
            "constraints" to plan.constraints,
            "sourceClaimIds" to plan.sourceClaimIds,
            "category" to plan.category.name,
            "sftEligible" to plan.sftEligible,
            "templateId" to plan.templateId,
            "templateCategory" to plan.templateCategory,
            "specTitle" to plan.specTitle,
            "specIntent" to plan.specIntent,
            "specPersonaLens" to plan.specPersonaLens,
            "specFormatConstraints" to plan.specFormatConstraints,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toPlan(): Stage4Plan =
        Stage4Plan(
            subjectId = getString("subjectId") ?: "",
            scoreRunId = getString("scoreRunId"),
            personaHash = getString("personaHash"),
            question = getString("question"),
            plan =
                VoicingPlan(
                    planId = id,
                    rowId = getLong("rowId")?.toInt() ?: 0,
                    voice = getString("voice") ?: "",
                    hedgeLevel = HedgeLevel.fromOrNull(getString("hedgeLevel")) ?: HedgeLevel.GAP,
                    constraints = (get("constraints") as? List<String>) ?: emptyList(),
                    sourceClaimIds = (get("sourceClaimIds") as? List<String>) ?: emptyList(),
                    category =
                        Stage4Category.fromOrNull(getString("category")) ?: Stage4Category.QA,
                    sftEligible = getBoolean("sftEligible") ?: true,
                    templateId = getString("templateId"),
                    templateCategory = getString("templateCategory"),
                    specTitle = getString("specTitle"),
                    specIntent = getString("specIntent"),
                    specPersonaLens = getString("specPersonaLens"),
                    specFormatConstraints =
                        (get("specFormatConstraints") as? List<String>) ?: emptyList(),
                ),
            createdAt = instant("createdAt"),
        )

    companion object {
        const val COLLECTION = "stage4_plans"
    }
}

package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Advocate
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.WindowPreset
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** `advocates` collection — doc id = subjectId (one advocate per subject, LLD §5.1). */
@Repository
class AdvocateRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun find(subjectId: String): Advocate? =
        col.document(subjectId).get().await().takeIf { it.exists() }?.toAdvocate()

    fun findAll(): List<Advocate> = col.get().await().documents.map { it.toAdvocate() }

    fun save(advocate: Advocate) {
        col.document(advocate.subjectId).set(advocate.toMap()).await()
    }

    fun delete(subjectId: String) {
        col.document(subjectId).delete().await()
    }

    private fun Advocate.toMap(): Map<String, Any?> =
        mapOf(
            "state" to state.name,
            "modelUri" to modelUri,
            "windowPreset" to windowPreset?.name,
            "windowEndsAt" to windowEndsAt.toTimestamp(),
            "windowSetBy" to windowSetBy,
            "windowSetAt" to windowSetAt.toTimestamp(),
            "lastError" to lastError,
            "servingModelResource" to servingModelResource,
            "servingDeployedModelId" to servingDeployedModelId,
            "servingOperation" to servingOperation,
            "provisioningStartedAt" to provisioningStartedAt.toTimestamp(),
            "aggregateScore" to aggregateScore,
            "scoredClaimCount" to scoredClaimCount,
            "scoreComputedAt" to scoreComputedAt.toTimestamp(),
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    private fun DocumentSnapshot.toAdvocate(): Advocate =
        Advocate(
            subjectId = id,
            state = AdvocateState.fromOrNull(getString("state")) ?: AdvocateState.NOT_BUILT,
            modelUri = getString("modelUri"),
            windowPreset = WindowPreset.fromOrNull(getString("windowPreset")),
            windowEndsAt = instant("windowEndsAt"),
            windowSetBy = getString("windowSetBy"),
            windowSetAt = instant("windowSetAt"),
            lastError = getString("lastError"),
            servingModelResource = getString("servingModelResource"),
            servingDeployedModelId = getString("servingDeployedModelId"),
            servingOperation = getString("servingOperation"),
            provisioningStartedAt = instant("provisioningStartedAt"),
            aggregateScore = getDouble("aggregateScore"),
            scoredClaimCount = getLong("scoredClaimCount")?.toInt() ?: 0,
            scoreComputedAt = instant("scoreComputedAt"),
            updatedAt = instant("updatedAt"),
        )

    companion object {
        const val COLLECTION = "advocates"
    }
}

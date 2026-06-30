package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.IntakeManifest
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class IntakeManifestRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    /** One manifest per subject — the document id is the subject id. */
    fun findBySubject(subjectId: String): IntakeManifest? =
        col.document(subjectId).get().await().takeIf { it.exists() }?.toManifest()

    fun save(manifest: IntakeManifest) {
        col.document(manifest.subjectId).set(manifest.toMap()).await()
    }

    fun delete(subjectId: String) {
        col.document(subjectId).delete().await()
    }

    private fun IntakeManifest.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "assetIds" to assetIds,
            "countsByModality" to countsByModality,
            "countsByContentType" to countsByContentType,
            "consentSummary" to consentSummary,
            "sealed" to sealed,
            "sealedBy" to sealedBy,
            "sealedAt" to sealedAt.toTimestamp(),
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toManifest(): IntakeManifest =
        IntakeManifest(
            id = id,
            subjectId = getString("subjectId") ?: id,
            assetIds = (get("assetIds") as? List<String>) ?: emptyList(),
            countsByModality = intMap("countsByModality"),
            countsByContentType = intMap("countsByContentType"),
            consentSummary = intMap("consentSummary"),
            sealed = getBoolean("sealed") ?: false,
            sealedBy = getString("sealedBy"),
            sealedAt = instant("sealedAt"),
            updatedAt = instant("updatedAt"),
        )

    /** Firestore stores integers as Long; coerce a count map's values back to Int. */
    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.intMap(field: String): Map<String, Int> =
        (get(field) as? Map<String, Any?>)
            ?.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toInt() } }
            ?.toMap() ?: emptyMap()

    companion object {
        const val COLLECTION = "intake_manifests"
    }
}

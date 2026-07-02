package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.SealAction
import ai.vishwakarma.labelling.domain.SealEvent
import com.google.cloud.Timestamp
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
            "sealBlockers" to sealBlockers,
            "sealed" to sealed,
            "sealEvents" to
                sealEvents.map {
                    mapOf(
                        "action" to it.action.name,
                        "actor" to it.actor,
                        "at" to it.at.toTimestamp(),
                        "note" to it.note,
                    )
                },
            "stage2StartedAt" to stage2StartedAt.toTimestamp(),
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
            sealBlockers = (get("sealBlockers") as? List<String>) ?: emptyList(),
            sealed = getBoolean("sealed") ?: false,
            sealEvents = sealEvents(),
            stage2StartedAt = instant("stage2StartedAt"),
            updatedAt = instant("updatedAt"),
        )

    /**
     * Read the seal-event history. Tolerant: entries with an unknown action or missing timestamp
     * are dropped. Legacy docs (pre-history schema) stored a single sealedBy/sealedAt pair — when
     * the history field is absent but the doc is sealed, synthesize the equivalent SEAL event so
     * the audit trail survives the schema change.
     */
    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.sealEvents(): List<SealEvent> {
        val raw = get("sealEvents") as? List<Map<String, Any?>>
        if (raw != null)
            return raw.mapNotNull { e ->
                val action = SealAction.fromOrNull(e["action"] as? String) ?: return@mapNotNull null
                val at = (e["at"] as? Timestamp)?.toDate()?.toInstant() ?: return@mapNotNull null
                SealEvent(
                    action = action,
                    actor = e["actor"] as? String,
                    at = at,
                    note = (e["note"] as? String) ?: "",
                )
            }
        // Legacy migration: pre-history sealed docs carry sealedBy/sealedAt at the top level.
        if (getBoolean("sealed") == true) {
            val at = instant("sealedAt") ?: return emptyList()
            return listOf(
                SealEvent(
                    action = SealAction.SEAL,
                    actor = getString("sealedBy"),
                    at = at,
                    note = "(migrated from legacy sealedBy/sealedAt)",
                )
            )
        }
        return emptyList()
    }

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

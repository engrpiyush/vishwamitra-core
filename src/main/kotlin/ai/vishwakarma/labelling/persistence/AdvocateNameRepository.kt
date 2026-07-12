package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.AdvocateName
import ai.vishwakarma.labelling.domain.AdvocateRegion
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/** `advocate_names` (QA on A2) — the admin pool the deterministic A2 fallback picks from. */
@Repository
class AdvocateNameRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): AdvocateName? =
        col.document(id).get().await().takeIf { it.exists() }?.toName()

    /**
     * The whole pool in a *stable* order (name, then id) — the deterministic pick indexes into this
     * list, so its order must not depend on Firestore return order.
     */
    fun findAll(): List<AdvocateName> =
        col.get()
            .await()
            .documents
            .map { it.toName() }
            .sortedWith(compareBy({ it.name }, { it.id }))

    fun save(name: AdvocateName) {
        col.document(name.id).set(name.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun AdvocateName.toMap(): Map<String, Any?> =
        mapOf(
            "name" to name,
            "region" to region?.name,
            "gender" to gender,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
        )

    private fun DocumentSnapshot.toName(): AdvocateName =
        AdvocateName(
            id = id,
            name = getString("name") ?: "",
            region = AdvocateRegion.fromOrNull(getString("region")),
            gender = getString("gender"),
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
        )

    companion object {
        const val COLLECTION = "advocate_names"
    }
}

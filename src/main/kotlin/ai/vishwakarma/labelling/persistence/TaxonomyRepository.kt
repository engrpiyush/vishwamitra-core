package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Taxonomy
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository

@Repository
class TaxonomyRepository(private val db: Firestore) {

    private val doc
        get() = db.collection(COLLECTION).document(Taxonomy.DOC_ID)

    @Suppress("UNCHECKED_CAST")
    fun get(): Taxonomy {
        val snap = doc.get().await()
        if (!snap.exists()) return Taxonomy()
        return Taxonomy(labels = snap.get("labels") as? List<String> ?: emptyList())
    }

    fun save(taxonomy: Taxonomy) {
        doc.set(mapOf("labels" to taxonomy.labels)).await()
    }

    companion object {
        const val COLLECTION = "taxonomy"
    }
}

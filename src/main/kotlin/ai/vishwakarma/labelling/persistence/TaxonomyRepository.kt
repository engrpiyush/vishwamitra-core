package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Taxonomy
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository

@Repository
class TaxonomyRepository(private val db: Firestore) {

    private val doc get() = db.collection(COLLECTION).document(Taxonomy.DOC_ID)

    @Suppress("UNCHECKED_CAST")
    fun get(): Taxonomy {
        val snap = doc.get().await()
        if (!snap.exists()) return Taxonomy()
        return Taxonomy(
            skills = snap.get("skills") as? List<String> ?: emptyList(),
            intents = snap.get("intents") as? List<String> ?: emptyList(),
            languages = snap.get("languages") as? List<String> ?: emptyList(),
        )
    }

    fun save(taxonomy: Taxonomy) {
        doc.set(
            mapOf(
                "skills" to taxonomy.skills,
                "intents" to taxonomy.intents,
                "languages" to taxonomy.languages,
            ),
        ).await()
    }

    companion object {
        const val COLLECTION = "taxonomy"
    }
}

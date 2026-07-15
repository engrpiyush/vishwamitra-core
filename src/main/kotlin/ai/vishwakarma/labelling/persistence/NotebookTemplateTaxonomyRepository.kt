package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.NotebookTemplateTaxonomy
import ai.vishwakarma.labelling.domain.TemplateCategory
import ai.vishwakarma.labelling.domain.TemplateCategoryGroup
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository

/**
 * The notebook-template category vocabulary — the [TaxonomyRepository] idiom: one document in the
 * shared `taxonomy` collection, id = [NotebookTemplateTaxonomy.DOC_ID].
 */
@Repository
class NotebookTemplateTaxonomyRepository(private val db: Firestore) {

    private val doc
        get() =
            db.collection(TaxonomyRepository.COLLECTION).document(NotebookTemplateTaxonomy.DOC_ID)

    @Suppress("UNCHECKED_CAST")
    fun get(): NotebookTemplateTaxonomy {
        val snap = doc.get().await()
        if (!snap.exists()) return NotebookTemplateTaxonomy()
        val raw = snap.get("categories") as? List<Map<String, Any?>> ?: emptyList()
        val categories =
            raw.mapNotNull { entry ->
                val slug = entry["slug"] as? String ?: return@mapNotNull null
                val group =
                    TemplateCategoryGroup.fromOrNull(entry["group"] as? String)
                        ?: return@mapNotNull null
                TemplateCategory(
                    slug = slug,
                    name = entry["name"] as? String ?: slug,
                    group = group
                )
            }
        return NotebookTemplateTaxonomy(categories)
    }

    fun save(taxonomy: NotebookTemplateTaxonomy) {
        doc.set(
                mapOf(
                    "categories" to
                        taxonomy.categories.map {
                            mapOf("slug" to it.slug, "name" to it.name, "group" to it.group.name)
                        }
                )
            )
            .await()
    }
}

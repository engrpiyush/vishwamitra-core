package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.BaseModel
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class BaseModelRepository(private val db: Firestore) {

    private val col get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): BaseModel? =
        col.document(id).get().await().takeIf { it.exists() }?.toBaseModel()

    fun findAll(): List<BaseModel> =
        col.get().await().documents.map { it.toBaseModel() }.sortedBy { it.displayName }

    fun save(model: BaseModel) {
        col.document(model.id).set(model.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun BaseModel.toMap(): Map<String, Any?> = mapOf(
        "publisherModel" to publisherModel,
        "displayName" to displayName,
        "family" to family,
        "active" to active,
        "updatedBy" to updatedBy,
        "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
    )

    private fun DocumentSnapshot.toBaseModel(): BaseModel = BaseModel(
        id = id,
        publisherModel = getString("publisherModel") ?: "",
        displayName = getString("displayName") ?: "",
        family = getString("family") ?: "",
        active = getBoolean("active") ?: true,
        updatedBy = getString("updatedBy"),
        updatedAt = instant("updatedAt"),
    )

    companion object {
        const val COLLECTION = "base_models"
    }
}

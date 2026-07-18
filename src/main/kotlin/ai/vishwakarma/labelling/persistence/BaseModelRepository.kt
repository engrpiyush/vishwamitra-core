package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.BaseModel
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class BaseModelRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

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

    private fun BaseModel.toMap(): Map<String, Any?> =
        mapOf(
            "publisherModel" to publisherModel,
            "displayName" to displayName,
            "family" to family,
            "active" to active,
            "tunable" to tunable,
            "hostable" to hostable,
            "servingImage" to servingImage,
            "acceleratorSpec" to acceleratorSpec,
            "serveVerified" to serveVerified,
            "updatedBy" to updatedBy,
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    private fun DocumentSnapshot.toBaseModel(): BaseModel =
        BaseModel(
            id = id,
            publisherModel = getString("publisherModel") ?: "",
            displayName = getString("displayName") ?: "",
            family = getString("family") ?: "",
            active = getBoolean("active") ?: true,
            // Legacy rows (pre-tunable) default true so nothing silently disappears from the
            // picker.
            tunable = getBoolean("tunable") ?: true,
            // Legacy rows (pre-hostable) default FALSE — host capability is claimed, not assumed
            // (the seeder reconcile marks the VA-74-probed families).
            hostable = getBoolean("hostable") ?: false,
            servingImage = getString("servingImage") ?: "",
            acceleratorSpec = getString("acceleratorSpec") ?: "",
            serveVerified = getBoolean("serveVerified") ?: false,
            updatedBy = getString("updatedBy"),
            updatedAt = instant("updatedAt"),
        )

    companion object {
        const val COLLECTION = "base_models"
    }
}

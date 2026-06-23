package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.BaseKind
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.Promotion
import ai.vishwakarma.labelling.domain.TuningMethod
import ai.vishwakarma.labelling.domain.VersionStatus
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ModelVersionRepository(private val db: Firestore) {

    private val col get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): ModelVersion? =
        col.document(id).get().await().takeIf { it.exists() }?.toVersion()

    fun findAll(): List<ModelVersion> =
        col.get().await().documents.map { it.toVersion() }

    fun findByFamily(family: String): List<ModelVersion> =
        findAll().filter { it.family == family }

    fun save(version: ModelVersion) {
        col.document(version.id).set(version.toMap()).await()
    }

    private fun ModelVersion.toMap(): Map<String, Any?> = mapOf(
        "baseModelId" to baseModelId,
        "family" to family,
        "version" to version,
        "method" to method.name,
        "baseKind" to baseKind.name,
        "parentVersionId" to parentVersionId,
        "datasetExportIds" to datasetExportIds,
        "tuningJobId" to tuningJobId,
        "gcsCheckpointUri" to gcsCheckpointUri,
        "vertexModelResource" to vertexModelResource,
        "status" to status.name,
        "promotion" to promotion.name,
        "displayName" to displayName,
        "createdBy" to createdBy,
        "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toVersion(): ModelVersion = ModelVersion(
        id = id,
        baseModelId = getString("baseModelId") ?: "",
        family = getString("family") ?: "",
        version = getString("version") ?: "v1.0",
        method = runCatching { TuningMethod.valueOf(getString("method") ?: "SFT") }.getOrDefault(TuningMethod.SFT),
        baseKind = runCatching { BaseKind.valueOf(getString("baseKind") ?: "FOUNDATION") }.getOrDefault(BaseKind.FOUNDATION),
        parentVersionId = getString("parentVersionId"),
        datasetExportIds = (get("datasetExportIds") as? List<String>) ?: emptyList(),
        tuningJobId = getString("tuningJobId"),
        gcsCheckpointUri = getString("gcsCheckpointUri"),
        vertexModelResource = getString("vertexModelResource"),
        status = runCatching { VersionStatus.valueOf(getString("status") ?: "TRAINING") }.getOrDefault(VersionStatus.TRAINING),
        promotion = runCatching { Promotion.valueOf(getString("promotion") ?: "NONE") }.getOrDefault(Promotion.NONE),
        displayName = getString("displayName") ?: "",
        createdBy = getString("createdBy"),
        createdAt = instant("createdAt"),
    )

    companion object {
        const val COLLECTION = "model_versions"
    }
}

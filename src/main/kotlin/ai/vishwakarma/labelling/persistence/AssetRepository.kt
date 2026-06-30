package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SourceClass
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class AssetRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun newId(): String = col.document().id

    fun findById(id: String): Asset? =
        col.document(id).get().await().takeIf { it.exists() }?.toAsset()

    /** All assets for a subject, newest first. */
    fun findBySubject(subjectId: String): List<Asset> =
        col.whereEqualTo("subjectId", subjectId)
            .get()
            .await()
            .documents
            .map { it.toAsset() }
            .sortedByDescending { it.updatedAt }

    fun save(asset: Asset) {
        col.document(asset.id).set(asset.toMap()).await()
    }

    fun delete(id: String) {
        col.document(id).delete().await()
    }

    private fun Asset.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "title" to title,
            "modality" to modality.name,
            "sourceClass" to sourceClass.name,
            "contentType" to contentType.name,
            "relationship" to relationship.name,
            "authenticityPrior" to authenticityPrior.name,
            "authenticityPriorOverridden" to authenticityPriorOverridden,
            "storedObjectPath" to storedObjectPath,
            "gcsUri" to gcsUri,
            "externalUrl" to externalUrl,
            "originalFilename" to originalFilename,
            "mimeType" to mimeType,
            "sizeBytes" to sizeBytes,
            "checksum" to checksum,
            "sourceName" to sourceName,
            "captureDate" to captureDate.toIsoDate(),
            "claimedEventDate" to claimedEventDate.toIsoDate(),
            "consentStatus" to consentStatus.name,
            "consentDate" to consentDate.toTimestamp(),
            "consentNote" to consentNote,
            "uploadStatus" to uploadStatus.name,
            "labels" to labels,
            "notes" to notes,
            "createdBy" to createdBy,
            "createdAt" to (createdAt ?: Instant.now()).toTimestamp(),
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toAsset(): Asset {
        // contentType is the source of truth for the (legacy-tolerant) source class; fall back to
        // the stored sourceClass, then to the content type's own class.
        val contentType =
            ContentType.fromOrNull(getString("contentType")) ?: ContentType.ACCOMPLISHMENT_STORY
        return Asset(
            id = id,
            subjectId = getString("subjectId") ?: "",
            title = getString("title") ?: "",
            modality = AssetModality.fromOrNull(getString("modality")) ?: AssetModality.DOCUMENT,
            sourceClass =
                SourceClass.fromOrNull(getString("sourceClass")) ?: contentType.sourceClass,
            contentType = contentType,
            relationship =
                Relationship.fromOrNull(getString("relationship")) ?: Relationship.UNKNOWN,
            authenticityPrior =
                AuthenticityTier.fromOrNull(getString("authenticityPrior")) ?: AuthenticityTier.LOW,
            authenticityPriorOverridden = getBoolean("authenticityPriorOverridden") ?: false,
            storedObjectPath = getString("storedObjectPath"),
            gcsUri = getString("gcsUri"),
            externalUrl = getString("externalUrl"),
            originalFilename = getString("originalFilename"),
            mimeType = getString("mimeType"),
            sizeBytes = getLong("sizeBytes"),
            checksum = getString("checksum"),
            sourceName = getString("sourceName"),
            captureDate = localDate("captureDate"),
            claimedEventDate = localDate("claimedEventDate"),
            consentStatus =
                ConsentStatus.fromOrNull(getString("consentStatus")) ?: ConsentStatus.PENDING,
            consentDate = instant("consentDate"),
            consentNote = getString("consentNote"),
            uploadStatus =
                AssetUploadStatus.fromOrNull(getString("uploadStatus"))
                    ?: AssetUploadStatus.AWAITING_UPLOAD,
            labels = (get("labels") as? List<String>) ?: emptyList(),
            notes = getString("notes") ?: "",
            createdBy = getString("createdBy"),
            createdAt = instant("createdAt"),
            updatedAt = instant("updatedAt"),
        )
    }

    companion object {
        const val COLLECTION = "assets"
    }
}

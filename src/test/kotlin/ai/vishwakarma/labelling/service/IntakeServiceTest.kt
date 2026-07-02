package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SealAction
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.gcs.IntakeStorage
import ai.vishwakarma.labelling.gcs.SignedUpload
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

/** [fold] is the one Either accessor this codebase already relies on (see IntakeApiController). */
private fun <A, B> Either<A, B>.valueOrNull(): B? = fold({ null }, { it })

private fun <A, B> Either<A, B>.errorOrNull(): A? = fold({ it }, { null })

// In-memory fakes over the (all-open) repositories/storage. The Firestore ctor arg is never touched
// because every DB-facing method is overridden, so a bare Mockito mock satisfies the constructor.
private class FakeAssetRepo : AssetRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Asset>()
    val saves = mutableListOf<Asset>()
    private var seq = 0

    override fun newId(): String = "gen-${++seq}"

    override fun findById(id: String): Asset? = store[id]

    override fun findBySubject(subjectId: String): List<Asset> =
        store.values.filter { it.subjectId == subjectId }.sortedByDescending { it.updatedAt }

    override fun save(asset: Asset) {
        store[asset.id] = asset
        saves += asset
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}

private class FakeSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]
}

private class FakeManifestRepo : IntakeManifestRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, IntakeManifest>()
    val saves = mutableListOf<IntakeManifest>()

    override fun findBySubject(subjectId: String): IntakeManifest? = store[subjectId]

    override fun save(manifest: IntakeManifest) {
        store[manifest.subjectId] = manifest
        saves += manifest
    }

    override fun delete(subjectId: String) {
        store.remove(subjectId)
    }
}

private class FakeStorage(props: AppProperties) : IntakeStorage(props) {
    /** objectPath → bytes present (Long) or absent (null / missing key). */
    val sizes = mutableMapOf<String, Long>()
    val deleted = mutableListOf<String>()

    override fun signedUploadUrl(objectPath: String, contentType: String): SignedUpload =
        SignedUpload(
            url = "https://signed",
            objectPath = objectPath,
            headers = mapOf("Content-Type" to contentType),
        )

    override fun objectSize(objectPath: String): Long? = sizes[objectPath]

    override fun deleteObject(objectPath: String) {
        deleted += objectPath
    }

    override fun uriFor(objectPath: String): String = "gs://test/$objectPath"

    override fun signedDownloadUrl(objectPath: String): String = "https://download/$objectPath"
}

/**
 * [IntakeService] backed by in-memory fakes (no Spring context). Covers the reissue/reconcile/
 * seal-gating/size-cap logic added to close the Stage 1 backend gaps. JUnit5's per-method lifecycle
 * gives each test fresh fakes.
 */
class IntakeServiceTest {

    private val assets = FakeAssetRepo()
    private val subjects = FakeSubjectRepo()
    private val manifests = FakeManifestRepo()
    private val props =
        AppProperties(
            intake = AppProperties.Intake(maxAssetSizeBytes = 1000, staleUploadHours = 24)
        )
    private val storage = FakeStorage(props)
    private val service = IntakeService(assets, subjects, manifests, storage, props)

    private val path = "intake/s1/resume_cv/2026-07-01/a1-resume.pdf"

    private fun asset(
        id: String = "a1",
        subjectId: String = "s1",
        modality: AssetModality = AssetModality.DOCUMENT,
        uploadStatus: AssetUploadStatus = AssetUploadStatus.AWAITING_UPLOAD,
        storedObjectPath: String? = path,
        mimeType: String? = "application/pdf",
        consentStatus: ConsentStatus = ConsentStatus.GRANTED,
        createdAt: Instant = Instant.now(),
    ) =
        Asset(
            id = id,
            subjectId = subjectId,
            title = "Resume",
            modality = modality,
            sourceClass = ContentType.RESUME_CV.sourceClass,
            contentType = ContentType.RESUME_CV,
            relationship = Relationship.SELF,
            authenticityPrior = AuthenticityTier.LOW,
            storedObjectPath = storedObjectPath,
            mimeType = mimeType,
            consentStatus = consentStatus,
            uploadStatus = uploadStatus,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    private fun seed(vararg a: Asset) = a.forEach { assets.store[it.id] = it }

    private fun seedSubject() {
        subjects.store["s1"] = Subject(id = "s1", displayName = "Test Subject", handle = "test")
    }

    // ---- reissueUploadUrl ---------------------------------------------------

    @Test
    fun `reissueUploadUrl re-arms a FAILED asset back to AWAITING_UPLOAD`() {
        seed(asset(uploadStatus = AssetUploadStatus.FAILED))

        val result = service.reissueUploadUrl("a1")

        assertTrue(result.isRight())
        assertEquals("https://signed", result.valueOrNull()?.upload?.url)
        assertEquals(AssetUploadStatus.AWAITING_UPLOAD, assets.store["a1"]!!.uploadStatus)
    }

    @Test
    fun `reissueUploadUrl leaves an already-AWAITING_UPLOAD asset unchanged`() {
        seed(asset(uploadStatus = AssetUploadStatus.AWAITING_UPLOAD))

        val result = service.reissueUploadUrl("a1")

        assertTrue(result.isRight())
        assertTrue(assets.saves.isEmpty())
    }

    @Test
    fun `reissueUploadUrl rejects LINK assets`() {
        seed(asset(modality = AssetModality.LINK, storedObjectPath = null))

        val result = service.reissueUploadUrl("a1")

        assertTrue(result.errorOrNull() is DomainError.Invalid)
    }

    @Test
    fun `reissueUploadUrl rejects already-STORED assets`() {
        seed(asset(uploadStatus = AssetUploadStatus.STORED))

        val result = service.reissueUploadUrl("a1")

        assertTrue(result.errorOrNull() is DomainError.Invalid)
    }

    @Test
    fun `reissueUploadUrl returns NotFound for an unknown asset`() {
        val result = service.reissueUploadUrl("missing")

        assertTrue(result.errorOrNull() is DomainError.NotFound)
    }

    // ---- reconcileAsset / reconcileSubject -----------------------------------

    @Test
    fun `reconcileAsset completes an asset whose bytes have landed`() {
        seed(asset(uploadStatus = AssetUploadStatus.AWAITING_UPLOAD))
        storage.sizes[path] = 500L

        val result = service.reconcileAsset("a1")

        assertTrue(result.isRight())
        assertEquals(AssetUploadStatus.STORED, assets.store["a1"]!!.uploadStatus)
        assertEquals(500L, assets.store["a1"]!!.sizeBytes)
    }

    @Test
    fun `reconcileAsset fails an asset whose landed bytes exceed the size cap`() {
        seed(asset(uploadStatus = AssetUploadStatus.AWAITING_UPLOAD))
        storage.sizes[path] = 5000L

        service.reconcileAsset("a1")

        assertTrue(storage.deleted.contains(path))
        assertEquals(AssetUploadStatus.FAILED, assets.store["a1"]!!.uploadStatus)
    }

    @Test
    fun `reconcileAsset fails a stale asset with no bytes present`() {
        seed(
            asset(
                uploadStatus = AssetUploadStatus.AWAITING_UPLOAD,
                createdAt = Instant.now().minusSeconds(48 * 3600),
            )
        )

        service.reconcileAsset("a1")

        assertEquals(AssetUploadStatus.FAILED, assets.store["a1"]!!.uploadStatus)
    }

    @Test
    fun `reconcileAsset leaves a fresh asset with no bytes present untouched`() {
        seed(asset(uploadStatus = AssetUploadStatus.AWAITING_UPLOAD, createdAt = Instant.now()))

        service.reconcileAsset("a1")

        assertTrue(assets.saves.isEmpty())
        assertEquals(AssetUploadStatus.AWAITING_UPLOAD, assets.store["a1"]!!.uploadStatus)
    }

    @Test
    fun `reconcileSubject only recomputes the manifest when something actually changed`() {
        seed(asset(uploadStatus = AssetUploadStatus.AWAITING_UPLOAD, createdAt = Instant.now()))

        val changed = service.reconcileSubject("s1")

        assertTrue(changed.isEmpty())
        assertTrue(manifests.saves.isEmpty())
    }

    // ---- sealManifest gating --------------------------------------------------

    @Test
    fun `sealManifest refuses when an asset is pending consent`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.PENDING, uploadStatus = AssetUploadStatus.STORED))

        val result = service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")

        assertTrue(result.errorOrNull() is DomainError.Conflict)
        assertTrue(result.errorOrNull()!!.message.contains("pending consent"))
    }

    @Test
    fun `sealManifest refuses when an asset is not yet stored`() {
        seedSubject()
        seed(
            asset(
                consentStatus = ConsentStatus.GRANTED,
                uploadStatus = AssetUploadStatus.AWAITING_UPLOAD,
            )
        )

        val result = service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")

        assertTrue(result.errorOrNull()!!.message.contains("not stored"))
    }

    @Test
    fun `sealManifest succeeds once every asset is stored and consented`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.GRANTED, uploadStatus = AssetUploadStatus.STORED))

        val result = service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")

        val sealed = result.valueOrNull()
        assertTrue(sealed != null)
        assertTrue(sealed.sealed)
        assertEquals("reviewer@vishwakarma.ai", sealed.sealedBy)
        assertTrue(sealed.sealBlockers.isEmpty())
    }

    // ---- unsealManifest -------------------------------------------------------

    @Test
    fun `unsealManifest reverses a sealed manifest`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.GRANTED, uploadStatus = AssetUploadStatus.STORED))
        service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")

        val result = service.unsealManifest("s1", "admin@vishwakarma.ai", "reopening for edits")

        val unsealed = result.valueOrNull()
        assertTrue(unsealed != null)
        assertTrue(!unsealed.sealed)
        assertEquals(null, unsealed.sealedBy)
        assertEquals(null, unsealed.sealedAt)
    }

    @Test
    fun `unsealManifest refuses when the manifest is not sealed`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.GRANTED, uploadStatus = AssetUploadStatus.STORED))

        val result = service.unsealManifest("s1", "admin@vishwakarma.ai", "reopening for edits")

        assertTrue(result.errorOrNull() is DomainError.Conflict)
        assertTrue(result.errorOrNull()!!.message.contains("not sealed"))
    }

    @Test
    fun `seal and unseal both require a non-blank note`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.GRANTED, uploadStatus = AssetUploadStatus.STORED))

        val sealResult = service.sealManifest("s1", "reviewer@vishwakarma.ai", "  ")
        assertTrue(sealResult.errorOrNull() is DomainError.Invalid)

        service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")
        val unsealResult = service.unsealManifest("s1", "admin@vishwakarma.ai", "")
        assertTrue(unsealResult.errorOrNull() is DomainError.Invalid)
    }

    @Test
    fun `seal and unseal append to the audit history with actor and note`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.GRANTED, uploadStatus = AssetUploadStatus.STORED))

        service.sealManifest("s1", "reviewer@vishwakarma.ai", "all reviewed")
        val unsealed =
            service
                .unsealManifest("s1", "admin@vishwakarma.ai", "subject wants edits")
                .valueOrNull()!!

        assertEquals(2, unsealed.sealEvents.size)
        assertEquals(SealAction.SEAL, unsealed.sealEvents[0].action)
        assertEquals("reviewer@vishwakarma.ai", unsealed.sealEvents[0].actor)
        assertEquals("all reviewed", unsealed.sealEvents[0].note)
        assertEquals(SealAction.UNSEAL, unsealed.sealEvents[1].action)
        assertEquals("admin@vishwakarma.ai", unsealed.sealEvents[1].actor)
        assertEquals("subject wants edits", unsealed.sealEvents[1].note)
        // History survives a recompute (any asset read/change path).
        assertEquals(2, service.manifest("s1").sealEvents.size)
    }

    @Test
    fun `a sealed manifest blocks asset mutations until unsealed`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.GRANTED, uploadStatus = AssetUploadStatus.STORED))
        service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")

        val update = service.updateAsset("a1", AssetPatch(title = "new title"))
        assertTrue(update.errorOrNull() is DomainError.Conflict)
        assertTrue(update.errorOrNull()!!.message.contains("sealed"))

        val delete = service.deleteAsset("a1")
        assertTrue(delete.errorOrNull() is DomainError.Conflict)

        val link =
            service.registerLink(
                "s1",
                "reviewer@vishwakarma.ai",
                LinkRegistration(
                    title = "GH",
                    contentType = ContentType.GITHUB,
                    externalUrl = "https://github.com/x",
                ),
            )
        assertTrue(link.errorOrNull() is DomainError.Conflict)

        // Unseal reopens editing.
        service.unsealManifest("s1", "admin@vishwakarma.ai", "reopening")
        assertTrue(service.updateAsset("a1", AssetPatch(title = "new title")).valueOrNull() != null)
    }

    @Test
    fun `unsealManifest is refused permanently once Stage 2 has started`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.GRANTED, uploadStatus = AssetUploadStatus.STORED))
        service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")
        // Simulate the future Stage 2 consumer stamping the manifest.
        manifests.store["s1"] = manifests.store["s1"]!!.copy(stage2StartedAt = Instant.now())

        val result = service.unsealManifest("s1", "admin@vishwakarma.ai", "please reopen")

        assertTrue(result.errorOrNull() is DomainError.Conflict)
        assertTrue(result.errorOrNull()!!.message.contains("Stage 2"))
    }

    // ---- completeAsset size cap -------------------------------------------

    @Test
    fun `completeAsset rejects and deletes bytes that exceed the size cap`() {
        seed(asset(uploadStatus = AssetUploadStatus.AWAITING_UPLOAD))
        storage.sizes[path] = 5000L

        val result = service.completeAsset("a1")

        assertTrue(result.errorOrNull() is DomainError.Invalid)
        assertTrue(storage.deleted.contains(path))
        assertEquals(AssetUploadStatus.FAILED, assets.store["a1"]!!.uploadStatus)
    }
}

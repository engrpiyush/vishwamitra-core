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
import ai.vishwakarma.labelling.liveConfig
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

    /** objectPath → content checksum returned by [objectChecksum]. */
    val checksums = mutableMapOf<String, String>()
    val deleted = mutableListOf<String>()

    override fun signedUploadUrl(objectPath: String, contentType: String): SignedUpload =
        SignedUpload(
            url = "https://signed",
            objectPath = objectPath,
            headers = mapOf("Content-Type" to contentType),
        )

    override fun objectSize(objectPath: String): Long? = sizes[objectPath]

    override fun objectChecksum(objectPath: String): String? = checksums[objectPath]

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
    private val service = IntakeService(assets, subjects, manifests, storage, liveConfig(props))

    private val path = "intake/s1/resume_cv/2026-07-01/a1-resume.pdf"

    private fun asset(
        id: String = "a1",
        subjectId: String = "s1",
        modality: AssetModality = AssetModality.DOCUMENT,
        uploadStatus: AssetUploadStatus = AssetUploadStatus.AWAITING_UPLOAD,
        storedObjectPath: String? = path,
        mimeType: String? = "application/pdf",
        consentStatus: ConsentStatus = ConsentStatus.GRANTED,
        checksum: String? = null,
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
            checksum = checksum,
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

    // ---- cross-asset dedup (§12.7 hardening, rank #1) --------------------------

    @Test
    fun `completeAsset stamps the content checksum from storage`() {
        seedSubject()
        seed(asset(uploadStatus = AssetUploadStatus.AWAITING_UPLOAD))
        storage.sizes[path] = 500
        storage.checksums[path] = "md5-abc"

        val result = service.completeAsset("a1")

        assertEquals("md5-abc", result.valueOrNull()?.checksum)
    }

    @Test
    fun `sealManifest refuses when two assets share a checksum (duplicate upload)`() {
        seedSubject()
        seed(
            asset(
                id = "a1",
                storedObjectPath = "p1",
                uploadStatus = AssetUploadStatus.STORED,
                checksum = "same"
            ),
            asset(
                id = "a2",
                storedObjectPath = "p2",
                uploadStatus = AssetUploadStatus.STORED,
                checksum = "same"
            ),
        )

        val result = service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")

        assertTrue(result.errorOrNull() is DomainError.Conflict)
        assertTrue(result.errorOrNull()!!.message.contains("same file"))
    }

    @Test
    fun `sealManifest ignores distinct checksums`() {
        seedSubject()
        seed(
            asset(
                id = "a1",
                storedObjectPath = "p1",
                uploadStatus = AssetUploadStatus.STORED,
                checksum = "h1"
            ),
            asset(
                id = "a2",
                storedObjectPath = "p2",
                uploadStatus = AssetUploadStatus.STORED,
                checksum = "h2"
            ),
        )

        val result = service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")

        assertTrue(result.valueOrNull()?.sealed == true)
    }

    @Test
    fun `duplicate blocker clears once one copy is removed`() {
        seedSubject()
        seed(
            asset(
                id = "a1",
                storedObjectPath = "p1",
                uploadStatus = AssetUploadStatus.STORED,
                checksum = "same"
            ),
            asset(
                id = "a2",
                storedObjectPath = "p2",
                uploadStatus = AssetUploadStatus.STORED,
                checksum = "same"
            ),
        )
        assertTrue(service.sealManifest("s1", "reviewer@vishwakarma.ai", "x").errorOrNull() != null)

        assets.store.remove("a2")

        assertTrue(
            service
                .sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")
                .valueOrNull()
                ?.sealed == true
        )
    }

    // ---- consent revocation (§12.7 hardening) ---------------------------------

    @Test
    fun `revokeAssetConsent marks REVOKED and deletes bytes, honored on a locked manifest`() {
        seedSubject()
        seed(asset(uploadStatus = AssetUploadStatus.STORED, consentStatus = ConsentStatus.GRANTED))
        service.sealManifest("s1", "reviewer@vishwakarma.ai", "reviewed")
        manifests.store["s1"] = manifests.store["s1"]!!.copy(stage2StartedAt = Instant.now())

        val result = service.revokeAssetConsent("a1", "subject withdrew consent")

        assertEquals(ConsentStatus.REVOKED, result.valueOrNull()?.consentStatus)
        assertTrue(storage.deleted.contains(path))
        // Revoke must not unlock — the permanent seal stands.
        assertTrue(
            service.unsealManifest("s1", "admin@vishwakarma.ai", "x").errorOrNull()
                is DomainError.Conflict
        )
    }

    @Test
    fun `revokeAssetConsent requires a note`() {
        seedSubject()
        seed(asset())

        assertTrue(service.revokeAssetConsent("a1", "  ").errorOrNull() is DomainError.Invalid)
    }

    @Test
    fun `revokeAssetConsent refuses an already-revoked asset`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.REVOKED))

        assertTrue(service.revokeAssetConsent("a1", "again").errorOrNull() is DomainError.Conflict)
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

    @Test
    fun `recomputeManifest preserves the claim-review locks (a manifest read must not wipe them)`() {
        seedSubject()
        seed(asset(consentStatus = ConsentStatus.GRANTED, uploadStatus = AssetUploadStatus.STORED))
        service.manifest("s1") // create the manifest
        val now = Instant.now()
        manifests.store["s1"] =
            manifests.store["s1"]!!.copy(reviewLockedAt = now, reviewSubmittedAt = now)

        // A manifest read recomputes + saves; the §12.6 locks must survive it.
        val recomputed = service.manifest("s1")

        assertTrue(recomputed.reviewLockedAt != null)
        assertTrue(recomputed.reviewSubmittedAt != null)
        assertTrue(manifests.store["s1"]!!.reviewLockedAt != null)
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

    @Test
    fun `completeAsset rejects an empty (0-byte) uploaded object and leaves it retryable`() {
        seed(asset(uploadStatus = AssetUploadStatus.AWAITING_UPLOAD))
        storage.sizes[path] = 0L

        val result = service.completeAsset("a1")

        assertTrue(result.errorOrNull() is DomainError.Invalid)
        assertTrue(result.errorOrNull()!!.message.contains("empty"))
        // Not marked STORED — the object is not a completed upload; the row can be retried.
        assertEquals(AssetUploadStatus.AWAITING_UPLOAD, assets.store["a1"]!!.uploadStatus)
    }

    // ---- consent attestation (VA-32, §13.1) --------------------------------

    @Test
    fun `attestConsent stamps who and when on the manifest`() {
        seedSubject()
        seed(asset(uploadStatus = AssetUploadStatus.STORED))

        val attested = service.attestConsent("s1", "dev-subject@example.com")

        assertTrue(attested.consentAttestedAt != null)
        assertEquals("dev-subject@example.com", attested.consentAttestedBy)
    }

    @Test
    fun `manifest recompute carries the attestation stamp through`() {
        seedSubject()
        seed(asset(uploadStatus = AssetUploadStatus.STORED))
        val stamped = service.attestConsent("s1", "dev-subject@example.com")

        // Any manifest read recomputes + saves; a field not carried through would be wiped here.
        val recomputed = service.manifest("s1")

        assertEquals(stamped.consentAttestedAt, recomputed.consentAttestedAt)
        assertEquals("dev-subject@example.com", recomputed.consentAttestedBy)
    }

    @Test
    fun `a later batch's attestation refreshes the stamp`() {
        seedSubject()
        seed(asset(uploadStatus = AssetUploadStatus.STORED))
        val first = service.attestConsent("s1", "dev-subject@example.com")

        val second = service.attestConsent("s1", "dev-subject@example.com")

        assertTrue(!second.consentAttestedAt!!.isBefore(first.consentAttestedAt!!))
    }
}

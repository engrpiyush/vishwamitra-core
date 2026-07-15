package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.ProviderRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage2.ClaimExtractor
import ai.vishwakarma.labelling.stage2.DocumentPayload
import ai.vishwakarma.labelling.stage2.DocumentSource
import ai.vishwakarma.labelling.stage2.SpeakerAttribution
import ai.vishwakarma.labelling.stage2.SpeakerResolution
import ai.vishwakarma.labelling.stage2.Transcriber
import ai.vishwakarma.labelling.stage2.Transcript
import ai.vishwakarma.labelling.stage2.TranscriptSegment
import ai.vishwakarma.labelling.stage2.TranscriptionPoll
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private fun <A, B> Either<A, B>.valueOrNull(): B? = fold({ null }, { it })

private fun <A, B> Either<A, B>.errorOrNull(): A? = fold({ it }, { null })

// In-memory fakes over the (all-open) repositories. The Firestore ctor arg is never touched
// because every DB-facing method is overridden, so a bare Mockito mock satisfies the constructor.
private class FakeStage2AssetRepo : AssetRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Asset>()

    override fun findById(id: String): Asset? = store[id]

    override fun findBySubject(subjectId: String): List<Asset> =
        store.values.filter { it.subjectId == subjectId }.sortedByDescending { it.updatedAt }

    override fun save(asset: Asset) {
        store[asset.id] = asset
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}

private class FakeStage2SubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]
}

private class FakeStage2ManifestRepo : IntakeManifestRepository(mock(Firestore::class.java)) {
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

private class FakeStage2JobRepo : Stage2JobRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage2Job>()
    val saves = mutableListOf<Stage2Job>()
    private var seq = 0

    override fun newId(): String = "job-${++seq}"

    override fun findById(id: String): Stage2Job? = store[id]

    override fun findBySubject(subjectId: String): List<Stage2Job> =
        store.values.filter { it.subjectId == subjectId }.sortedByDescending { it.createdAt }

    override fun save(job: Stage2Job) {
        store[job.id] = job
        saves += job
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}

private class FakeClaimRepo : ClaimRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Claim>()
    private var seq = 0

    override fun newId(): String = "claim-${++seq}"

    override fun findById(id: String): Claim? = store[id]

    override fun findBySubject(subjectId: String): List<Claim> =
        store.values.filter { it.subjectId == subjectId }.sortedByDescending { it.createdAt }

    override fun findByAsset(assetId: String): List<Claim> =
        store.values.filter { it.assetId == assetId }.sortedByDescending { it.createdAt }

    override fun save(claim: Claim) {
        store[claim.id] = claim
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}

private class FakeStage2ReviewRepo : ClaimReviewRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ClaimReview>()

    override fun findByClaim(claimId: String): ClaimReview? = store[claimId]

    override fun findBySubject(subjectId: String): List<ClaimReview> =
        store.values.filter { it.subjectId == subjectId }

    override fun save(review: ClaimReview) {
        store[review.claimId] = review
    }

    override fun delete(claimId: String) {
        store.remove(claimId)
    }
}

private class StubTranscriber : Transcriber {
    val submitted = mutableListOf<String>()
    val attempts = mutableListOf<Int>()
    var lastHints: List<String> = emptyList()
    var failSubmitFor: String? = null
    var attemptLimit = Int.MAX_VALUE
    var pollThrows = false
    var pollResult: TranscriptionPoll = TranscriptionPoll.Running
    var fetchedTranscript: Transcript? = null
    var fetchThrows = false
    private var seq = 0

    override fun submit(
        subjectId: String,
        assetId: String,
        gcsUri: String,
        mimeType: String?,
        attempt: Int,
        hints: List<String>,
    ): String {
        if (assetId == failSubmitFor) error("submit refused for $assetId")
        require(attempt < attemptLimit) { "decoding attempts exhausted" }
        submitted += assetId
        attempts += attempt
        lastHints = hints
        return "op-${++seq}"
    }

    override fun poll(operationName: String): TranscriptionPoll {
        if (pollThrows) error("transport down")
        return pollResult
    }

    override fun fetchTranscript(transcriptUri: String): Transcript {
        if (fetchThrows) error("transcript object missing")
        return fetchedTranscript ?: error("no fetched transcript configured")
    }
}

private class FakeDocumentSource : DocumentSource(liveConfig(AppProperties())) {
    var unsupported: String? = null
    var readThrows = false
    var payload = DocumentPayload("certificate bytes".toByteArray(), "image/png")

    override fun supportError(asset: Asset): String? = unsupported

    override fun read(asset: Asset): DocumentPayload {
        check(!readThrows) { "stored document not found: ${asset.gcsUri}" }
        return payload
    }
}

private class FakeExtractor :
    ClaimExtractor(
        GeminiDrafting(
            AppProperties(),
            ProviderService(ProviderRepository(mock(Firestore::class.java)))
        ),
        ExtractionPromptService(ExtractionPromptRepository(mock(Firestore::class.java)))
    ) {
    var throws = false
    var lastDocumentMime: String? = null

    override fun extractDocument(
        subjectId: String,
        asset: Asset,
        bytes: ByteArray,
        mimeType: String,
        subjectName: String?,
    ): List<Claim> {
        if (throws) error("extraction blew up")
        lastDocumentMime = mimeType
        return listOf(
            Claim(
                id = "",
                subjectId = subjectId,
                assetId = asset.id,
                claimType = ClaimType.EPISODE,
                text = "Was awarded the Professional Cloud Architect certification",
                sourceExcerpt = "Professional Cloud Architect",
                authenticityTier = asset.authenticityPrior,
            ),
            Claim(
                id = "",
                subjectId = subjectId,
                assetId = asset.id,
                claimType = ClaimType.SKILL,
                text = "Holds cloud architecture expertise",
                sourceExcerpt = "Professional Cloud Architect",
                authenticityTier = asset.authenticityPrior,
            ),
        )
    }

    var lastSpeakerRoles: Map<String, SpeakerAssignment>? = null

    override fun extract(
        subjectId: String,
        asset: Asset,
        transcript: Transcript,
        subjectName: String?,
        speakerRoles: Map<String, SpeakerAssignment>?,
    ): List<Claim> {
        if (throws) error("extraction blew up")
        lastSpeakerRoles = speakerRoles
        return listOf(
            Claim(
                id = "",
                subjectId = subjectId,
                assetId = asset.id,
                claimType = ClaimType.EPISODE,
                text = "Led the 2021 gateway migration",
                speaker = "Speaker 2",
                authenticityTier = asset.authenticityPrior,
            ),
            Claim(
                id = "",
                subjectId = subjectId,
                assetId = asset.id,
                claimType = ClaimType.SKILL,
                text = "Strong in distributed systems",
                speaker = "Speaker 2",
                authenticityTier = asset.authenticityPrior,
            ),
        )
    }
}

/**
 * Fake [SpeakerAttribution] — returns a configurable resolution (null = no attribution, default).
 */
private class FakeSpeakerAttribution :
    SpeakerAttribution(
        GeminiDrafting(
            AppProperties(),
            ProviderService(ProviderRepository(mock(Firestore::class.java)))
        )
    ) {
    var resolution: SpeakerResolution? = null
    var resolvedFor: String? = null

    override fun resolve(
        asset: Asset,
        transcript: Transcript,
        subjectName: String?,
    ): SpeakerResolution? {
        resolvedFor = asset.id
        return resolution
    }
}

/**
 * [Stage2Service] backed by in-memory fakes (no Spring context): the process guards + permanent
 * lock, per-asset job submission, and the poll state machine. JUnit5's per-method lifecycle gives
 * each test fresh fakes.
 */
class Stage2ServiceTest {

    private val subjects = FakeStage2SubjectRepo()
    private val manifests = FakeStage2ManifestRepo()
    private val assets = FakeStage2AssetRepo()
    private val jobs = FakeStage2JobRepo()
    private val claims = FakeClaimRepo()
    private val reviews = FakeStage2ReviewRepo()
    private val transcriber = StubTranscriber()
    private val extractor = FakeExtractor()
    private val speakerAttribution = FakeSpeakerAttribution()
    private val documents = FakeDocumentSource()
    private val service =
        Stage2Service(
            subjects,
            manifests,
            assets,
            jobs,
            claims,
            reviews,
            transcriber,
            extractor,
            speakerAttribution,
            documents,
            liveConfig(AppProperties()),
        )

    private val actor = "reviewer@vishwakarma.ai"

    private fun seedSubject() {
        subjects.store["s1"] = Subject(id = "s1", displayName = "Test Subject")
    }

    private fun seedManifest(sealed: Boolean = true, stage2StartedAt: Instant? = null) {
        manifests.store["s1"] =
            IntakeManifest(
                id = "s1",
                subjectId = "s1",
                sealed = sealed,
                stage2StartedAt = stage2StartedAt,
            )
    }

    private fun avAsset(
        id: String = "a1",
        modality: AssetModality = AssetModality.AUDIO,
        consentStatus: ConsentStatus = ConsentStatus.GRANTED,
        uploadStatus: AssetUploadStatus = AssetUploadStatus.STORED,
        gcsUri: String? = "gs://intake/s1/$id.mp3",
        prior: AuthenticityTier = AuthenticityTier.MEDIUM,
    ) =
        Asset(
            id = id,
            subjectId = "s1",
            title = "Endorser call",
            modality = modality,
            sourceClass = ContentType.MANAGER_ENDORSEMENT.sourceClass,
            contentType = ContentType.MANAGER_ENDORSEMENT,
            relationship = Relationship.MANAGER,
            authenticityPrior = prior,
            gcsUri = gcsUri,
            consentStatus = consentStatus,
            uploadStatus = uploadStatus,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun seed(vararg a: Asset) = a.forEach { assets.store[it.id] = it }

    @Test
    fun `purgeSubject deletes the subject's claim reviews too (no orphaned reviews)`() {
        claims.store["c1"] =
            Claim(
                id = "c1",
                subjectId = "s1",
                assetId = "a1",
                claimType = ClaimType.EPISODE,
                text = "x",
            )
        reviews.store["c1"] = ClaimReview(claimId = "c1", subjectId = "s1")

        service.purgeSubject("s1")

        assertTrue(reviews.store.isEmpty())
        assertTrue(claims.store.isEmpty())
    }

    private fun seedTranscribingJob(id: String = "j1", assetId: String = "a1"): Stage2Job {
        val job =
            Stage2Job(
                id = id,
                subjectId = "s1",
                assetId = assetId,
                modality = AssetModality.AUDIO,
                status = Stage2JobStatus.TRANSCRIBING,
                externalOperationId = "op-x",
                createdAt = Instant.now(),
            )
        jobs.store[id] = job
        return job
    }

    private fun transcript() =
        Transcript(
            segments = listOf(TranscriptSegment("Speaker 2", 6.5, 15.0, "I managed him.")),
            language = "en-US",
        )

    // ---- process ------------------------------------------------------------

    @Test
    fun `process refuses an unsealed manifest`() {
        seedSubject()
        seedManifest(sealed = false)
        seed(avAsset())

        val result = service.process("s1", actor)

        assertTrue(result.errorOrNull() is DomainError.Conflict)
        assertTrue(jobs.store.isEmpty())
        assertEquals(null, manifests.store["s1"]!!.stage2StartedAt)
    }

    @Test
    fun `process refuses when Stage 2 already started`() {
        seedSubject()
        seedManifest(stage2StartedAt = Instant.now())
        seed(avAsset())

        val result = service.process("s1", actor)

        assertTrue(result.errorOrNull() is DomainError.Conflict)
        assertTrue(result.errorOrNull()!!.message.contains("already started"))
        assertTrue(jobs.store.isEmpty())
    }

    @Test
    fun `process returns NotFound for an unknown subject`() {
        val result = service.process("nope", actor)

        assertTrue(result.errorOrNull() is DomainError.NotFound)
    }

    @Test
    fun `process stamps the permanent lock and creates TRANSCRIBING jobs for eligible A_V assets`() {
        seedSubject()
        seedManifest()
        seed(
            avAsset("a1"),
            avAsset("a2", modality = AssetModality.VIDEO, gcsUri = "gs://intake/s1/a2.mp4"),
        )

        val created = service.process("s1", actor).valueOrNull()!!

        assertEquals(2, created.size)
        assertTrue(
            created.all {
                it.status == Stage2JobStatus.TRANSCRIBING &&
                    it.externalOperationId != null &&
                    it.startedAt != null
            }
        )
        assertEquals(setOf("a1", "a2"), transcriber.submitted.toSet())
        assertEquals(actor, created.first().createdBy)
        assertTrue(manifests.store["s1"]!!.stage2StartedAt != null)
    }

    @Test
    fun `process skips non-consented assets and unprocessable modalities`() {
        seedSubject()
        seedManifest()
        seed(
            avAsset("a1"),
            avAsset("a2", consentStatus = ConsentStatus.PENDING),
            avAsset("a3", consentStatus = ConsentStatus.REVOKED),
            avAsset("a4", modality = AssetModality.TEXT),
            avAsset("a5", consentStatus = ConsentStatus.NOT_REQUIRED),
            avAsset("a6", uploadStatus = AssetUploadStatus.AWAITING_UPLOAD),
        )

        val created = service.process("s1", actor).valueOrNull()!!

        assertEquals(setOf("a1", "a5"), created.map { it.assetId }.toSet())
    }

    @Test
    fun `process with no eligible assets is refused without stamping the lock`() {
        seedSubject()
        seedManifest()
        seed(avAsset(modality = AssetModality.TEXT))

        val result = service.process("s1", actor)

        assertTrue(result.errorOrNull() is DomainError.Invalid)
        assertEquals(null, manifests.store["s1"]!!.stage2StartedAt)
        assertTrue(jobs.store.isEmpty())
    }

    @Test
    fun `process marks a job FAILED when submit throws and continues the rest`() {
        seedSubject()
        seedManifest()
        seed(avAsset("a1"), avAsset("a2"))
        transcriber.failSubmitFor = "a1"

        val created = service.process("s1", actor).valueOrNull()!!

        val byAsset = created.associateBy { it.assetId }
        assertEquals(Stage2JobStatus.FAILED, byAsset["a1"]!!.status)
        assertTrue(byAsset["a1"]!!.error!!.contains("submit"))
        assertEquals(Stage2JobStatus.TRANSCRIBING, byAsset["a2"]!!.status)
    }

    // ---- poll ---------------------------------------------------------------

    @Test
    fun `poll returns NotFound for an unknown job`() {
        assertTrue(service.poll("nope").errorOrNull() is DomainError.NotFound)
    }

    @Test
    fun `poll leaves a TRANSCRIBING job unchanged while the operation runs`() {
        seedTranscribingJob()
        transcriber.pollResult = TranscriptionPoll.Running

        val result = service.poll("j1")

        assertEquals(Stage2JobStatus.TRANSCRIBING, result.valueOrNull()!!.status)
        assertTrue(jobs.saves.isEmpty())
    }

    @Test
    fun `poll on transcript completion writes claims seeded from the asset prior and completes the job`() {
        seed(avAsset("a1", prior = AuthenticityTier.HIGH))
        seedTranscribingJob()
        transcriber.pollResult =
            TranscriptionPoll.Done(transcript(), "gs://transcripts/s1/a1/out.json")

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        assertEquals(2, result.claimCount)
        assertEquals("gs://transcripts/s1/a1/out.json", result.transcriptUri)
        assertTrue(result.finishedAt != null)
        val stored = claims.store.values.toList()
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.id.isNotBlank() })
        assertTrue(stored.all { it.subjectId == "s1" && it.assetId == "a1" })
        assertTrue(stored.all { it.authenticityTier == AuthenticityTier.HIGH })
        assertTrue(stored.all { it.authenticityScore == null })
    }

    @Test
    fun `poll marks the job FAILED when the operation reports an error`() {
        seedTranscribingJob()
        transcriber.pollResult = TranscriptionPoll.Failed("audio codec unsupported")

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.FAILED, result.status)
        assertEquals("audio codec unsupported", result.error)
        assertTrue(result.finishedAt != null)
        assertTrue(claims.store.isEmpty())
    }

    @Test
    fun `poll marks the job FAILED when extraction throws`() {
        seed(avAsset("a1"))
        seedTranscribingJob()
        transcriber.pollResult = TranscriptionPoll.Done(transcript(), null)
        extractor.throws = true

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("extraction"))
        assertTrue(claims.store.isEmpty())
    }

    @Test
    fun `poll fails the job when its asset no longer exists`() {
        seedTranscribingJob()
        transcriber.pollResult = TranscriptionPoll.Done(transcript(), null)

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("no longer exists"))
    }

    @Test
    fun `re-polling a terminal job is a no-op`() {
        val done = seedTranscribingJob().copy(status = Stage2JobStatus.COMPLETED)
        jobs.store["j1"] = done

        val result = service.poll("j1")

        assertEquals(Stage2JobStatus.COMPLETED, result.valueOrNull()!!.status)
        assertTrue(jobs.saves.isEmpty())
    }

    @Test
    fun `poll leaves an EXTRACTING job untouched so concurrent polls cannot double-extract`() {
        seed(avAsset("a1"))
        jobs.store["j1"] = seedTranscribingJob().copy(status = Stage2JobStatus.EXTRACTING)
        transcriber.pollResult = TranscriptionPoll.Done(transcript(), null)

        val result = service.poll("j1")

        assertEquals(Stage2JobStatus.EXTRACTING, result.valueOrNull()!!.status)
        assertTrue(jobs.saves.isEmpty())
        assertTrue(claims.store.isEmpty())
    }

    @Test
    fun `poll reclaims a job stuck in EXTRACTING past the timeout`() {
        seed(avAsset("a1"))
        jobs.store["j1"] =
            seedTranscribingJob()
                .copy(
                    status = Stage2JobStatus.EXTRACTING,
                    extractingSince = Instant.now().minus(Duration.ofHours(1)),
                )

        val result = service.poll("j1")

        assertEquals(Stage2JobStatus.FAILED, result.valueOrNull()!!.status)
        assertTrue(result.valueOrNull()!!.error!!.contains("stranded in EXTRACTING"))
    }

    @Test
    fun `poll leaves a recently-EXTRACTING job untouched`() {
        seed(avAsset("a1"))
        jobs.store["j1"] =
            seedTranscribingJob()
                .copy(status = Stage2JobStatus.EXTRACTING, extractingSince = Instant.now())

        val result = service.poll("j1")

        assertEquals(Stage2JobStatus.EXTRACTING, result.valueOrNull()!!.status)
        assertTrue(jobs.saves.isEmpty())
    }

    @Test
    fun `process passes the subject's name as a transcription hint`() {
        seedSubject()
        seedManifest()
        seed(avAsset("a1"))

        service.process("s1", actor)

        assertEquals(listOf("Test Subject"), transcriber.lastHints)
    }

    @Test
    fun `purgeSubject deletes the subject's claims and jobs`() {
        seedTranscribingJob("j1")
        claims.store["c1"] =
            Claim(
                id = "c1",
                subjectId = "s1",
                assetId = "a1",
                claimType = ClaimType.EPISODE,
                text = "x",
            )

        service.purgeSubject("s1")

        assertTrue(jobs.store.isEmpty())
        assertTrue(claims.store.isEmpty())
    }

    @Test
    fun `poll resubmits with the next decoding attempt on a retryable encoding failure`() {
        seed(avAsset("a1"))
        seedTranscribingJob()
        transcriber.pollResult =
            TranscriptionPoll.Failed("unsupported encoding", retryableWithNextDecoding = true)

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.TRANSCRIBING, result.status)
        assertEquals(1, result.decodingAttempt)
        assertEquals("op-1", result.externalOperationId)
        assertEquals(listOf(1), transcriber.attempts)
        assertTrue(claims.store.isEmpty())
    }

    @Test
    fun `poll fails the job when decoding attempts are exhausted`() {
        seed(avAsset("a1"))
        seedTranscribingJob()
        transcriber.attemptLimit = 1
        transcriber.pollResult =
            TranscriptionPoll.Failed("unsupported encoding", retryableWithNextDecoding = true)

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.FAILED, result.status)
        assertEquals("unsupported encoding", result.error)
        assertTrue(result.finishedAt != null)
    }

    // ---- retryJob -----------------------------------------------------------

    @Test
    fun `retryJob resubmits a FAILED job from attempt zero`() {
        seed(avAsset("a1"))
        jobs.store["j1"] =
            seedTranscribingJob()
                .copy(
                    status = Stage2JobStatus.FAILED,
                    error = "boom",
                    decodingAttempt = 1,
                    finishedAt = Instant.now(),
                )

        val result = service.retryJob("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.TRANSCRIBING, result.status)
        assertEquals(0, result.decodingAttempt)
        assertEquals(null, result.error)
        assertEquals(null, result.finishedAt)
        assertEquals(listOf(0), transcriber.attempts)
    }

    @Test
    fun `retryJob refuses a job that is not FAILED`() {
        seedTranscribingJob()

        assertTrue(service.retryJob("j1").errorOrNull() is DomainError.Conflict)
    }

    @Test
    fun `retryJob returns NotFound for an unknown job`() {
        assertTrue(service.retryJob("nope").errorOrNull() is DomainError.NotFound)
    }

    // ---- rerunJob -----------------------------------------------------------

    private fun seedCompletedJob(transcriptUri: String? = "gs://t/s1/a1/out.json"): Stage2Job {
        val job =
            Stage2Job(
                id = "j1",
                subjectId = "s1",
                assetId = "a1",
                modality = AssetModality.VIDEO,
                status = Stage2JobStatus.COMPLETED,
                externalOperationId = "op-x",
                transcriptUri = transcriptUri,
                claimCount = 2,
                createdAt = Instant.now(),
                finishedAt = Instant.now(),
            )
        jobs.store["j1"] = job
        return job
    }

    private fun seedOldClaim(id: String = "c-old", assetId: String = "a1") {
        claims.store[id] =
            Claim(
                id = id,
                subjectId = "s1",
                assetId = assetId,
                claimType = ClaimType.EPISODE,
                text = "stale claim from the previous run",
            )
    }

    @Test
    fun `purgeAssetDerived deletes only the target asset's claims and jobs`() {
        seedOldClaim(id = "c1", assetId = "a1")
        seedOldClaim(id = "c2", assetId = "a2")
        seedTranscribingJob(id = "j1", assetId = "a1")
        seedTranscribingJob(id = "j2", assetId = "a2")

        service.purgeAssetDerived("s1", "a1")

        assertTrue(claims.store["c1"] == null)
        assertTrue(claims.store["c2"] != null)
        assertTrue(jobs.store["j1"] == null)
        assertTrue(jobs.store["j2"] != null)
    }

    @Test
    fun `rerun re-extracts from the stored transcript and replaces the asset's claims`() {
        seed(avAsset("a1"))
        seedCompletedJob()
        seedOldClaim()
        transcriber.fetchedTranscript = transcript()

        val result = service.rerunJob("j1", full = false).valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        assertEquals(2, result.claimCount)
        assertTrue(transcriber.attempts.isEmpty())
        assertTrue(claims.store.keys.none { it == "c-old" })
        assertEquals(2, claims.store.size)
        assertTrue(claims.store.values.all { it.assetId == "a1" && it.id.isNotBlank() })
    }

    @Test
    fun `rerun refuses jobs that are not COMPLETED`() {
        seedTranscribingJob()

        assertTrue(service.rerunJob("j1", full = false).errorOrNull() is DomainError.Conflict)
    }

    @Test
    fun `rerun falls back to full re-transcription when no transcript is stored`() {
        seed(avAsset("a1"))
        seedCompletedJob(transcriptUri = null)

        val result = service.rerunJob("j1", full = false).valueOrNull()!!

        assertEquals(Stage2JobStatus.TRANSCRIBING, result.status)
        assertEquals(0, result.decodingAttempt)
        assertEquals(listOf(0), transcriber.attempts)
        assertEquals(null, result.claimCount)
    }

    @Test
    fun `full rerun re-transcribes even when a transcript is stored`() {
        seed(avAsset("a1"))
        seedCompletedJob()

        val result = service.rerunJob("j1", full = true).valueOrNull()!!

        assertEquals(Stage2JobStatus.TRANSCRIBING, result.status)
        assertEquals(listOf(0), transcriber.attempts)
    }

    @Test
    fun `rerun with a failing transcript fetch leaves the job COMPLETED and its claims intact`() {
        seed(avAsset("a1"))
        seedCompletedJob()
        seedOldClaim()
        transcriber.fetchThrows = true

        val result = service.rerunJob("j1", full = false)

        assertTrue(result.errorOrNull() is DomainError.Invalid)
        assertEquals(Stage2JobStatus.COMPLETED, jobs.store["j1"]!!.status)
        assertTrue(claims.store.containsKey("c-old"))
    }

    @Test
    fun `poll completion replaces the asset's previous claims`() {
        seed(avAsset("a1"))
        seedTranscribingJob()
        seedOldClaim()
        transcriber.pollResult = TranscriptionPoll.Done(transcript(), null)

        service.poll("j1")

        assertTrue(claims.store.keys.none { it == "c-old" })
        assertEquals(2, claims.store.size)
    }

    // ---- §12.4 speaker attribution wiring -----------------------------------

    private fun endorserBinding() =
        mapOf(
            "Speaker 1" to SpeakerAssignment(SpeakerRole.INTERVIEWER),
            "Speaker 2" to SpeakerAssignment(SpeakerRole.ENDORSER, Relationship.MANAGER),
        )

    private fun twoSpeakerTranscript() =
        Transcript(
            segments =
                listOf(
                    TranscriptSegment(
                        "Speaker 0",
                        0.0,
                        5.0,
                        "So what are you working on these days?"
                    ),
                    TranscriptSegment(
                        "Speaker 1",
                        5.0,
                        12.0,
                        "Mostly the payments platform migration."
                    ),
                ),
            language = "en-US",
        )

    @Test
    fun `poll resolves a speaker binding, persists it on the job, and passes it to extraction`() {
        seed(avAsset("a1"))
        seedTranscribingJob()
        // High confidence → auto-extract, no gate.
        speakerAttribution.resolution = SpeakerResolution(endorserBinding(), confidence = 0.95)
        transcriber.pollResult = TranscriptionPoll.Done(transcript(), null)

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        assertEquals("a1", speakerAttribution.resolvedFor)
        assertEquals(endorserBinding(), result.speakerRoles)
        assertEquals(endorserBinding(), extractor.lastSpeakerRoles)
    }

    @Test
    fun `poll leaves speakerRoles null when attribution finds nothing (single-speaker)`() {
        seed(avAsset("a1"))
        seedTranscribingJob()
        // FakeSpeakerAttribution.resolution defaults to null → asset-level provenance.
        transcriber.pollResult = TranscriptionPoll.Done(transcript(), null)

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        assertEquals(null, result.speakerRoles)
        assertEquals(null, extractor.lastSpeakerRoles)
    }

    @Test
    fun `re-extract reuses an existing binding without re-resolving`() {
        seed(avAsset("a1"))
        seedCompletedJob()
        jobs.store["j1"] = jobs.store["j1"]!!.copy(speakerRoles = endorserBinding())
        transcriber.fetchedTranscript = transcript()
        // A different binding is on offer — it must NOT be used (resolve should not be called).
        speakerAttribution.resolution =
            SpeakerResolution(mapOf("X" to SpeakerAssignment(SpeakerRole.SUBJECT)), 0.95)

        val result = service.rerunJob("j1", full = false).valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        assertEquals(endorserBinding(), extractor.lastSpeakerRoles)
        assertEquals(null, speakerAttribution.resolvedFor)
    }

    @Test
    fun `full rerun clears the binding so a fresh transcription re-resolves`() {
        seed(avAsset("a1"))
        seedCompletedJob()
        jobs.store["j1"] = jobs.store["j1"]!!.copy(speakerRoles = endorserBinding())

        val result = service.rerunJob("j1", full = true).valueOrNull()!!

        assertEquals(Stage2JobStatus.TRANSCRIBING, result.status)
        assertEquals(null, result.speakerRoles)
    }

    @Test
    fun `updateSpeakerRoles saves the operator binding and re-extracts, reusing it (not re-resolving)`() {
        seed(avAsset("a1"))
        seedCompletedJob()
        seedOldClaim()
        transcriber.fetchedTranscript = transcript()
        // Attribution would offer a different binding — it must NOT be consulted.
        speakerAttribution.resolution =
            SpeakerResolution(mapOf("X" to SpeakerAssignment(SpeakerRole.SUBJECT)), 0.95)

        val result = service.updateSpeakerRoles("j1", endorserBinding()).valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        assertEquals(endorserBinding(), result.speakerRoles)
        assertEquals(endorserBinding(), extractor.lastSpeakerRoles)
        assertEquals(null, speakerAttribution.resolvedFor)
        assertTrue(claims.store.keys.none { it == "c-old" })
        assertEquals(2, claims.store.size)
    }

    @Test
    fun `updateSpeakerRoles refuses a job that is not COMPLETED`() {
        seed(avAsset("a1"))
        seedTranscribingJob()

        val result = service.updateSpeakerRoles("j1", endorserBinding())

        assertTrue(result.errorOrNull() is DomainError.Conflict)
    }

    @Test
    fun `updateSpeakerRoles refuses when no transcript is stored`() {
        seed(avAsset("a1"))
        seedCompletedJob(transcriptUri = null)

        val result = service.updateSpeakerRoles("j1", endorserBinding())

        assertTrue(result.errorOrNull() is DomainError.Invalid)
    }

    // ---- §12.4 speaker-selection gate ---------------------------------------

    @Test
    fun `poll parks a low-confidence multi-speaker job for speaker selection`() {
        seed(avAsset("a1"))
        seedTranscribingJob()
        speakerAttribution.resolution =
            SpeakerResolution(
                mapOf("Speaker 1" to SpeakerAssignment(SpeakerRole.SUBJECT)),
                confidence = 0.4,
            )
        transcriber.pollResult =
            TranscriptionPoll.Done(twoSpeakerTranscript(), "gs://t/s1/a1/out.json")

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.AWAITING_SPEAKER_SELECTION, result.status)
        assertEquals("gs://t/s1/a1/out.json", result.transcriptUri)
        assertEquals(setOf("Speaker 0", "Speaker 1"), result.speakerSamples?.keys)
        assertTrue(claims.store.isEmpty()) // no extraction until the operator resolves
    }

    @Test
    fun `poll leaves a parked job untouched (waits for the operator)`() {
        seed(avAsset("a1"))
        jobs.store["j1"] =
            seedTranscribingJob()
                .copy(status = Stage2JobStatus.AWAITING_SPEAKER_SELECTION, transcriptUri = "gs://t")

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.AWAITING_SPEAKER_SELECTION, result.status)
        assertTrue(jobs.saves.isEmpty())
    }

    @Test
    fun `resolveSpeakers tags self, weights the rest as the asset relationship, and extracts`() {
        seed(avAsset("a1")) // MANAGER_ENDORSEMENT → relationship MANAGER
        jobs.store["j1"] =
            seedTranscribingJob()
                .copy(
                    status = Stage2JobStatus.AWAITING_SPEAKER_SELECTION,
                    transcriptUri = "gs://t/s1/a1/out.json",
                    speakerSamples = mapOf("Speaker 0" to "…", "Speaker 1" to "…"),
                )
        transcriber.fetchedTranscript = twoSpeakerTranscript()

        val result = service.resolveSpeakers("j1", listOf("Speaker 1")).valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        val binding = extractor.lastSpeakerRoles!!
        assertEquals(SpeakerRole.SUBJECT, binding["Speaker 1"]!!.role)
        assertEquals(SpeakerRole.ENDORSER, binding["Speaker 0"]!!.role)
        assertEquals(Relationship.MANAGER, binding["Speaker 0"]!!.relationship)
        assertEquals(
            setOf("Speaker 0", "Speaker 1"),
            result.speakerSamples?.keys,
        ) // kept for the post-completion Speakers editor
    }

    @Test
    fun `resolveSpeakers with no self labels treats everyone as the other party (subject absent)`() {
        seed(avAsset("a1"))
        jobs.store["j1"] =
            seedTranscribingJob()
                .copy(
                    status = Stage2JobStatus.AWAITING_SPEAKER_SELECTION,
                    transcriptUri = "gs://t/s1/a1/out.json",
                )
        transcriber.fetchedTranscript = twoSpeakerTranscript()

        val result = service.resolveSpeakers("j1", emptyList()).valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        assertTrue(extractor.lastSpeakerRoles!!.values.all { it.role == SpeakerRole.ENDORSER })
    }

    @Test
    fun `resolveSpeakers refuses a job that is not awaiting selection`() {
        seed(avAsset("a1"))
        seedTranscribingJob()

        val result = service.resolveSpeakers("j1", listOf("Speaker 1"))

        assertTrue(result.errorOrNull() is DomainError.Conflict)
    }

    @Test
    fun `updateSpeakerRoles refuses a document job`() {
        seed(docAsset("d1"))
        seedDocJob(status = Stage2JobStatus.COMPLETED)

        val result = service.updateSpeakerRoles("j1", endorserBinding())

        assertTrue(result.errorOrNull() is DomainError.Conflict)
    }

    // ---- pollAll ------------------------------------------------------------

    @Test
    fun `pollAll advances every active job and passes terminal ones through`() {
        seed(avAsset("a1"), avAsset("a2"))
        seedTranscribingJob("j1", "a1")
        seedTranscribingJob("j2", "a2")
        jobs.store["j3"] =
            Stage2Job(
                id = "j3",
                subjectId = "s1",
                assetId = "a1",
                modality = AssetModality.AUDIO,
                status = Stage2JobStatus.FAILED,
                error = "old failure",
                createdAt = Instant.now(),
            )
        transcriber.pollResult = TranscriptionPoll.Done(transcript(), null)

        val polled = service.pollAll("s1")

        assertEquals(3, polled.size)
        val byId = polled.associateBy { it.id }
        assertEquals(Stage2JobStatus.COMPLETED, byId["j1"]!!.status)
        assertEquals(Stage2JobStatus.COMPLETED, byId["j2"]!!.status)
        assertEquals(Stage2JobStatus.FAILED, byId["j3"]!!.status)
        assertEquals(4, claims.store.size)
    }

    @Test
    fun `pollAll returns jobs unchanged on transport errors`() {
        seedTranscribingJob("j1")
        transcriber.pollThrows = true

        val polled = service.pollAll("s1")

        assertEquals(Stage2JobStatus.TRANSCRIBING, polled.single().status)
        assertTrue(jobs.saves.isEmpty())
    }

    @Test
    fun `a transport error during poll leaves the job pollable`() {
        seedTranscribingJob()
        transcriber.pollThrows = true

        val result = service.poll("j1")

        assertTrue(result.errorOrNull() is DomainError.Invalid)
        assertEquals(Stage2JobStatus.TRANSCRIBING, jobs.store["j1"]!!.status)
        assertTrue(jobs.saves.isEmpty())
    }

    // ---- document lane (IMAGE/DOCUMENT — no transcription leg) ----------------

    private fun docAsset(
        id: String = "d1",
        modality: AssetModality = AssetModality.IMAGE,
        mimeType: String? = "image/png",
    ) =
        Asset(
            id = id,
            subjectId = "s1",
            title = "Cloud architect certificate",
            modality = modality,
            sourceClass = ContentType.CERTIFICATE.sourceClass,
            contentType = ContentType.CERTIFICATE,
            relationship = Relationship.SELF,
            authenticityPrior = AuthenticityTier.HIGH,
            gcsUri = "gs://intake/s1/$id.png",
            mimeType = mimeType,
            sizeBytes = 1_234,
            consentStatus = ConsentStatus.GRANTED,
            uploadStatus = AssetUploadStatus.STORED,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun seedDocJob(
        id: String = "j1",
        assetId: String = "d1",
        status: Stage2JobStatus = Stage2JobStatus.PENDING,
    ): Stage2Job {
        val job =
            Stage2Job(
                id = id,
                subjectId = "s1",
                assetId = assetId,
                modality = AssetModality.IMAGE,
                status = status,
                createdAt = Instant.now(),
            )
        jobs.store[id] = job
        return job
    }

    @Test
    fun `process creates PENDING document jobs without submitting transcription`() {
        seedSubject()
        seedManifest()
        seed(
            avAsset("a1"),
            docAsset("d1"),
            docAsset("d2", modality = AssetModality.DOCUMENT, mimeType = "application/pdf"),
        )

        val created = service.process("s1", actor).valueOrNull()!!

        val byAsset = created.associateBy { it.assetId }
        assertEquals(Stage2JobStatus.TRANSCRIBING, byAsset["a1"]!!.status)
        assertEquals(Stage2JobStatus.PENDING, byAsset["d1"]!!.status)
        assertEquals(Stage2JobStatus.PENDING, byAsset["d2"]!!.status)
        assertTrue(byAsset["d1"]!!.externalOperationId == null)
        assertEquals(listOf("a1"), transcriber.submitted)
        assertTrue(manifests.store["s1"]!!.stage2StartedAt != null)
    }

    @Test
    fun `process births a FAILED document job for an unsupported source and continues the rest`() {
        seedSubject()
        seedManifest()
        seed(avAsset("a1"), docAsset("d1", mimeType = "application/msword"))
        documents.unsupported =
            "Unsupported document type for Gemini extraction: application/msword"

        val created = service.process("s1", actor).valueOrNull()!!

        val byAsset = created.associateBy { it.assetId }
        assertEquals(Stage2JobStatus.FAILED, byAsset["d1"]!!.status)
        assertEquals(documents.unsupported, byAsset["d1"]!!.error)
        assertTrue(byAsset["d1"]!!.finishedAt != null)
        assertEquals(Stage2JobStatus.TRANSCRIBING, byAsset["a1"]!!.status)
    }

    @Test
    fun `poll runs document extraction and completes a PENDING document job`() {
        seed(docAsset("d1"))
        seedDocJob()
        seedOldClaim(assetId = "d1")

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.COMPLETED, result.status)
        assertEquals(2, result.claimCount)
        assertEquals(null, result.transcriptUri)
        assertTrue(result.startedAt != null && result.finishedAt != null)
        assertEquals("image/png", extractor.lastDocumentMime)
        assertTrue(claims.store.keys.none { it == "c-old" })
        val stored = claims.store.values.toList()
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.id.isNotBlank() && it.assetId == "d1" })
        assertTrue(stored.all { it.authenticityTier == AuthenticityTier.HIGH })
        assertTrue(stored.all { it.speaker == null })
    }

    @Test
    fun `poll leaves an EXTRACTING document job untouched`() {
        seed(docAsset("d1"))
        seedDocJob(status = Stage2JobStatus.EXTRACTING)

        val result = service.poll("j1")

        assertEquals(Stage2JobStatus.EXTRACTING, result.valueOrNull()!!.status)
        assertTrue(jobs.saves.isEmpty())
        assertTrue(claims.store.isEmpty())
    }

    @Test
    fun `poll fails the document job verbatim when the bytes cannot be read`() {
        seed(docAsset("d1"))
        seedDocJob()
        documents.readThrows = true

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("stored document not found"))
        assertTrue(claims.store.isEmpty())
    }

    @Test
    fun `poll fails the document job when extraction throws`() {
        seed(docAsset("d1"))
        seedDocJob()
        extractor.throws = true

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Document extraction failed"))
        assertTrue(claims.store.isEmpty())
    }

    @Test
    fun `poll re-checks document support before extracting`() {
        seed(docAsset("d1"))
        seedDocJob()
        documents.unsupported = "Unsupported document type for Gemini extraction: unknown"

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.FAILED, result.status)
        assertEquals(documents.unsupported, result.error)
    }

    @Test
    fun `poll fails the document job when its asset no longer exists`() {
        seedDocJob()

        val result = service.poll("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("no longer exists"))
    }

    @Test
    fun `retryJob resets a FAILED document job to PENDING and the next poll completes it`() {
        seed(docAsset("d1"))
        seedDocJob(status = Stage2JobStatus.FAILED)
        jobs.store["j1"] = jobs.store["j1"]!!.copy(error = "boom", finishedAt = Instant.now())

        val retried = service.retryJob("j1").valueOrNull()!!

        assertEquals(Stage2JobStatus.PENDING, retried.status)
        assertEquals(null, retried.error)
        assertEquals(null, retried.finishedAt)
        assertTrue(transcriber.attempts.isEmpty())

        val polled = service.poll("j1").valueOrNull()!!
        assertEquals(Stage2JobStatus.COMPLETED, polled.status)
        assertEquals(2, polled.claimCount)
    }

    @Test
    fun `rerun resets a COMPLETED document job to PENDING in both modes and keeps old claims until completion`() {
        seed(docAsset("d1"))
        seedDocJob(status = Stage2JobStatus.COMPLETED)
        jobs.store["j1"] = jobs.store["j1"]!!.copy(claimCount = 2, finishedAt = Instant.now())
        seedOldClaim(assetId = "d1")

        val extractMode = service.rerunJob("j1", full = false).valueOrNull()!!
        assertEquals(Stage2JobStatus.PENDING, extractMode.status)
        assertEquals(null, extractMode.claimCount)
        assertTrue(claims.store.containsKey("c-old"))

        jobs.store["j1"] = jobs.store["j1"]!!.copy(status = Stage2JobStatus.COMPLETED)
        val fullMode = service.rerunJob("j1", full = true).valueOrNull()!!
        assertEquals(Stage2JobStatus.PENDING, fullMode.status)
        assertTrue(transcriber.attempts.isEmpty())

        service.poll("j1")
        assertTrue(claims.store.keys.none { it == "c-old" })
        assertEquals(2, claims.store.size)
    }

    @Test
    fun `pollAll advances PENDING document jobs alongside A_V jobs`() {
        seed(avAsset("a1"), docAsset("d1"))
        seedTranscribingJob("j1", "a1")
        seedDocJob("j2", "d1")
        transcriber.pollResult = TranscriptionPoll.Done(transcript(), null)

        val polled = service.pollAll("s1")

        val byId = polled.associateBy { it.id }
        assertEquals(Stage2JobStatus.COMPLETED, byId["j1"]!!.status)
        assertEquals(Stage2JobStatus.COMPLETED, byId["j2"]!!.status)
        assertEquals(4, claims.store.size)
    }
}

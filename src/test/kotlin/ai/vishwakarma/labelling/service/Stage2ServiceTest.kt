package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.ProviderRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage2.ClaimExtractor
import ai.vishwakarma.labelling.stage2.Transcriber
import ai.vishwakarma.labelling.stage2.Transcript
import ai.vishwakarma.labelling.stage2.TranscriptSegment
import ai.vishwakarma.labelling.stage2.TranscriptionPoll
import arrow.core.Either
import com.google.cloud.firestore.Firestore
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

private class FakeExtractor :
    ClaimExtractor(
        GeminiDrafting(
            AppProperties(),
            ProviderService(ProviderRepository(mock(Firestore::class.java)))
        )
    ) {
    var throws = false

    override fun extract(
        subjectId: String,
        asset: Asset,
        transcript: Transcript,
        subjectName: String?,
    ): List<Claim> {
        if (throws) error("extraction blew up")
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
    private val transcriber = StubTranscriber()
    private val extractor = FakeExtractor()
    private val service =
        Stage2Service(subjects, manifests, assets, jobs, claims, transcriber, extractor)

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
    fun `process skips non-consented assets and non A_V modalities`() {
        seedSubject()
        seedManifest()
        seed(
            avAsset("a1"),
            avAsset("a2", consentStatus = ConsentStatus.PENDING),
            avAsset("a3", consentStatus = ConsentStatus.REVOKED),
            avAsset("a4", modality = AssetModality.DOCUMENT),
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
        seed(avAsset(modality = AssetModality.DOCUMENT))

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

    private fun seedOldClaim(id: String = "c-old") {
        claims.store[id] =
            Claim(
                id = id,
                subjectId = "s1",
                assetId = "a1",
                claimType = ClaimType.EPISODE,
                text = "stale claim from the previous run",
            )
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
}

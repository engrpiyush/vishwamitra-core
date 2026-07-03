package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage2.ClaimExtractor
import ai.vishwakarma.labelling.stage2.Transcriber
import ai.vishwakarma.labelling.stage2.TranscriptionPoll
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Orchestrates the Stage 2 A/V → Claims slice: [process] consumes a sealed manifest (stamping
 * [ai.vishwakarma.labelling.domain.IntakeManifest.stage2StartedAt] — the permanent-seal lock) and
 * submits one transcription LRO per eligible AUDIO/VIDEO asset; [poll] drives each [Stage2Job] to a
 * terminal state, running Claude claim extraction when the transcript lands. Submit-then-poll
 * mirrors [TrainingService.pollJob] — there is no background scheduler; polling is
 * endpoint/UI-triggered.
 */
@Service
class Stage2Service(
    private val subjects: SubjectRepository,
    private val manifests: IntakeManifestRepository,
    private val assets: AssetRepository,
    private val jobs: Stage2JobRepository,
    private val claims: ClaimRepository,
    private val transcriber: Transcriber,
    private val extractor: ClaimExtractor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Start Stage 2 for a subject: guard (sealed, not already started, has eligible A/V assets),
     * stamp the permanent lock, then create + submit one job per eligible asset. A single asset's
     * submit failure marks that job FAILED without aborting the others.
     */
    fun process(subjectId: String, actor: String?): Either<DomainError, List<Stage2Job>> {
        val subject =
            subjects.findById(subjectId)
                ?: return DomainError.NotFound("Subject $subjectId not found").left()
        val manifest =
            manifests.findBySubject(subjectId)
                ?: return DomainError.NotFound("No manifest for subject $subjectId").left()
        if (!manifest.sealed)
            return DomainError.Conflict("Manifest must be sealed before Stage 2 processing").left()
        if (manifest.stage2StartedAt != null)
            return DomainError.Conflict("Stage 2 already started at ${manifest.stage2StartedAt}")
                .left()
        val eligible = assets.findBySubject(subjectId).filter { it.eligible() }
        // Refuse before stamping: the lock is permanent, so don't burn it on a no-op.
        if (eligible.isEmpty())
            return DomainError.Invalid(
                    "No eligible AUDIO/VIDEO assets to process (must be STORED with consent)"
                )
                .left()
        val now = Instant.now()
        manifests.save(manifest.copy(stage2StartedAt = now, updatedAt = now))
        log.info("Stage 2 started for subject {} — {} A/V asset(s)", subjectId, eligible.size)
        val hints = nameHints(subject)
        return eligible.map { submitJob(subjectId, actor, it, hints) }.right()
    }

    /**
     * Poll one job's transcription operation and advance it. Terminal jobs are a no-op; a transport
     * error persists nothing (the job stays pollable). On transcript arrival: extract claims, write
     * them to the ledger, complete the job.
     */
    fun poll(jobId: String): Either<DomainError, Stage2Job> {
        val job = jobs.findById(jobId) ?: return DomainError.NotFound("Job $jobId not found").left()
        if (job.status == Stage2JobStatus.COMPLETED || job.status == Stage2JobStatus.FAILED)
            return job.right()
        // Extraction runs synchronously inside whichever poll request saw the transcript land; a
        // concurrent poll (the UI auto-polls every few seconds) must not start a second extraction
        // — that duplicates claims (observed live: 4 overlapping polls → 4x claims).
        if (job.status == Stage2JobStatus.EXTRACTING) return job.right()
        val op =
            job.externalOperationId
                ?: return DomainError.Invalid("Job has no transcription operation").left()
        val outcome =
            try {
                transcriber.poll(op)
            } catch (e: Exception) {
                return DomainError.Invalid("Status poll failed: ${e.message}").left()
            }
        return when (outcome) {
            is TranscriptionPoll.Running -> job.right()
            is TranscriptionPoll.Failed ->
                (if (outcome.retryableWithNextDecoding) retryNextDecoding(job, outcome.error)
                    else failJob(job, outcome.error))
                    .right()
            is TranscriptionPoll.Done -> extractClaims(job, outcome).right()
        }
    }

    /**
     * Operator retry for a FAILED job (transient causes: IAM propagation, provider hiccups): fresh
     * transcription of the same asset from decoding attempt 0, on the same job record.
     */
    fun retryJob(jobId: String): Either<DomainError, Stage2Job> {
        val job = jobs.findById(jobId) ?: return DomainError.NotFound("Job $jobId not found").left()
        if (job.status != Stage2JobStatus.FAILED)
            return DomainError.Conflict("Only FAILED jobs can be retried (job is ${job.status})")
                .left()
        val asset =
            assets.findById(job.assetId)
                ?: return DomainError.NotFound("Asset ${job.assetId} no longer exists").left()
        return try {
            val op =
                transcriber.submit(
                    job.subjectId,
                    asset.id,
                    asset.gcsUri.orEmpty(),
                    asset.mimeType,
                    hints = subjectHints(job.subjectId),
                )
            job.copy(
                    status = Stage2JobStatus.TRANSCRIBING,
                    externalOperationId = op,
                    decodingAttempt = 0,
                    error = null,
                    claimCount = null,
                    startedAt = Instant.now(),
                    finishedAt = null,
                )
                .also { jobs.save(it) }
                .right()
        } catch (e: Exception) {
            DomainError.Invalid("Retry submit failed: ${e.message}").left()
        }
    }

    /** Delete-on-request cascade: claims and jobs derived from a subject die with it. */
    fun purgeSubject(subjectId: String) {
        claims.findBySubject(subjectId).forEach { claims.delete(it.id) }
        jobs.findBySubject(subjectId).forEach { jobs.delete(it.id) }
    }

    /**
     * An encoding rejection surfaced from the operation (decoding happens at run time, not at
     * submit): resubmit the same asset with the next decoding candidate. Exhausted candidates — or
     * a vanished asset — fall through to a terminal failure carrying the provider's error.
     */
    private fun retryNextDecoding(job: Stage2Job, error: String): Stage2Job {
        val asset = assets.findById(job.assetId) ?: return failJob(job, error)
        val next = job.decodingAttempt + 1
        return try {
            val op =
                transcriber.submit(
                    job.subjectId,
                    asset.id,
                    asset.gcsUri.orEmpty(),
                    asset.mimeType,
                    next,
                    subjectHints(job.subjectId),
                )
            log.warn(
                "Job {}: decoding attempt {} rejected ({}); resubmitted as attempt {}",
                job.id,
                job.decodingAttempt,
                error,
                next,
            )
            job.copy(
                    status = Stage2JobStatus.TRANSCRIBING,
                    externalOperationId = op,
                    decodingAttempt = next,
                    startedAt = Instant.now(),
                )
                .also { jobs.save(it) }
        } catch (e: Exception) {
            log.warn("Job {}: decoding attempts exhausted ({})", job.id, e.message)
            failJob(job, error)
        }
    }

    private fun failJob(job: Stage2Job, error: String): Stage2Job =
        job.copy(status = Stage2JobStatus.FAILED, error = error, finishedAt = Instant.now()).also {
            jobs.save(it)
        }

    /**
     * STT adaptation hints: the subject's name(s), so recognition doesn't garble the one term every
     * claim depends on (observed live: "Piyush Vishwakarma" → "piyusha karma").
     */
    private fun nameHints(subject: Subject): List<String> =
        listOfNotNull(
            subject.displayName.takeIf { it.isNotBlank() },
            subject.handle?.takeIf { it.isNotBlank() },
        )

    private fun subjectHints(subjectId: String): List<String> =
        subjects.findById(subjectId)?.let { nameHints(it) } ?: emptyList()

    /**
     * Poll every non-terminal job for a subject once — the UI's auto-poll loop and "Poll all"
     * button. A job whose poll fails on transport is returned unchanged (still pollable next
     * cycle); terminal jobs pass through untouched.
     */
    fun pollAll(subjectId: String): List<Stage2Job> =
        jobs.findBySubject(subjectId).map { job ->
            if (job.status == Stage2JobStatus.COMPLETED || job.status == Stage2JobStatus.FAILED) job
            else poll(job.id).fold({ job }, { it })
        }

    fun job(jobId: String): Stage2Job? = jobs.findById(jobId)

    fun listJobs(subjectId: String): List<Stage2Job> = jobs.findBySubject(subjectId)

    fun listClaims(subjectId: String): List<Claim> = claims.findBySubject(subjectId)

    /** AUDIO/VIDEO, bytes present, and consent that permits processing. */
    private fun Asset.eligible(): Boolean =
        modality in AV_MODALITIES &&
            consentStatus !in BLOCKED_CONSENT &&
            uploadStatus == AssetUploadStatus.STORED &&
            !gcsUri.isNullOrBlank()

    private fun submitJob(
        subjectId: String,
        actor: String?,
        asset: Asset,
        hints: List<String>,
    ): Stage2Job {
        val pending =
            Stage2Job(
                id = jobs.newId(),
                subjectId = subjectId,
                assetId = asset.id,
                modality = asset.modality,
                status = Stage2JobStatus.PENDING,
                createdBy = actor,
                createdAt = Instant.now(),
            )
        jobs.save(pending)
        return try {
            val op =
                transcriber.submit(
                    subjectId,
                    asset.id,
                    asset.gcsUri.orEmpty(),
                    asset.mimeType,
                    hints = hints,
                )
            pending
                .copy(
                    status = Stage2JobStatus.TRANSCRIBING,
                    externalOperationId = op,
                    startedAt = Instant.now(),
                )
                .also { jobs.save(it) }
        } catch (e: Exception) {
            log.warn("Transcription submit failed for asset {}: {}", asset.id, e.message)
            pending
                .copy(
                    status = Stage2JobStatus.FAILED,
                    error = "Transcription submit failed: ${e.message}",
                    finishedAt = Instant.now(),
                )
                .also { jobs.save(it) }
        }
    }

    private fun extractClaims(job: Stage2Job, done: TranscriptionPoll.Done): Stage2Job {
        val extracting =
            job.copy(status = Stage2JobStatus.EXTRACTING, transcriptUri = done.transcriptUri).also {
                jobs.save(it)
            }
        val asset =
            assets.findById(job.assetId)
                ?: return extracting
                    .copy(
                        status = Stage2JobStatus.FAILED,
                        error = "Asset ${job.assetId} no longer exists",
                        finishedAt = Instant.now(),
                    )
                    .also { jobs.save(it) }
        return try {
            val subjectName = subjects.findById(job.subjectId)?.displayName
            val extracted = extractor.extract(job.subjectId, asset, done.transcript, subjectName)
            extracted.map { it.copy(id = claims.newId()) }.forEach { claims.save(it) }
            log.info("Job {} extracted {} claim(s) from asset {}", job.id, extracted.size, asset.id)
            extracting
                .copy(
                    status = Stage2JobStatus.COMPLETED,
                    claimCount = extracted.size,
                    finishedAt = Instant.now(),
                )
                .also { jobs.save(it) }
        } catch (e: Exception) {
            log.warn("Claim extraction failed for job {}", job.id, e)
            extracting
                .copy(
                    status = Stage2JobStatus.FAILED,
                    error = "Claim extraction failed: ${e.message}",
                    finishedAt = Instant.now(),
                )
                .also { jobs.save(it) }
        }
    }

    companion object {
        private val AV_MODALITIES = setOf(AssetModality.AUDIO, AssetModality.VIDEO)
        private val BLOCKED_CONSENT = setOf(ConsentStatus.PENDING, ConsentStatus.REVOKED)
    }
}

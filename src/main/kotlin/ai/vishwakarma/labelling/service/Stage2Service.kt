package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage2.ClaimExtractor
import ai.vishwakarma.labelling.stage2.DocumentSource
import ai.vishwakarma.labelling.stage2.SpeakerAttribution
import ai.vishwakarma.labelling.stage2.SpeakerResolution
import ai.vishwakarma.labelling.stage2.Transcriber
import ai.vishwakarma.labelling.stage2.Transcript
import ai.vishwakarma.labelling.stage2.TranscriptionPoll
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Orchestrates Stage 2 → Claims: [process] consumes a sealed manifest (stamping
 * [ai.vishwakarma.labelling.domain.IntakeManifest.stage2StartedAt] — the permanent-seal lock) and
 * creates one [Stage2Job] per eligible asset; [poll] drives each job to a terminal state.
 * AUDIO/VIDEO submit a transcription LRO and extract when the transcript lands; IMAGE/DOCUMENT have
 * no transcription leg — they wait PENDING and one poll runs the multimodal (OCR + extraction)
 * Gemini call synchronously. Submit-then-poll mirrors [TrainingService.pollJob] — there is no
 * background scheduler; polling is endpoint/UI-triggered.
 */
@Service
class Stage2Service(
    private val subjects: SubjectRepository,
    private val manifests: IntakeManifestRepository,
    private val assets: AssetRepository,
    private val jobs: Stage2JobRepository,
    private val claims: ClaimRepository,
    private val reviews: ClaimReviewRepository,
    private val transcriber: Transcriber,
    private val extractor: ClaimExtractor,
    private val speakerAttribution: SpeakerAttribution,
    private val documents: DocumentSource,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Start Stage 2 for a subject: guard (sealed, not already started, has eligible assets), stamp
     * the permanent lock, then create one job per eligible asset (A/V submit their LRO;
     * IMAGE/DOCUMENT wait PENDING for the poll loop). A single asset's submit failure marks that
     * job FAILED without aborting the others.
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
                    "No eligible AUDIO/VIDEO/IMAGE/DOCUMENT assets to process " +
                        "(must be STORED with consent)"
                )
                .left()
        val now = Instant.now()
        manifests.save(manifest.copy(stage2StartedAt = now, updatedAt = now))
        log.info("Stage 2 started for subject {} — {} asset(s)", subjectId, eligible.size)
        val hints = nameHints(subject)
        return eligible.map { submitJob(subjectId, actor, it, hints) }.right()
    }

    /**
     * Advance one job. Terminal jobs are a no-op; a transport error persists nothing (the job stays
     * pollable). A/V: check the transcription operation and, on transcript arrival, extract claims.
     * IMAGE/DOCUMENT: run the multimodal extraction right here — one poll takes the job PENDING →
     * EXTRACTING → terminal.
     */
    fun poll(jobId: String): Either<DomainError, Stage2Job> {
        val job = jobs.findById(jobId) ?: return DomainError.NotFound("Job $jobId not found").left()
        if (job.status == Stage2JobStatus.COMPLETED || job.status == Stage2JobStatus.FAILED)
            return job.right()
        // Extraction runs synchronously inside whichever poll request saw the transcript land; a
        // concurrent poll (the UI auto-polls every few seconds) must not start a second extraction
        // — that duplicates claims (observed live: 4 overlapping polls → 4x claims).
        if (job.status == Stage2JobStatus.EXTRACTING) return maybeReclaimStuck(job).right()
        // Parked for operator speaker-selection (§12.4) — the auto-poll loop must not advance it;
        // it
        // waits for resolveSpeakers to supply the confirmed subject label(s).
        if (job.status == Stage2JobStatus.AWAITING_SPEAKER_SELECTION) return job.right()
        if (job.modality in DOC_MODALITIES) {
            // PENDING is the only non-terminal doc state left; saving EXTRACTING here is the
            // same double-run guard as above for overlapping poll-all requests.
            val extracting =
                job.copy(
                        status = Stage2JobStatus.EXTRACTING,
                        startedAt = job.startedAt ?: Instant.now(),
                        extractingSince = Instant.now(),
                    )
                    .also { jobs.save(it) }
            return runDocumentExtraction(extracting).right()
        }
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
        reviewLocked(job.subjectId)?.let {
            return it.left()
        }
        if (job.status != Stage2JobStatus.FAILED)
            return DomainError.Conflict("Only FAILED jobs can be retried (job is ${job.status})")
                .left()
        val asset =
            assets.findById(job.assetId)
                ?: return DomainError.NotFound("Asset ${job.assetId} no longer exists").left()
        if (job.modality in DOC_MODALITIES) return resetToPending(job).right()
        return resubmit(job, asset)
    }

    /**
     * Re-run a COMPLETED job — after a model/prompt change, or when claim quality disappoints.
     * [full] re-transcribes from scratch; otherwise only extraction re-runs from the stored
     * transcript (falling back to full when none was stored, e.g. dry-run-era jobs). Either way the
     * asset's previous claims are REPLACED at completion, never appended.
     */
    fun rerunJob(jobId: String, full: Boolean): Either<DomainError, Stage2Job> {
        val job = jobs.findById(jobId) ?: return DomainError.NotFound("Job $jobId not found").left()
        reviewLocked(job.subjectId)?.let {
            return it.left()
        }
        if (job.status != Stage2JobStatus.COMPLETED)
            return DomainError.Conflict(
                    "Only COMPLETED jobs can be re-run (job is ${job.status}; use Retry for FAILED)"
                )
                .left()
        val asset =
            assets.findById(job.assetId)
                ?: return DomainError.NotFound("Asset ${job.assetId} no longer exists").left()
        // Documents have no transcript leg — re-extract and full are the same run ([full] is
        // ignored). Deliberate delta vs the A/V extract-mode fetch-first behavior: a doc re-run
        // that later fails leaves the job FAILED (old claims intact — replacement happens only at
        // completion — and Retry is available).
        if (job.modality in DOC_MODALITIES) return resetToPending(job).right()
        val transcriptUri = job.transcriptUri
        if (full || transcriptUri == null) return resubmit(job, asset)
        // Fetch BEFORE touching job state: a failed fetch leaves the job COMPLETED and its
        // existing claims intact — nothing is lost by a re-run that couldn't start.
        val transcript =
            try {
                transcriber.fetchTranscript(transcriptUri)
            } catch (e: Exception) {
                return DomainError.Invalid("Could not fetch stored transcript: ${e.message}").left()
            }
        val extracting =
            job.copy(
                    status = Stage2JobStatus.EXTRACTING,
                    claimCount = null,
                    error = null,
                    finishedAt = null,
                    extractingSince = Instant.now(),
                )
                .also { jobs.save(it) }
        return runExtraction(extracting, transcript).right()
    }

    /**
     * §12.4 Phase B: replace a COMPLETED A/V job's operator speaker→role binding, then re-extract
     * from the stored transcript so the corrected attribution re-weights the claims (SUBJECT →
     * self-report, ENDORSER → third-party, INTERVIEWER/OTHER dropped). Delegates to [rerunJob]'s
     * re-extract path, which reuses the just-saved binding (rather than re-resolving) and — because
     * claims are replaced only at completion — leaves the old claims intact if the transcript fetch
     * fails. An empty binding clears attribution, so the next re-extract re-resolves it afresh.
     */
    fun updateSpeakerRoles(
        jobId: String,
        roles: Map<String, SpeakerAssignment>,
    ): Either<DomainError, Stage2Job> {
        val job = jobs.findById(jobId) ?: return DomainError.NotFound("Job $jobId not found").left()
        reviewLocked(job.subjectId)?.let {
            return it.left()
        }
        if (job.modality !in AV_MODALITIES)
            return DomainError.Conflict("Speaker roles apply to audio/video jobs only").left()
        if (job.status != Stage2JobStatus.COMPLETED)
            return DomainError.Conflict(
                    "Speaker roles can be edited only on a COMPLETED job (job is ${job.status})"
                )
                .left()
        if (job.transcriptUri == null)
            return DomainError.Invalid(
                    "No stored transcript to re-extract from — run a full Re-run first"
                )
                .left()
        jobs.save(job.copy(speakerRoles = roles.ifEmpty { null }))
        return rerunJob(jobId, full = false)
    }

    /**
     * IMAGE/DOCUMENT Retry/Re-run: back to PENDING on the same record — the auto-poll loop runs the
     * extraction, the one execution path for the lane.
     */
    private fun resetToPending(job: Stage2Job): Stage2Job =
        job.copy(
                status = Stage2JobStatus.PENDING,
                error = null,
                claimCount = null,
                startedAt = null,
                finishedAt = null,
            )
            .also { jobs.save(it) }

    /** Fresh transcription of [asset] on the same job record (Retry and full Re-run). */
    private fun resubmit(job: Stage2Job, asset: Asset): Either<DomainError, Stage2Job> =
        try {
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
                    // Fresh transcription may re-diarize with different labels, so the old
                    // speaker→role binding no longer applies — clear it and re-resolve on arrival
                    // (§12.4). Re-extract Re-run does NOT come through here, so it keeps the
                    // binding.
                    speakerRoles = null,
                )
                .also { jobs.save(it) }
                .right()
        } catch (e: Exception) {
            DomainError.Invalid("Re-submit failed: ${e.message}").left()
        }

    /**
     * §12.6: once the operator starts claim review the claims freeze — no re-transcribe /
     * re-extract (Retry, Re-run, speaker edits, gate resolution all refuse). Claim-lock is
     * permanent; an ADMIN can reopen the review to edit decisions, but not to re-extract.
     */
    private fun reviewLocked(subjectId: String): DomainError? =
        if (manifests.findBySubject(subjectId)?.reviewLockedAt != null)
            DomainError.Conflict(
                "Claims are locked for review and can no longer be re-transcribed or re-extracted"
            )
        else null

    /**
     * Delete-on-request cascade: claims (and their reviews) and jobs derived from a subject die.
     */
    fun purgeSubject(subjectId: String) {
        claims.findBySubject(subjectId).forEach {
            reviews.delete(it.id)
            claims.delete(it.id)
        }
        jobs.findBySubject(subjectId).forEach { jobs.delete(it.id) }
    }

    /**
     * Per-asset analogue of [purgeSubject]: delete the claims and job(s) derived from one asset —
     * used when consent for that asset is revoked (§12.7 hardening), so the withdrawal reaches
     * everything derived. Idempotent (safe with no claims/jobs yet).
     */
    fun purgeAssetDerived(subjectId: String, assetId: String) {
        claims.findByAsset(assetId).forEach {
            reviews.delete(it.id)
            claims.delete(it.id)
        }
        jobs
            .findBySubject(subjectId)
            .filter { it.assetId == assetId }
            .forEach { jobs.delete(it.id) }
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

    /** A processable modality, bytes present, and consent that permits processing. */
    private fun Asset.eligible(): Boolean =
        (modality in AV_MODALITIES || modality in DOC_MODALITIES) &&
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
        if (asset.modality in DOC_MODALITIES) {
            // No LRO leg: a supportable document waits PENDING for the poll loop to extract it;
            // an unsupportable one is born FAILED with the verbatim reason (the "Transcription
            // submit failed" visibility, without burning a Gemini call).
            val unsupported = documents.supportError(asset) ?: return pending
            log.warn("Document job refused for asset {}: {}", asset.id, unsupported)
            return pending
                .copy(
                    status = Stage2JobStatus.FAILED,
                    error = unsupported,
                    finishedAt = Instant.now(),
                )
                .also { jobs.save(it) }
        }
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

    /**
     * A crash mid-extraction strands a job in EXTRACTING forever — the guard in [poll] makes every
     * later poll a no-op, and there is no scheduler. Reclaim it to FAILED once it has been
     * EXTRACTING longer than [AppProperties.Stage2.extractingTimeout] so operator Retry can re-run
     * it. That threshold must exceed the Cloud Run request timeout: a live extraction runs
     * synchronously inside one request, so anything older can only be a crash, never an in-flight
     * run. Jobs from before [Stage2Job.extractingSince] existed fall back to startedAt/createdAt.
     */
    private fun maybeReclaimStuck(job: Stage2Job): Stage2Job {
        val since = job.extractingSince ?: job.startedAt ?: job.createdAt ?: return job
        if (Duration.between(since, Instant.now()) <= props.stage2.extractingTimeout) return job
        log.warn("Reclaiming job {} stranded in EXTRACTING since {}", job.id, since)
        return job.copy(
                status = Stage2JobStatus.FAILED,
                error =
                    "Extraction stranded in EXTRACTING for over ${props.stage2.extractingTimeout} " +
                        "(likely a crash mid-extraction); Retry to re-extract.",
                finishedAt = Instant.now(),
            )
            .also { jobs.save(it) }
    }

    /**
     * Transcript landed: stamp it, then run §12.4 speaker attribution once. Single-speaker /
     * unresolvable → extract at asset level. Confident (≥ threshold) → auto-extract with the
     * binding. Unsure → park in AWAITING_SPEAKER_SELECTION for the operator to tag self.
     */
    private fun extractClaims(job: Stage2Job, done: TranscriptionPoll.Done): Stage2Job {
        // Claim the job (EXTRACTING) before the slow attribution call so a concurrent poll can't
        // double-run attribution + extraction (§9.1 concurrency guard); then attribute and either
        // extract (confident / single-speaker) or park for operator speaker-selection.
        val claimed =
            job.copy(
                    status = Stage2JobStatus.EXTRACTING,
                    transcriptUri = done.transcriptUri,
                    extractingSince = Instant.now(),
                )
                .also { jobs.save(it) }
        val asset =
            assets.findById(job.assetId)
                ?: return failJob(claimed, "Asset ${job.assetId} no longer exists")
        val subjectName = subjects.findById(job.subjectId)?.displayName
        val resolution = speakerAttribution.resolve(asset, done.transcript, subjectName)
        if (resolution == null)
            return runExtraction(claimed.copy(speakerRoles = null), done.transcript)
        // Multi-speaker: keep per-speaker samples on the job through completion so BOTH the gate
        // and
        // the post-completion Speakers editor can show "who said what" while re-assigning roles.
        val withSamples = claimed.copy(speakerSamples = speakerSamples(done.transcript))
        return if (resolution.confidence >= props.stage2.attributionConfidenceThreshold) {
            log.info(
                "Job {}: attribution confident ({}) — auto-extracting",
                job.id,
                resolution.confidence,
            )
            runExtraction(withSamples.copy(speakerRoles = resolution.binding), done.transcript)
        } else {
            parkForSpeakerSelection(withSamples, resolution)
        }
    }

    /** Park a low-confidence multi-speaker job for operator speaker-selection (§12.4). */
    private fun parkForSpeakerSelection(job: Stage2Job, resolution: SpeakerResolution): Stage2Job {
        log.info(
            "Job {}: attribution low-confidence ({}) — awaiting speaker selection",
            job.id,
            resolution.confidence,
        )
        return job.copy(
                status = Stage2JobStatus.AWAITING_SPEAKER_SELECTION,
                speakerRoles = resolution.binding, // the model's proposal, editable by the operator
            )
            .also { jobs.save(it) }
    }

    /**
     * §12.4 gate resolution: the operator tagged which diarized label(s) are the subject (self).
     * Selected labels become SUBJECT (self-report); every other speaker takes the asset's declared
     * relationship (third-party). No labels selected = the subject is not on this call. Then
     * extract from the stored transcript.
     */
    fun resolveSpeakers(jobId: String, selfLabels: List<String>): Either<DomainError, Stage2Job> {
        val job = jobs.findById(jobId) ?: return DomainError.NotFound("Job $jobId not found").left()
        reviewLocked(job.subjectId)?.let {
            return it.left()
        }
        if (job.status != Stage2JobStatus.AWAITING_SPEAKER_SELECTION)
            return DomainError.Conflict(
                    "Job is not awaiting speaker selection (it is ${job.status})"
                )
                .left()
        val asset =
            assets.findById(job.assetId)
                ?: return DomainError.NotFound("Asset ${job.assetId} no longer exists").left()
        val transcriptUri =
            job.transcriptUri
                ?: return DomainError.Invalid("Job has no stored transcript to extract").left()
        val transcript =
            try {
                transcriber.fetchTranscript(transcriptUri)
            } catch (e: Exception) {
                return DomainError.Invalid("Could not fetch stored transcript: ${e.message}").left()
            }
        val selected = selfLabels.toSet()
        val binding =
            transcript.segments
                .mapNotNull { it.speaker?.takeIf { s -> s.isNotBlank() } }
                .distinct()
                .associateWith { label ->
                    if (label in selected) SpeakerAssignment(SpeakerRole.SUBJECT)
                    else SpeakerAssignment(SpeakerRole.ENDORSER, asset.relationship)
                }
        // Keep speakerSamples so the post-completion Speakers editor can still show each speaker's
        // transcript when re-assigning roles.
        val bound = job.copy(speakerRoles = binding).also { jobs.save(it) }
        return runExtraction(bound, transcript).right()
    }

    /**
     * A/V lane: set EXTRACTING and extract [transcript] using the job's resolved speaker binding.
     */
    private fun runExtraction(job: Stage2Job, transcript: Transcript): Stage2Job {
        val asset =
            assets.findById(job.assetId)
                ?: return failJob(job, "Asset ${job.assetId} no longer exists")
        val subjectName = subjects.findById(job.subjectId)?.displayName
        val extracting =
            job.copy(status = Stage2JobStatus.EXTRACTING, extractingSince = Instant.now()).also {
                jobs.save(it)
            }
        return try {
            val extracted =
                extractor.extract(
                    extracting.subjectId,
                    asset,
                    transcript,
                    subjectName,
                    extracting.speakerRoles,
                )
            completeWithClaims(extracting, asset.id, extracted)
        } catch (e: Exception) {
            log.warn("Claim extraction failed for job {}", extracting.id, e)
            failJob(extracting, "Claim extraction failed: ${e.message}")
        }
    }

    /**
     * Per-speaker transcript for the §12.4 selection UIs (the gate and the Speakers editor) — the
     * stored [Stage2Job.speakerSamples] when present, else fetched + computed from the job's stored
     * transcript. The fallback covers jobs completed before samples were kept, so "View transcript"
     * works on any diarized job. Null when there's no transcript or the fetch fails.
     */
    fun speakerSamples(job: Stage2Job): Map<String, String>? {
        job.speakerSamples?.let {
            return it
        }
        val uri = job.transcriptUri ?: return null
        return try {
            speakerSamples(transcriber.fetchTranscript(uri)).ifEmpty { null }
        } catch (e: Exception) {
            log.warn("Could not load speaker samples for job {}: {}", job.id, e.message)
            null
        }
    }

    /**
     * Each diarized speaker's concatenated words (capped at [SAMPLE_CHARS]) for the selection UIs.
     */
    private fun speakerSamples(transcript: Transcript): Map<String, String> =
        transcript.segments
            .filter { !it.speaker.isNullOrBlank() }
            .groupBy { it.speaker!! }
            .mapValues { (_, segs) -> segs.joinToString(" ") { it.text }.take(SAMPLE_CHARS) }

    /**
     * IMAGE/DOCUMENT lane: read the stored bytes and run the multimodal (OCR + extraction) Gemini
     * call synchronously inside this poll request. The support re-check covers Retry — the asset
     * could have been unsupportable all along.
     */
    private fun runDocumentExtraction(job: Stage2Job): Stage2Job {
        val asset =
            assets.findById(job.assetId)
                ?: return failJob(job, "Asset ${job.assetId} no longer exists")
        documents.supportError(asset)?.let {
            return failJob(job, it)
        }
        return try {
            val payload = documents.read(asset)
            val subjectName = subjects.findById(job.subjectId)?.displayName
            val extracted =
                extractor.extractDocument(
                    job.subjectId,
                    asset,
                    payload.bytes,
                    payload.mimeType,
                    subjectName,
                )
            completeWithClaims(job, asset.id, extracted)
        } catch (e: Exception) {
            log.warn("Document extraction failed for job {}", job.id, e)
            failJob(job, "Document extraction failed: ${e.message}")
        }
    }

    /**
     * REPLACE the asset's previous claims and complete the job — a claim for this asset can only
     * come from an earlier run of it, so replacement makes completion idempotent (kills
     * crash-window duplicates too).
     */
    private fun completeWithClaims(
        job: Stage2Job,
        assetId: String,
        extracted: List<Claim>,
    ): Stage2Job {
        claims.findByAsset(assetId).forEach { claims.delete(it.id) }
        extracted.map { it.copy(id = claims.newId()) }.forEach { claims.save(it) }
        log.info("Job {} extracted {} claim(s) from asset {}", job.id, extracted.size, assetId)
        return job.copy(
                status = Stage2JobStatus.COMPLETED,
                claimCount = extracted.size,
                finishedAt = Instant.now(),
            )
            .also { jobs.save(it) }
    }

    companion object {
        private val AV_MODALITIES = setOf(AssetModality.AUDIO, AssetModality.VIDEO)
        private val DOC_MODALITIES = setOf(AssetModality.IMAGE, AssetModality.DOCUMENT)
        private val BLOCKED_CONSENT = setOf(ConsentStatus.PENDING, ConsentStatus.REVOKED)
        /**
         * Max stored per-speaker transcript length for the §12.4 selection gate (preview + full).
         */
        private const val SAMPLE_CHARS = 12_000
    }
}

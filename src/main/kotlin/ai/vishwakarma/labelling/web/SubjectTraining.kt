package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AssetUploadStatus
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus

/**
 * The subject-vocabulary bridge for the training surfaces (VA-32/33, product LLD §8) — pure
 * functions so the copy rules are unit-testable. Everything a subject template renders about
 * pipeline state comes through here: raw `Stage2JobStatus` names, error fields, and machinery
 * vocabulary never reach `templates/subject` (§12.3; the §8.2 table is the only bridge).
 */
object SubjectTraining {

    /** Which S2 (training home) phase applies (§8.1) — one screen, phase-aware rendering. */
    enum class TrainingPhase {
        /** Collecting uploads; nothing processed yet. */
        UPLOAD,
        /** Uploads submitted; jobs still working (or still being created). */
        PROCESSING,
        /** Every job settled and the review hasn't started — show the review CTA. */
        REVIEW_READY,
        /** Review started but not submitted — continue the wizard. */
        REVIEW_IN_PROGRESS,
        /** Review submitted — the whole training area is read-only (post-submit S2). */
        SUBMITTED,
    }

    fun phaseFor(manifest: IntakeManifest, jobs: List<Stage2Job>): TrainingPhase =
        when {
            manifest.reviewSubmittedAt != null -> TrainingPhase.SUBMITTED
            manifest.reviewLockedAt != null -> TrainingPhase.REVIEW_IN_PROGRESS
            manifest.stage2StartedAt == null && !manifest.sealed -> TrainingPhase.UPLOAD
            jobs.isNotEmpty() && jobs.all { it.status.isTerminal() } -> TrainingPhase.REVIEW_READY
            // Sealed-but-not-started (a submit that failed halfway) renders as PROCESSING; the
            // home page offers the submit action again, which skips the seal and re-processes.
            else -> TrainingPhase.PROCESSING
        }

    /**
     * The §8.2 friendly-state mapping — the only vocabulary a subject sees for a job. [chip] is the
     * app.css badge modifier (reusing the existing palette; ATTENTION deliberately renders muted,
     * not alarming — the copy already says nothing is needed from the subject).
     */
    enum class FriendlyState(val chip: String) {
        WORKING("info"),
        NEEDS_YOU("warning"),
        UNDERSTANDING("info"),
        DONE("success"),
        ATTENTION("muted"),
    }

    /** One row of the S4 progress list: friendly state + copy, never the raw status. */
    data class FriendlyJob(
        val jobId: String,
        val title: String,
        val state: FriendlyState,
        val label: String,
    )

    fun friendlyJob(job: Stage2Job, title: String): FriendlyJob {
        val av = job.modality == AssetModality.AUDIO || job.modality == AssetModality.VIDEO
        val (state, label) =
            when (job.status) {
                Stage2JobStatus.PENDING,
                Stage2JobStatus.TRANSCRIBING ->
                    FriendlyState.WORKING to
                        (if (av) "Listening to your recording…" else "Reading it through…")
                Stage2JobStatus.AWAITING_SPEAKER_SELECTION ->
                    FriendlyState.NEEDS_YOU to "Help us identify who's speaking"
                Stage2JobStatus.EXTRACTING ->
                    FriendlyState.UNDERSTANDING to "Understanding what was said…"
                Stage2JobStatus.COMPLETED -> FriendlyState.DONE to "Done ✓"
                Stage2JobStatus.FAILED ->
                    FriendlyState.ATTENTION to
                        "We're looking into this one — nothing you need to do."
            }
        return FriendlyJob(job.id, title, state, label)
    }

    /** Long-A/V banner (§8.2 last row): shown while any recording is still being listened to. */
    fun longRunningBanner(jobs: List<Stage2Job>): Boolean =
        jobs.any {
            it.status == Stage2JobStatus.TRANSCRIBING &&
                (it.modality == AssetModality.AUDIO || it.modality == AssetModality.VIDEO)
        }

    /** Friendly upload-status copy for the pre-submit uploads list (S3). */
    fun uploadLabel(status: AssetUploadStatus): String =
        when (status) {
            AssetUploadStatus.STORED -> "Ready"
            AssetUploadStatus.AWAITING_UPLOAD -> "Still uploading — retry if it stalled"
            AssetUploadStatus.FAILED -> "Didn't finish — try uploading it again"
            else -> "Ready"
        }

    /**
     * S6 decide step (VA-33): rows worth the subject's attention — mirrors
     * ClaimReviewService.needsDecision (INFERRED, unfavorable, or unscored) without surfacing the
     * favorability number itself (§12.3: no scores in subject templates).
     */
    fun needsAttention(claim: Claim, favorabilityThreshold: Double): Boolean =
        claim.claimBasis == ClaimBasis.INFERRED ||
            claim.favorability == null ||
            claim.favorability < favorabilityThreshold

    private fun Stage2JobStatus.isTerminal(): Boolean =
        this == Stage2JobStatus.COMPLETED || this == Stage2JobStatus.FAILED
}

/**
 * The curated S3 asset-type list (VA-32): friendly capability labels over [ContentType] — the
 * subject picks "what this is", never a taxonomy enum. Endorsement-flavored kinds additionally ask
 * who it's from ([SubjectFrom]), which refines the content type + relationship.
 */
enum class SubjectAssetKind(
    val label: String,
    val hint: String,
    private val contentType: ContentType?,
    val asksWho: Boolean = false,
) {
    STORY(
        "Me telling my story",
        "A recording of you talking about yourself, or something you wrote about your journey.",
        ContentType.SELF_INTERVIEW,
    ),
    RESUME("My résumé or CV", "Your current résumé, CV or bio.", ContentType.RESUME_CV),
    WORK(
        "Work I've made",
        "A portfolio piece, writing sample, or project you created.",
        ContentType.WORK_SAMPLE_PORTFOLIO,
    ),
    CERTIFICATE(
        "A certificate, license or qualification",
        "Certifications, licenses or accreditations you hold.",
        ContentType.CERTIFICATE,
    ),
    DEGREE(
        "A degree or academic transcript",
        "Degree certificates, marksheets, transcripts.",
        ContentType.DEGREE_TRANSCRIPT,
    ),
    AWARD("An award or honor", "Award letters, trophies, honors.", ContentType.AWARD_HONOR),
    PRESS(
        "News or press about me",
        "Articles or media coverage that mentions you.",
        ContentType.PRESS_COVERAGE,
    ),
    EVENT(
        "A recording of a moment",
        "A talk, ceremony, performance or match you were part of.",
        ContentType.SPEECH_TALK,
    ),
    ENDORSEMENT(
        "Someone vouching for me",
        "A recording or letter from someone who knows your work.",
        null,
        asksWho = true,
    );

    /** Resolve the intake taxonomy pair; endorsements refine by who they're from. */
    fun resolve(from: SubjectFrom?): Pair<ContentType, Relationship> =
        if (asksWho) {
            val who = from ?: SubjectFrom.SOMEONE_ELSE
            who.contentType to who.relationship
        } else {
            checkNotNull(contentType) to Relationship.SELF
        }

    companion object {
        fun fromOrNull(raw: String?): SubjectAssetKind? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** "Who is this from?" — the endorsement refinement, in subject vocabulary. */
enum class SubjectFrom(
    val label: String,
    val contentType: ContentType,
    val relationship: Relationship,
) {
    MANAGER("My manager", ContentType.MANAGER_ENDORSEMENT, Relationship.MANAGER),
    PEER("A colleague or friend", ContentType.PEER_ENDORSEMENT, Relationship.PEER),
    MENTOR("A mentor or teacher", ContentType.MENTOR_TEACHER_ENDORSEMENT, Relationship.MENTOR),
    CLIENT("A client", ContentType.CLIENT_TESTIMONIAL, Relationship.CLIENT),
    EXPERT("An expert in my field", ContentType.EXPERT_INTERVIEW, Relationship.EXPERT),
    FAMILY("Family", ContentType.PERSONAL_REFERENCE, Relationship.FAMILY),
    SOMEONE_ELSE("Someone else", ContentType.PERSONAL_REFERENCE, Relationship.UNKNOWN);

    companion object {
        fun fromOrNull(raw: String?): SubjectFrom? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

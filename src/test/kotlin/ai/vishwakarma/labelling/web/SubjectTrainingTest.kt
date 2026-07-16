package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.web.SubjectTraining.FriendlyState
import ai.vishwakarma.labelling.web.SubjectTraining.TrainingPhase
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The subject-vocabulary bridge (VA-32/33): S2 phase resolution, the §8.2 friendly-state mapping
 * (the grep-able §12.3 rule — templates render only what these functions emit), and the curated S3
 * asset-kind catalog.
 */
class SubjectTrainingTest {

    private fun manifest(
        sealed: Boolean = false,
        stage2StartedAt: Instant? = null,
        reviewLockedAt: Instant? = null,
        reviewSubmittedAt: Instant? = null,
    ) =
        IntakeManifest(
            id = "s1",
            subjectId = "s1",
            sealed = sealed,
            stage2StartedAt = stage2StartedAt,
            reviewLockedAt = reviewLockedAt,
            reviewSubmittedAt = reviewSubmittedAt,
        )

    private fun job(
        status: Stage2JobStatus,
        modality: AssetModality = AssetModality.AUDIO,
    ) =
        Stage2Job(
            id = "j-${status.ordinal}",
            subjectId = "s1",
            assetId = "a1",
            modality = modality,
            status = status,
        )

    private fun claim(basis: ClaimBasis? = ClaimBasis.STATED, favorability: Double? = 0.9) =
        Claim(
            id = "c1",
            subjectId = "s1",
            assetId = "a1",
            claimType = ClaimType.SKILL,
            text = "text",
            claimBasis = basis,
            favorability = favorability,
        )

    // ---- phase resolution (S2) ------------------------------------------------

    @Test
    fun `fresh subject is in the upload phase`() {
        assertEquals(TrainingPhase.UPLOAD, SubjectTraining.phaseFor(manifest(), emptyList()))
    }

    @Test
    fun `active jobs mean processing`() {
        val m = manifest(sealed = true, stage2StartedAt = Instant.now())
        val jobs = listOf(job(Stage2JobStatus.TRANSCRIBING), job(Stage2JobStatus.COMPLETED))
        assertEquals(TrainingPhase.PROCESSING, SubjectTraining.phaseFor(m, jobs))
    }

    @Test
    fun `a parked speaker-selection job keeps the subject in processing`() {
        val m = manifest(sealed = true, stage2StartedAt = Instant.now())
        val jobs = listOf(job(Stage2JobStatus.AWAITING_SPEAKER_SELECTION))
        assertEquals(TrainingPhase.PROCESSING, SubjectTraining.phaseFor(m, jobs))
    }

    @Test
    fun `all jobs settled means the review CTA`() {
        val m = manifest(sealed = true, stage2StartedAt = Instant.now())
        val jobs = listOf(job(Stage2JobStatus.COMPLETED), job(Stage2JobStatus.FAILED))
        assertEquals(TrainingPhase.REVIEW_READY, SubjectTraining.phaseFor(m, jobs))
    }

    @Test
    fun `a locked review continues the wizard`() {
        val m =
            manifest(sealed = true, stage2StartedAt = Instant.now(), reviewLockedAt = Instant.now())
        assertEquals(
            TrainingPhase.REVIEW_IN_PROGRESS,
            SubjectTraining.phaseFor(m, listOf(job(Stage2JobStatus.COMPLETED))),
        )
    }

    @Test
    fun `a submitted review is read-only`() {
        val m =
            manifest(
                sealed = true,
                stage2StartedAt = Instant.now(),
                reviewLockedAt = Instant.now(),
                reviewSubmittedAt = Instant.now(),
            )
        assertEquals(TrainingPhase.SUBMITTED, SubjectTraining.phaseFor(m, emptyList()))
    }

    @Test
    fun `sealed but not started renders as processing (half-failed submit is retryable)`() {
        assertEquals(
            TrainingPhase.PROCESSING,
            SubjectTraining.phaseFor(manifest(sealed = true), emptyList()),
        )
    }

    // ---- §8.2 friendly states (S4) ---------------------------------------------

    @Test
    fun `every raw status maps to friendly copy with no machinery vocabulary`() {
        Stage2JobStatus.entries.forEach { status ->
            val friendly = SubjectTraining.friendlyJob(job(status), "My story")
            Stage2JobStatus.entries.forEach { raw ->
                assertFalse(
                    friendly.label.contains(raw.name, ignoreCase = true),
                    "raw status ${raw.name} leaked into '${friendly.label}'",
                )
            }
        }
    }

    @Test
    fun `the mapping matches the §8_2 table`() {
        assertEquals(
            "Listening to your recording…",
            SubjectTraining.friendlyJob(job(Stage2JobStatus.TRANSCRIBING), "t").label,
        )
        assertEquals(
            FriendlyState.NEEDS_YOU,
            SubjectTraining.friendlyJob(job(Stage2JobStatus.AWAITING_SPEAKER_SELECTION), "t").state,
        )
        assertEquals(
            "Understanding what was said…",
            SubjectTraining.friendlyJob(job(Stage2JobStatus.EXTRACTING), "t").label,
        )
        assertEquals(
            FriendlyState.DONE,
            SubjectTraining.friendlyJob(job(Stage2JobStatus.COMPLETED), "t").state,
        )
        assertEquals(
            FriendlyState.ATTENTION,
            SubjectTraining.friendlyJob(job(Stage2JobStatus.FAILED), "t").state,
        )
    }

    @Test
    fun `documents get reading copy, not listening copy`() {
        val friendly =
            SubjectTraining.friendlyJob(
                job(Stage2JobStatus.PENDING, AssetModality.DOCUMENT),
                "Resume",
            )
        assertEquals("Reading it through…", friendly.label)
    }

    @Test
    fun `long-running banner shows only while an A_V transcription is in flight`() {
        assertTrue(
            SubjectTraining.longRunningBanner(
                listOf(job(Stage2JobStatus.TRANSCRIBING, AssetModality.VIDEO))
            )
        )
        assertFalse(
            SubjectTraining.longRunningBanner(
                listOf(job(Stage2JobStatus.EXTRACTING, AssetModality.VIDEO))
            )
        )
        assertFalse(SubjectTraining.longRunningBanner(emptyList()))
    }

    // ---- S6 attention rule (VA-33) ------------------------------------------------

    @Test
    fun `inferred, unfavorable and unscored claims need attention`() {
        assertTrue(SubjectTraining.needsAttention(claim(basis = ClaimBasis.INFERRED), 0.4))
        assertTrue(SubjectTraining.needsAttention(claim(favorability = 0.1), 0.4))
        assertTrue(SubjectTraining.needsAttention(claim(favorability = null), 0.4))
        assertFalse(SubjectTraining.needsAttention(claim(favorability = 0.9), 0.4))
    }

    // ---- curated asset kinds (S3) -------------------------------------------------

    @Test
    fun `self kinds resolve to their content type with SELF relationship`() {
        val (contentType, relationship) = SubjectAssetKind.RESUME.resolve(from = null)
        assertEquals(ContentType.RESUME_CV, contentType)
        assertEquals(Relationship.SELF, relationship)
    }

    @Test
    fun `endorsements refine content type and relationship by who they're from`() {
        val (contentType, relationship) = SubjectAssetKind.ENDORSEMENT.resolve(SubjectFrom.MANAGER)
        assertEquals(ContentType.MANAGER_ENDORSEMENT, contentType)
        assertEquals(Relationship.MANAGER, relationship)
    }

    @Test
    fun `an endorsement with nobody named degrades to a personal reference`() {
        val (contentType, relationship) = SubjectAssetKind.ENDORSEMENT.resolve(from = null)
        assertEquals(ContentType.PERSONAL_REFERENCE, contentType)
        assertEquals(Relationship.UNKNOWN, relationship)
    }
}

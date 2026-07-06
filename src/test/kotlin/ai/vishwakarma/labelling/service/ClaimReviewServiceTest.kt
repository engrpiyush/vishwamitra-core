package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private fun <A, B> Either<A, B>.ok(): B? = fold({ null }, { it })

private fun <A, B> Either<A, B>.err(): A? = fold({ it }, { null })

private class RFakeManifestRepo : IntakeManifestRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, IntakeManifest>()

    override fun findBySubject(subjectId: String): IntakeManifest? = store[subjectId]

    override fun save(manifest: IntakeManifest) {
        store[manifest.subjectId] = manifest
    }
}

private class RFakeJobRepo : Stage2JobRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage2Job>()

    override fun findBySubject(subjectId: String): List<Stage2Job> =
        store.values.filter { it.subjectId == subjectId }
}

private class RFakeClaimRepo : ClaimRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Claim>()

    override fun findById(id: String): Claim? = store[id]

    override fun findBySubject(subjectId: String): List<Claim> =
        store.values.filter { it.subjectId == subjectId }
}

private class RFakeReviewRepo : ClaimReviewRepository(mock(Firestore::class.java)) {
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

/** §12.6 review layer: lifecycle locks, review-required partitioning, and the Stage-3 read path. */
class ClaimReviewServiceTest {

    private val manifests = RFakeManifestRepo()
    private val jobs = RFakeJobRepo()
    private val claims = RFakeClaimRepo()
    private val reviews = RFakeReviewRepo()
    private val service = ClaimReviewService(manifests, jobs, claims, reviews, AppProperties())

    private val actor = "reviewer@vishwakarma.ai"

    private fun manifest(
        locked: Instant? = null,
        submitted: Instant? = null,
        started: Instant? = Instant.now(),
    ) {
        manifests.store["s1"] =
            IntakeManifest(
                id = "s1",
                subjectId = "s1",
                sealed = true,
                stage2StartedAt = started,
                reviewLockedAt = locked,
                reviewSubmittedAt = submitted,
            )
    }

    private fun claim(
        id: String,
        basis: ClaimBasis = ClaimBasis.STATED,
        favor: Double? = 0.9,
        sensitive: Boolean = false,
    ) {
        claims.store[id] =
            Claim(
                id = id,
                subjectId = "s1",
                assetId = "a1",
                claimType = ClaimType.EPISODE,
                text = "claim $id",
                claimBasis = basis,
                favorability = favor,
                sensitive = sensitive,
                createdAt = Instant.now(),
            )
    }

    private fun completedJob() {
        jobs.store["j1"] =
            Stage2Job(
                id = "j1",
                subjectId = "s1",
                assetId = "a1",
                modality = AssetModality.AUDIO,
                status = Stage2JobStatus.COMPLETED,
                createdAt = Instant.now(),
            )
    }

    // ---- lifecycle -----------------------------------------------------------

    @Test
    fun `startReview locks the claims once all jobs are terminal`() {
        manifest()
        completedJob()
        assertTrue(service.startReview("s1").ok()?.reviewLockedAt != null)
        assertTrue(manifests.store["s1"]!!.reviewLockedAt != null)
    }

    @Test
    fun `startReview refuses while a job is still active`() {
        manifest()
        jobs.store["j1"] =
            Stage2Job(
                id = "j1",
                subjectId = "s1",
                assetId = "a1",
                modality = AssetModality.AUDIO,
                status = Stage2JobStatus.TRANSCRIBING,
                createdAt = Instant.now(),
            )
        assertTrue(service.startReview("s1").err() is DomainError.Conflict)
        assertNull(manifests.store["s1"]!!.reviewLockedAt)
    }

    @Test
    fun `startReview refuses when review already started`() {
        manifest(locked = Instant.now())
        completedJob()
        assertTrue(service.startReview("s1").err() is DomainError.Conflict)
    }

    // ---- partitioning --------------------------------------------------------

    @Test
    fun `partition sends inferred, unfavorable, and unscored claims to needsDecision`() {
        manifest()
        claim("c-fav", favor = 0.9) // favorable STATED → auto
        claim("c-unfav", favor = 0.2) // unfavorable → decide
        claim("c-inf", basis = ClaimBasis.INFERRED, favor = 0.9) // inferred → decide
        claim("c-null", favor = null) // unscored → decide
        claim("c-pii", favor = 0.9, sensitive = true) // favorable but sensitive → auto + sensitive

        val p = service.partition("s1")

        assertEquals(setOf("c-unfav", "c-inf", "c-null"), p.needsDecision.map { it.id }.toSet())
        assertEquals(setOf("c-fav", "c-pii"), p.autoApproved.map { it.id }.toSet())
        assertEquals(setOf("c-pii"), p.sensitive.map { it.id }.toSet())
    }

    // ---- per-claim actions ---------------------------------------------------

    @Test
    fun `sidecar requires a justification, then records the decision and refs`() {
        manifest(locked = Instant.now())
        claim("c1", favor = 0.2)
        assertTrue(
            service.reviewClaim("c1", ReviewDecision.SIDECARED, null, emptyList(), actor).err()
                is DomainError.Invalid
        )
        assertTrue(
            service
                .reviewClaim("c1", ReviewDecision.SIDECARED, "context", listOf("c2"), actor)
                .ok() != null
        )
        val r = reviews.store["c1"]!!
        assertEquals(ReviewDecision.SIDECARED, r.decision)
        assertEquals("context", r.justification)
        assertEquals(listOf("c2"), r.corroboratingClaimIds)
    }

    @Test
    fun `per-claim edits refuse before review starts and after it is submitted`() {
        claim("c1", favor = 0.2)
        manifest(locked = null) // not started
        assertTrue(
            service.reviewClaim("c1", ReviewDecision.APPROVED, null, emptyList(), actor).err()
                is DomainError.Conflict
        )
        manifest(locked = Instant.now(), submitted = Instant.now()) // submitted
        assertTrue(
            service.reviewClaim("c1", ReviewDecision.APPROVED, null, emptyList(), actor).err()
                is DomainError.Conflict
        )
    }

    @Test
    fun `a stated fact cannot be contested, only an inferred claim can`() {
        manifest(locked = Instant.now())
        claim("c-stated", basis = ClaimBasis.STATED, favor = 0.2)
        assertTrue(
            service
                .reviewClaim("c-stated", ReviewDecision.CONTESTED, null, emptyList(), actor)
                .err() is DomainError.Invalid
        )
        claim("c-inferred", basis = ClaimBasis.INFERRED, favor = 0.9)
        assertTrue(
            service
                .reviewClaim("c-inferred", ReviewDecision.CONTESTED, null, emptyList(), actor)
                .ok() != null
        )
    }

    @Test
    fun `setPii refuses a non-sensitive claim and records the opt-in otherwise`() {
        manifest(locked = Instant.now())
        claim("c1", sensitive = false)
        assertTrue(service.setPii("c1", PiiChoice.INCLUDE, actor).err() is DomainError.Invalid)
        claim("c2", sensitive = true)
        assertTrue(service.setPii("c2", PiiChoice.INCLUDE, actor).ok() != null)
        assertEquals(PiiChoice.INCLUDE, reviews.store["c2"]!!.piiChoice)
    }

    // ---- submit / reopen -----------------------------------------------------

    @Test
    fun `submitReview succeeds by default (approve-by-default needs no per-claim decisions)`() {
        manifest(locked = Instant.now())
        claim("c-unfav", favor = 0.2) // no decision recorded
        claim("c-pii", favor = 0.9, sensitive = true) // no PII choice recorded
        assertTrue(service.submitReview("s1").ok()?.reviewSubmittedAt != null)
    }

    @Test
    fun `submitReview refuses when not started or already submitted`() {
        claim("c1", favor = 0.2)
        manifest(locked = null)
        assertTrue(service.submitReview("s1").err() is DomainError.Conflict)
        manifest(locked = Instant.now(), submitted = Instant.now())
        assertTrue(service.submitReview("s1").err() is DomainError.Conflict)
    }

    @Test
    fun `reopenReview clears the submitted stamp, and refuses when not submitted`() {
        manifest(locked = Instant.now(), submitted = Instant.now())
        assertNull(service.reopenReview("s1").ok()?.reviewSubmittedAt)
        assertNull(manifests.store["s1"]!!.reviewSubmittedAt)
        // now not submitted → refuse
        assertTrue(service.reopenReview("s1").err() is DomainError.Conflict)
    }

    // ---- the Stage-3 read path -----------------------------------------------

    @Test
    fun `approvedForDownstream excludes contested and unopted PII, keeps approved with sidecar`() {
        manifest(locked = Instant.now(), submitted = Instant.now())
        claim("c-app", favor = 0.9) // auto-approved, no review
        claim("c-side", favor = 0.2) // sidecared
        claim("c-con", basis = ClaimBasis.INFERRED, favor = 0.9) // contested
        claim("c-pii-in", favor = 0.9, sensitive = true) // opted in
        claim("c-pii-out", favor = 0.9, sensitive = true) // held
        reviews.store["c-side"] =
            ClaimReview(
                "c-side",
                "s1",
                decision = ReviewDecision.SIDECARED,
                justification = "context",
                corroboratingClaimIds = listOf("c-app"),
            )
        reviews.store["c-con"] = ClaimReview("c-con", "s1", decision = ReviewDecision.CONTESTED)
        reviews.store["c-pii-in"] = ClaimReview("c-pii-in", "s1", piiChoice = PiiChoice.INCLUDE)
        reviews.store["c-pii-out"] = ClaimReview("c-pii-out", "s1", piiChoice = PiiChoice.HIDE)

        val approved = service.approvedForDownstream("s1")

        assertEquals(
            setOf("c-app", "c-side", "c-pii-in"),
            approved.map { it.claim.id }.toSet(),
        )
        val side = approved.first { it.claim.id == "c-side" }
        assertEquals("context", side.justification)
        assertEquals(listOf("c-app"), side.corroboratingClaimIds)
    }

    @Test
    fun `summary buckets claims into approved, sidecar, rejected with PII counts`() {
        manifest(locked = Instant.now())
        claim("c-app", favor = 0.9) // approved by default
        claim("c-side", favor = 0.2)
        claim("c-con", basis = ClaimBasis.INFERRED, favor = 0.9)
        claim("c-pii-in", favor = 0.9, sensitive = true)
        claim("c-pii-out", favor = 0.9, sensitive = true)
        reviews.store["c-side"] =
            ClaimReview(
                "c-side",
                "s1",
                decision = ReviewDecision.SIDECARED,
                justification = "context"
            )
        reviews.store["c-con"] = ClaimReview("c-con", "s1", decision = ReviewDecision.CONTESTED)
        reviews.store["c-pii-in"] = ClaimReview("c-pii-in", "s1", piiChoice = PiiChoice.INCLUDE)

        val s = service.summary("s1")

        assertEquals(5, s.total)
        assertEquals(3, s.approved) // c-app + the two PII (by decision, none are side/con)
        assertEquals(1, s.sidecared)
        assertEquals(1, s.rejected)
        assertEquals(1, s.sensitiveIncluded)
        assertEquals(1, s.sensitiveHeld)
        assertEquals(setOf("c-side", "c-con"), s.nonApproved.map { it.claim.id }.toSet())
    }
}

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage3.DryRunStage3Corpus
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private fun <A, B> Either<A, B>.valueOrNull(): B? = fold({ null }, { it })

private fun <A, B> Either<A, B>.errorOrNull(): A? = fold({ it }, { null })

private class SeedSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]

    override fun save(subject: Subject) {
        store[subject.id] = subject
    }
}

private class SeedManifestRepo : IntakeManifestRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, IntakeManifest>()

    override fun findBySubject(subjectId: String): IntakeManifest? = store[subjectId]

    override fun save(manifest: IntakeManifest) {
        store[manifest.subjectId] = manifest
    }
}

private class SeedAssetRepo : AssetRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Asset>()

    override fun findBySubject(subjectId: String): List<Asset> =
        store.values.filter { it.subjectId == subjectId }

    override fun save(asset: Asset) {
        store[asset.id] = asset
    }
}

private class SeedClaimRepo : ClaimRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Claim>()

    override fun findBySubject(subjectId: String): List<Claim> =
        store.values.filter { it.subjectId == subjectId }

    override fun save(claim: Claim) {
        store[claim.id] = claim
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}

private class SeedReviewRepo : ClaimReviewRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ClaimReview>()

    override fun save(review: ClaimReview) {
        store[review.claimId] = review
    }

    override fun delete(claimId: String) {
        store.remove(claimId)
    }
}

private class SeedRunRepo : Stage3RunRepository(mock(Firestore::class.java)) {
    var active: Stage3Run? = null

    override fun findActiveBySubject(subjectId: String): Stage3Run? =
        active?.takeIf { it.subjectId == subjectId }
}

class Stage3CorpusSeederTest {

    private val subjects = SeedSubjectRepo()
    private val manifests = SeedManifestRepo()
    private val assets = SeedAssetRepo()
    private val claims = SeedClaimRepo()
    private val reviews = SeedReviewRepo()
    private val runs = SeedRunRepo()

    private fun seeder(dryRun: Boolean = true) =
        Stage3CorpusSeeder(
            subjects,
            manifests,
            assets,
            claims,
            reviews,
            runs,
            AppProperties(stage3 = AppProperties.Stage3(dryRun = dryRun)),
        )

    @Test
    fun `seeds the corpus as a review-submitted subject`() {
        val outcome = seeder().seed("admin@test").valueOrNull()
        assertNotNull(outcome)
        assertEquals(DryRunStage3Corpus.SUBJECT_ID, outcome.subjectId)
        assertEquals(DryRunStage3Corpus.claims.size, outcome.claims)
        assertEquals(DryRunStage3Corpus.assets.size, outcome.assets)
        assertEquals(1, outcome.sidecars)

        // The Stage 3 submit guard reads reviewSubmittedAt — it must be stamped.
        assertNotNull(manifests.store.getValue(DryRunStage3Corpus.SUBJECT_ID).reviewSubmittedAt)

        // Claims carry the seed tiers and denormalized source classes the projection reads.
        val c01 = claims.store.getValue("asha-c01")
        assertEquals(SourceClass.DOCUMENTARY, c01.sourceClass)
        assertEquals(ClaimType.EPISODE, c01.claimType)
        assertEquals("HIGH", c01.authenticityTier?.name)

        // The sidecar rides c05's SIDECARED review (the §11.9 dual-judge hook).
        val c05Review = reviews.store.getValue("asha-c05")
        assertEquals(ReviewDecision.SIDECARED, c05Review.decision)
        assertTrue(!c05Review.justification.isNullOrBlank())
        assertEquals(ReviewDecision.APPROVED, reviews.store.getValue("asha-c01").decision)
    }

    @Test
    fun `re-seed removes claims from an older corpus revision`() {
        claims.save(
            Claim(
                id = "asha-old-claim",
                subjectId = DryRunStage3Corpus.SUBJECT_ID,
                assetId = "gone",
                claimType = ClaimType.SKILL,
                text = "A claim from a previous corpus revision.",
            )
        )
        val outcome = seeder().seed(null).valueOrNull()
        assertNotNull(outcome)
        assertEquals(1, outcome.staleClaimsRemoved)
        assertNull(claims.store["asha-old-claim"])
        assertEquals(DryRunStage3Corpus.claims.size, claims.store.size)
    }

    @Test
    fun `refuses outside dry-run and while a run is active`() {
        val offline = seeder(dryRun = false).seed(null).errorOrNull()
        assertTrue(offline is DomainError.Conflict)

        runs.active =
            Stage3Run(
                id = "run-1",
                subjectId = DryRunStage3Corpus.SUBJECT_ID,
                status = Stage3RunStatus.JUDGING,
                createdAt = Instant.now(),
            )
        val busy = seeder().seed(null).errorOrNull()
        assertTrue(busy is DomainError.Conflict)
    }
}

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage3.DryRunStage3Corpus
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** What one seeding pass wrote — the endpoint's response body. */
data class CorpusSeedOutcome(
    val subjectId: String,
    val assets: Int,
    val claims: Int,
    val sidecars: Int,
    /** Claims from an older corpus revision that were removed by the wholesale replace. */
    val staleClaimsRemoved: Int,
)

/**
 * Seeds the §11.12 sample corpus ([DryRunStage3Corpus]) into Firestore as a review-submitted
 * subject, so a dev Stage 3 run reproduces the LLD §11.8 worked example end-to-end — dev-only
 * machinery, refused unless `app.stage3.dry-run` is on (the §11.12 posture; the per-leg judge
 * override may still point at real Gemini for the gated live smoke).
 *
 * Idempotent by construction: fixed subject/asset/claim ids, so a re-seed overwrites in place
 * (claim ids being stable is what keeps judge-cache keys and pair records stable across seeds);
 * claims from an older corpus revision are deleted wholesale first.
 */
@Service
class Stage3CorpusSeeder(
    private val subjects: SubjectRepository,
    private val manifests: IntakeManifestRepository,
    private val assets: AssetRepository,
    private val claims: ClaimRepository,
    private val reviews: ClaimReviewRepository,
    private val runs: Stage3RunRepository,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(Stage3CorpusSeeder::class.java)

    fun seed(actor: String?): Either<DomainError, CorpusSeedOutcome> {
        if (!props.stage3.dryRun)
            return DomainError.Conflict(
                    "The sample corpus is the §11.12 dry-run fixture — enable app.stage3.dry-run " +
                        "before seeding"
                )
                .left()
        val subjectId = DryRunStage3Corpus.SUBJECT_ID
        runs.findActiveBySubject(subjectId)?.let {
            return DomainError.Conflict(
                    "A Stage 3 run is active for the corpus subject (${it.id}: ${it.status}) — " +
                        "let it finish or fail it before re-seeding"
                )
                .left()
        }
        val now = Instant.now()

        subjects.save(
            Subject(
                id = subjectId,
                displayName = DryRunStage3Corpus.SUBJECT_NAME,
                handle = subjectId,
                notes = "Stage 3 dry-run sample corpus (LLD §11.12) — seeded, not user data.",
                createdBy = actor,
                createdAt = now,
                updatedAt = now,
            )
        )

        val corpusAssets = DryRunStage3Corpus.assets
        corpusAssets.forEach { a ->
            val contentType = ContentType.valueOf(a.contentType)
            assets.save(
                Asset(
                    id = a.assetId(subjectId),
                    subjectId = subjectId,
                    title = a.title,
                    modality = AssetModality.valueOf(a.modality),
                    sourceClass = contentType.sourceClass,
                    contentType = contentType,
                    relationship = Relationship.valueOf(a.relationship),
                    authenticityPrior = contentType.basePrior,
                    consentStatus = ConsentStatus.GRANTED,
                    consentDate = now,
                    consentNote = "Synthetic dry-run fixture",
                )
            )
        }

        // Wholesale replace: a shrunk/renamed corpus never leaves orphan claims behind.
        val corpusClaimIds = DryRunStage3Corpus.claims.map { it.claimId }.toSet()
        val stale = claims.findBySubject(subjectId).filter { it.id !in corpusClaimIds }
        stale.forEach {
            claims.delete(it.id)
            reviews.delete(it.id)
        }

        var sidecars = 0
        DryRunStage3Corpus.claims.forEach { c ->
            val asset = corpusAssets.first { it.key == c.assetKey }
            val contentType = ContentType.valueOf(asset.contentType)
            claims.save(
                Claim(
                    id = c.claimId,
                    subjectId = subjectId,
                    assetId = asset.assetId(subjectId),
                    claimType = ClaimType.valueOf(c.type),
                    text = c.text,
                    claimedDate = c.claimedDate?.let(LocalDate::parse),
                    authenticityTier = AuthenticityTier.valueOf(c.tierSeed),
                    sourceClass = contentType.sourceClass,
                    relationship = Relationship.valueOf(asset.relationship),
                    extractionConfidence = 0.95,
                    claimBasis = ClaimBasis.STATED,
                    favorability = c.favorability,
                )
            )
            reviews.save(
                ClaimReview(
                    claimId = c.claimId,
                    subjectId = subjectId,
                    decision =
                        if (c.sidecar != null) ReviewDecision.SIDECARED
                        else ReviewDecision.APPROVED,
                    justification = c.sidecar,
                    reviewedBy = actor ?: "dry-run-seeder",
                    reviewedAt = now,
                )
            )
            if (c.sidecar != null) sidecars++
        }

        manifests.save(
            IntakeManifest(
                id = subjectId,
                subjectId = subjectId,
                assetIds = corpusAssets.map { it.assetId(subjectId) },
                sealed = true,
                stage2StartedAt = now,
                reviewLockedAt = now,
                reviewSubmittedAt = now,
            )
        )

        log.info(
            "Seeded the §11.12 dry-run corpus: subject {}, {} asset(s), {} claim(s) ({} stale " +
                "removed)",
            subjectId,
            corpusAssets.size,
            DryRunStage3Corpus.claims.size,
            stale.size,
        )
        return CorpusSeedOutcome(
                subjectId = subjectId,
                assets = corpusAssets.size,
                claims = DryRunStage3Corpus.claims.size,
                sidecars = sidecars,
                staleClaimsRemoved = stale.size,
            )
            .right()
    }
}

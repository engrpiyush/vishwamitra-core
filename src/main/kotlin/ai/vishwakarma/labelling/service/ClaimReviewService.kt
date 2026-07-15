package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.springframework.stereotype.Service

/**
 * The subject's claims split into the three §12.6 review surfaces. [needsDecision] is every claim
 * the operator must act on (INFERRED, unfavorable, or unscored); [autoApproved] flows to Stage 3 as
 * extracted (favorable STATED); [sensitive] claims each need a hide/include opt-in (an orthogonal
 * axis — a sensitive claim may also appear in [needsDecision]). [reviews] maps claimId → decision.
 */
data class ReviewPartition(
    val needsDecision: List<Claim>,
    val autoApproved: List<Claim>,
    val sensitive: List<Claim>,
    val reviews: Map<String, ClaimReview>,
)

/** A claim cleared for Stage 3, carrying its sidecar (§12.6). */
data class ApprovedClaim(
    val claim: Claim,
    val justification: String?,
    val corroboratingClaimIds: List<String>,
)

/** A claim paired with its review (if any) — the consolidated preview's row shape. */
data class ReviewedClaim(val claim: Claim, val review: ClaimReview?)

/**
 * Consolidated counts for the review preview (§12.6). Approve-by-default: every claim is [approved]
 * unless it was explicitly [sidecared] (kept + contextualized) or [rejected] (contested →
 * excluded). PII is orthogonal: a sensitive claim is held unless opted in.
 */
data class ReviewSummary(
    val total: Int,
    val approved: Int,
    val sidecared: Int,
    val rejected: Int,
    val sensitiveIncluded: Int,
    val sensitiveHeld: Int,
    /**
     * The non-approved claims (sidecared or rejected) — surfaced for a final look before submit.
     */
    val nonApproved: List<ReviewedClaim>,
)

/**
 * The §12.6 claim-review layer — the human ratification gate between Stage 2 extraction and
 * Stage 3. Reviews live in a durable sidecar ([ClaimReviewRepository]) keyed by claim id; the claim
 * ledger stays immutable. Two locks stamped on the manifest drive the flow: [startReview] freezes
 * the claims (Stage2Service refuses re-extraction thereafter), [submitReview] finalizes the
 * decisions; an ADMIN may [reopenReview]. Favorable, non-sensitive claims flow by default;
 * unfavorable / INFERRED / unscored claims need an explicit decision; sensitive claims are held
 * until opted in.
 */
@Service
class ClaimReviewService(
    private val manifests: IntakeManifestRepository,
    private val jobs: Stage2JobRepository,
    private val claims: ClaimRepository,
    private val reviews: ClaimReviewRepository,
    private val config: StageConfigService,
) {

    // ---- lifecycle -----------------------------------------------------------

    /**
     * Begin review: all jobs must be terminal and review not already started; freezes the claims.
     */
    fun startReview(subjectId: String): Either<DomainError, IntakeManifest> {
        val manifest =
            manifests.findBySubject(subjectId)
                ?: return DomainError.NotFound("No manifest for subject $subjectId").left()
        if (manifest.stage2StartedAt == null)
            return DomainError.Conflict("Stage 2 has not run for this subject yet").left()
        if (manifest.reviewLockedAt != null)
            return DomainError.Conflict("Claim review has already started").left()
        val active = jobs.findBySubject(subjectId).filterNot { it.status.isTerminal() }
        if (active.isNotEmpty())
            return DomainError.Conflict(
                    "Finish or resolve all Stage 2 jobs before starting review " +
                        "(${active.size} still active)"
                )
                .left()
        val now = Instant.now()
        return manifest
            .copy(reviewLockedAt = now, updatedAt = now)
            .also { manifests.save(it) }
            .right()
    }

    /**
     * Finalize the review. Approve-by-default: no per-claim decision is required, and sensitive
     * claims default to HIDE — so submitting simply stamps the lock over whatever exceptions
     * (sidecars / rejections / PII opt-ins) the operator chose to make.
     */
    fun submitReview(subjectId: String): Either<DomainError, IntakeManifest> {
        val manifest =
            manifests.findBySubject(subjectId)
                ?: return DomainError.NotFound("No manifest for subject $subjectId").left()
        if (manifest.reviewLockedAt == null)
            return DomainError.Conflict("Claim review has not been started").left()
        if (manifest.reviewSubmittedAt != null)
            return DomainError.Conflict("Claim review has already been submitted").left()
        val now = Instant.now()
        return manifest
            .copy(reviewSubmittedAt = now, updatedAt = now)
            .also { manifests.save(it) }
            .right()
    }

    /** ADMIN-only (enforced at the controller): reopen a submitted review for edits. */
    fun reopenReview(subjectId: String): Either<DomainError, IntakeManifest> {
        val manifest =
            manifests.findBySubject(subjectId)
                ?: return DomainError.NotFound("No manifest for subject $subjectId").left()
        if (manifest.reviewSubmittedAt == null)
            return DomainError.Conflict("Claim review is not submitted").left()
        return manifest
            .copy(reviewSubmittedAt = null, updatedAt = Instant.now())
            .also { manifests.save(it) }
            .right()
    }

    // ---- per-claim actions ---------------------------------------------------

    fun reviewClaim(
        claimId: String,
        decision: ReviewDecision,
        justification: String?,
        corroboratingClaimIds: List<String>,
        actor: String?,
    ): Either<DomainError, ClaimReview> {
        val claim =
            claims.findById(claimId)
                ?: return DomainError.NotFound("Claim $claimId not found").left()
        guardOpen(claim.subjectId)?.let {
            return it.left()
        }
        if (decision == ReviewDecision.SIDECARED && justification.isNullOrBlank())
            return DomainError.Invalid("A justification is required to sidecar a claim").left()
        // A stated fact is never hidden — it can only be approved or contextualized (sidecarred).
        // Contesting (excluding) is reserved for INFERRED claims, where it disputes the inference.
        if (decision == ReviewDecision.CONTESTED && claim.claimBasis != ClaimBasis.INFERRED)
            return DomainError.Invalid(
                    "Only inferred claims can be contested — a stated fact can be approved or " +
                        "sidecarred, never hidden"
                )
                .left()
        val existing = reviews.findByClaim(claimId)
        val review =
            ClaimReview(
                claimId = claimId,
                subjectId = claim.subjectId,
                decision = decision,
                justification = justification?.trim()?.takeIf { it.isNotBlank() },
                corroboratingClaimIds = corroboratingClaimIds,
                piiChoice = existing?.piiChoice,
                reviewedBy = actor,
                reviewedAt = Instant.now(),
            )
        reviews.save(review)
        return review.right()
    }

    fun setPii(
        claimId: String,
        choice: PiiChoice,
        actor: String?,
    ): Either<DomainError, ClaimReview> {
        val claim =
            claims.findById(claimId)
                ?: return DomainError.NotFound("Claim $claimId not found").left()
        if (!claim.sensitive)
            return DomainError.Invalid("Claim $claimId is not marked sensitive").left()
        guardOpen(claim.subjectId)?.let {
            return it.left()
        }
        val existing = reviews.findByClaim(claimId) ?: ClaimReview(claimId, claim.subjectId)
        val review =
            existing.copy(piiChoice = choice, reviewedBy = actor, reviewedAt = Instant.now())
        reviews.save(review)
        return review.right()
    }

    // ---- reads ---------------------------------------------------------------

    fun partition(subjectId: String): ReviewPartition {
        val all = claims.findBySubject(subjectId)
        val reviewMap = reviews.findBySubject(subjectId).associateBy { it.claimId }
        return ReviewPartition(
            needsDecision = all.filter { it.needsDecision() },
            autoApproved = all.filterNot { it.needsDecision() },
            sensitive = all.filter { it.sensitive },
            reviews = reviewMap,
        )
    }

    /** The Stage-3 contract: approved-and-not-contested claims (sensitive only if opted in). */
    fun approvedForDownstream(subjectId: String): List<ApprovedClaim> {
        val reviewMap = reviews.findBySubject(subjectId).associateBy { it.claimId }
        return claims.findBySubject(subjectId).mapNotNull { c ->
            val r = reviewMap[c.id]
            if (r?.decision == ReviewDecision.CONTESTED) return@mapNotNull null
            if (c.sensitive && r?.piiChoice != PiiChoice.INCLUDE) return@mapNotNull null
            ApprovedClaim(c, r?.justification, r?.corroboratingClaimIds ?: emptyList())
        }
    }

    /**
     * Consolidated view for the preview: how many approved / enriched / rejected, plus the list.
     */
    fun summary(subjectId: String): ReviewSummary {
        val all = claims.findBySubject(subjectId)
        val reviewMap = reviews.findBySubject(subjectId).associateBy { it.claimId }
        val rejected = all.count { reviewMap[it.id]?.decision == ReviewDecision.CONTESTED }
        val sidecared = all.count { reviewMap[it.id]?.decision == ReviewDecision.SIDECARED }
        val sensitiveIncluded =
            all.count { it.sensitive && reviewMap[it.id]?.piiChoice == PiiChoice.INCLUDE }
        val nonApproved =
            all.filter {
                    val d = reviewMap[it.id]?.decision
                    d == ReviewDecision.SIDECARED || d == ReviewDecision.CONTESTED
                }
                .map { ReviewedClaim(it, reviewMap[it.id]) }
        return ReviewSummary(
            total = all.size,
            approved = all.size - rejected - sidecared,
            sidecared = sidecared,
            rejected = rejected,
            sensitiveIncluded = sensitiveIncluded,
            sensitiveHeld = all.count { it.sensitive } - sensitiveIncluded,
            nonApproved = nonApproved,
        )
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * Which claims force an explicit decision (doubt #1): every INFERRED claim, every claim below
     * the favorability gate, and any unscored (null-favorability) claim — the last a safety net so
     * a missing score is never silently auto-approved. The rest (favorable STATED) flow by default.
     */
    private fun Claim.needsDecision(): Boolean =
        claimBasis == ClaimBasis.INFERRED ||
            favorability == null ||
            favorability < config.stage2().favorabilityThreshold

    private fun Stage2JobStatus.isTerminal(): Boolean =
        this == Stage2JobStatus.COMPLETED || this == Stage2JobStatus.FAILED

    /**
     * Per-claim edits require review started and not yet submitted (reopen re-opens the window).
     */
    private fun guardOpen(subjectId: String): DomainError? {
        val manifest =
            manifests.findBySubject(subjectId)
                ?: return DomainError.NotFound("No manifest for subject $subjectId")
        if (manifest.reviewLockedAt == null)
            return DomainError.Conflict("Claim review has not been started")
        if (manifest.reviewSubmittedAt != null)
            return DomainError.Conflict("Claim review is submitted — reopen it to make changes")
        return null
    }
}

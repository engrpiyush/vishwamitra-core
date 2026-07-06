package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * The reviewer's decision on a claim during the §12.6 review flow. A claim with **no**
 * [ClaimReview] row is *undecided*: whether it still flows downstream is computed by
 * [ai.vishwakarma.labelling.service.ClaimReviewService] from the claim's own markers (favorable,
 * non-sensitive claims flow by default; unfavorable / INFERRED / sensitive ones need an explicit
 * decision). This enum records only the decisions a human actually made.
 */
enum class ReviewDecision {
    /** Keep the claim as extracted — it flows to Stage 3 unchanged. */
    APPROVED,
    /**
     * Keep the claim, but attach a [ClaimReview.justification] (and optional corroborating claims)
     * that *contextualises* an unfavorable fact rather than hiding it. Still flows downstream — the
     * "don't erase, explain" path (§12.6).
     */
    SIDECARED,
    /** Dispute an over-claimed (usually INFERRED) claim — excluded from Stage 3/4. */
    CONTESTED;

    companion object {
        fun fromOrNull(raw: String?): ReviewDecision? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * Opt-in decision for a `sensitive` (contact/identity PII) claim (§12.6). Sensitive claims are
 * **held from tuning by default** ([HIDE]) — kept in the ledger, excluded from Stage 3/4 — until
 * the subject explicitly consents to include them ([INCLUDE]).
 */
enum class PiiChoice {
    HIDE,
    INCLUDE;

    companion object {
        fun fromOrNull(raw: String?): PiiChoice? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * A durable human review of a single [Claim] (§12.6) — the sidecar that lets the subject curate and
 * contextualise the evidence ledger *without mutating it* (claims stay extraction-owned and
 * immutable). Keyed by [claimId]: the review flow first locks the claims (no more re-extraction),
 * so claim ids are stable and can key the review directly — no content fingerprint needed.
 *
 * A row exists only once a human has acted on the claim; its absence means "undecided / default".
 */
data class ClaimReview(
    /** The reviewed [Claim.id]; also this review's Firestore document id. */
    val claimId: String,
    val subjectId: String,
    /** The approve / sidecar / contest decision, if one has been made. */
    val decision: ReviewDecision? = null,
    /** Free-text justification attached when [decision] is [ReviewDecision.SIDECARED]. */
    val justification: String? = null,
    /**
     * Ids of the subject's other claims that corroborate this one (the sidecar's evidence links).
     */
    val corroboratingClaimIds: List<String> = emptyList(),
    /** For a `sensitive` claim: whether the subject opted it into tuning. Null = default HIDE. */
    val piiChoice: PiiChoice? = null,
    val reviewedBy: String? = null,
    val reviewedAt: Instant? = null,
)

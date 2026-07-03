package ai.vishwakarma.labelling.domain

import java.time.Instant
import java.time.LocalDate

/**
 * The kind of Claim an example exercises — the Neo spine object's type. An advocate answer is built
 * around one of these.
 */
enum class ClaimType {
    IDENTITY,
    EPISODE,
    VALUE,
    WEAKNESS,
    SKILL;

    companion object {
        fun fromOrNull(raw: String?): ClaimType? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** How well-corroborated the underlying Claim is; drives answer confidence (assertive → hedged). */
enum class AuthenticityTier {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromOrNull(raw: String?): AuthenticityTier? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * The pipeline's spine object: one atomic, traceable evidence unit about a subject, extracted in
 * Stage 2 from an [Asset]'s transcript/text. Written by Stage 2, scored by Stage 3
 * ([authenticityScore] stays null until then), read by Stage 4 for training-pair synthesis.
 * Provenance ([assetId], [speaker], [mediaStart]/[mediaEnd], [sourceExcerpt]) keeps every claim
 * traceable back to the exact moment in the source material it came from.
 */
data class Claim(
    val id: String,
    val subjectId: String,
    val assetId: String,
    val claimType: ClaimType,
    /** The atomic claim, e.g. "Led the payments-platform migration in 2019". */
    val text: String,
    /** Diarization label as heard in the source (e.g. "Speaker 1"). */
    val speaker: String? = null,
    /** Seconds into the source A/V where the claim starts/ends. */
    val mediaStart: Double? = null,
    val mediaEnd: Double? = null,
    /** Transcript span the claim was drawn from. */
    val sourceExcerpt: String? = null,
    /** When the documented event happened (LLM-extracted; tolerant parse). */
    val claimedDate: LocalDate? = null,
    /** Seeded from the source asset's [Asset.authenticityPrior]; Stage 3 refines. */
    val authenticityTier: AuthenticityTier? = null,
    /** Final trust score — null until Stage 3 scores it. */
    val authenticityScore: Double? = null,
    /** LLM self-reported extraction confidence (0..1). */
    val extractionConfidence: Double? = null,
    val createdAt: Instant? = null,
    val stage2ProcessedAt: Instant? = null,
)

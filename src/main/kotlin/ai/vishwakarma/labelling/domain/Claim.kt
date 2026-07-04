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
 * Whether the source asserts a Claim outright ([STATED]) or the extractor inferred it from
 * demonstrated behaviour ([INFERRED] — the reduced-confidence demonstration-inference mode). A
 * structural marker kept independent of [Claim.extractionConfidence] (which is per-run noise): it
 * drives the Stage 2 review action (STATED retractable, INFERRED contestable) and Stage 3
 * weighting.
 */
enum class ClaimBasis {
    STATED,
    INFERRED;

    companion object {
        fun fromOrNull(raw: String?): ClaimBasis? =
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
    /**
     * Denormalised from the source [Asset] so a claim is a self-contained evidence unit for Stage
     * 3's content-type × relationship weighting (no re-join to the asset).
     */
    val sourceClass: SourceClass? = null,
    val relationship: Relationship? = null,
    /** Final trust score — null until Stage 3 scores it. */
    val authenticityScore: Double? = null,
    /**
     * LLM self-reported extraction *fidelity* (0..1) — how cleanly the source was read, NOT
     * evidential weight; uniform per run in practice, so Stage 3 must not weight on it.
     */
    val extractionConfidence: Double? = null,
    /** STATED (source asserts it) vs INFERRED (extractor inferred from demonstration). */
    val claimBasis: ClaimBasis? = null,
    /** Contact/identity PII — captured but held for opt-in approval by the §12.6 review layer. */
    val sensitive: Boolean = false,
    /** [ContentType] name whose instruction block extracted this claim. */
    val extractionPromptId: String? = null,
    /** [ExtractionPrompt.version] used (0 = built-in default); Stage 3 compares like with like. */
    val extractionPromptVersion: Int? = null,
    /** Short hash of the exact instruction block used. */
    val extractionPromptHash: String? = null,
    val createdAt: Instant? = null,
    val stage2ProcessedAt: Instant? = null,
)

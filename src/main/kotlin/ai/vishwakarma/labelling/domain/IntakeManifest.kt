package ai.vishwakarma.labelling.domain

import java.time.Instant

/** A seal-lifecycle action recorded in [IntakeManifest.sealEvents]. */
enum class SealAction {
    SEAL,
    UNSEAL;

    companion object {
        fun fromOrNull(raw: String?): SealAction? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * One audited seal-lifecycle event: who did what, when, and why. The mandatory [note] is the
 * operator's justification (confirmation summary on SEAL, reason on UNSEAL).
 */
data class SealEvent(
    val action: SealAction,
    /** Logged-in user who performed the action. */
    val actor: String? = null,
    val at: Instant,
    /** Mandatory operator note (enforced at the service layer). */
    val note: String = "",
)

/**
 * A per-subject rollup of the [Asset] inventory — Stage 2's stable handoff object. There is one
 * manifest per subject (document id == subjectId). It can be **sealed** to mark the intake as
 * complete and ready for downstream processing; counts are recomputed from the live asset set
 * whenever assets change or the manifest is read. Seal/unseal actions are audited in [sealEvents]
 * (append-only history replacing the old single sealedBy/sealedAt pair).
 */
data class IntakeManifest(
    /** Document id; equal to [subjectId] (one manifest per subject). */
    val id: String,
    val subjectId: String,
    val assetIds: List<String> = emptyList(),
    /** AssetModality.name → count. */
    val countsByModality: Map<String, Int> = emptyMap(),
    /** ContentType.name → count. */
    val countsByContentType: Map<String, Int> = emptyMap(),
    /** ConsentStatus.name → count. */
    val consentSummary: Map<String, Int> = emptyMap(),
    /** Human-readable reasons sealing is currently blocked; empty means ready to seal. */
    val sealBlockers: List<String> = emptyList(),
    val sealed: Boolean = false,
    /** Append-only seal/unseal audit history; the last entry reflects the current [sealed]. */
    val sealEvents: List<SealEvent> = emptyList(),
    /**
     * Stamped when Stage 2 begins consuming this manifest. Once set, the seal is permanent — not
     * even an ADMIN can unseal. Nothing sets this yet (no Stage 2 consumer exists); it is the
     * forward-looking lock the unseal path already honors.
     */
    val stage2StartedAt: Instant? = null,
    /**
     * §12.6 claim-review lifecycle. [reviewLockedAt] is stamped when the operator *starts* the
     * review — from that instant the subject's claims are frozen (no more extraction / re-run), so
     * the review can key off stable claim ids. [reviewSubmittedAt] is stamped when the review is
     * *submitted* (decisions final; Stage 3 may consume the approved-and-not-contested set). An
     * ADMIN may clear [reviewSubmittedAt] to reopen the review, but [reviewLockedAt] is permanent.
     */
    val reviewLockedAt: Instant? = null,
    val reviewSubmittedAt: Instant? = null,
    /**
     * F2 consent attestation (VA-32, product LLD §13.1): stamped every time the subject attests the
     * upload-consent text on the self-serve upload surface — required per upload batch, so the
     * latest attestation wins. One-time and irrevocable; no revocation surface exists anywhere.
     * Null for operator-driven intakes (consent is tracked per asset there).
     */
    val consentAttestedAt: Instant? = null,
    val consentAttestedBy: String? = null,
    val updatedAt: Instant? = null,
) {
    /** The most recent seal-lifecycle event, if any. */
    val lastSealEvent: SealEvent?
        get() = sealEvents.lastOrNull()

    /**
     * Actor of the seal currently in force (UI/API convenience; replaces the old sealedBy field).
     * Null while unsealed, even though past SEAL events remain in the history.
     */
    val sealedBy: String?
        get() = if (sealed) sealEvents.lastOrNull { it.action == SealAction.SEAL }?.actor else null

    /** Timestamp of the seal currently in force (replaces the old sealedAt field). */
    val sealedAt: Instant?
        get() = if (sealed) sealEvents.lastOrNull { it.action == SealAction.SEAL }?.at else null
}

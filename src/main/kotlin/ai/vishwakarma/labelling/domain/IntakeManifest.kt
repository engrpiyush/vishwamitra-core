package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * A per-subject rollup of the [Asset] inventory — Stage 2's stable handoff object. There is one
 * manifest per subject (document id == subjectId). It can be **sealed** to mark the intake as
 * complete and ready for downstream processing; counts are recomputed from the live asset set
 * whenever assets change or the manifest is read.
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
    val sealedBy: String? = null,
    val sealedAt: Instant? = null,
    val updatedAt: Instant? = null,
)

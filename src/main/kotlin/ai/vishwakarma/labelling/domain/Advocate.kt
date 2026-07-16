package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * Serving lifecycle of an advocate (LLD §7.3). Guest-facing copy collapses PROVISIONING /
 * DEPROVISIONING / DEPLOY_FAILED to "not available right now" (§8.2).
 */
enum class AdvocateState {
    NOT_BUILT,
    BUILDING,
    UNPROVISIONED,
    PROVISIONING,
    LIVE,
    DEPROVISIONING,
    DEPLOY_FAILED;

    companion object {
        fun fromOrNull(raw: String?): AdvocateState? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Serving-window presets (F10 fork: presets only — 1 day / 3 days / 1 week). */
enum class WindowPreset {
    D1,
    D3,
    W1;

    companion object {
        fun fromOrNull(raw: String?): WindowPreset? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * `advocates/{subjectId}` (LLD §5.1) — one per subject, lazily created when the operator registers
 * a trained model (§7.1). Score fields are denormalized from §10 for cheap rendering. The
 * `serving*` fields are the persisted [ai.vishwakarma.labelling.serving.ServingHandle] while a
 * window is in flight on the shared endpoint (v1.3 §7.4 — the ModelVersion idiom).
 */
data class Advocate(
    val subjectId: String,
    val state: AdvocateState = AdvocateState.NOT_BUILT,
    /** GCS URI of the merged tuned checkpoint, staged in the serving region (v1.3 §7.1). */
    val modelUri: String? = null,
    val windowPreset: WindowPreset? = null,
    /** Sweep clock (§3.4): LIVE past this ⇒ the sweeper deprovisions. */
    val windowEndsAt: Instant? = null,
    val windowSetBy: String? = null,
    val windowSetAt: Instant? = null,
    /** Operator-visible only — never rendered on guest/subject surfaces. */
    val lastError: String? = null,
    // ---- serving handle (v1.3 §7.4): the in-flight deployment on the shared endpoint ----
    /** The serving-container Vertex Model uploaded for this window's deploy. */
    val servingModelResource: String? = null,
    val servingDeployedModelId: String? = null,
    /** The in-flight Vertex LRO name while PROVISIONING/DEPROVISIONING; null when settled. */
    val servingOperation: String? = null,
    /** Deploy-timeout clock: set when a window starts, cleared when the state settles. */
    val provisioningStartedAt: Instant? = null,
    val aggregateScore: Double? = null,
    val scoredClaimCount: Int = 0,
    val scoreComputedAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    /** True while this advocate holds (or is acquiring/releasing) the shared endpoint. */
    val occupying: Boolean
        get() =
            state == AdvocateState.PROVISIONING ||
                state == AdvocateState.LIVE ||
                state == AdvocateState.DEPROVISIONING

    /** §10.1 presentation: round(100·A) — "Evidence strength: NN/100"; null while unscored. */
    val evidenceStrength: Int?
        get() = aggregateScore?.let { Math.round(it * 100).toInt() }
}

package ai.vishwakarma.labelling.domain

/**
 * Day-one product ops counters (VA-68, LLD §14.1) — the [Stage3Counters] idiom product-side: named
 * keys, incremented at the write site, no new infra. Keys are fields of one
 * `ops_counters/{subjectId}` doc, bumped atomically (FieldValue.increment) so they can never be
 * clobbered by the whole-doc advocate saves and never need a transaction.
 */
object OpsCounters {
    /** Every §6.3 wall redemption attempt, allowed or not. */
    const val WALL_ATTEMPTS = "wallAttempts"
    /** Attempts refused by an active (or just-tripped) lockout. */
    const val WALL_LOCKOUTS = "wallLockouts"
    /** Tokens successfully redeemed into guest sessions. */
    const val REDEMPTIONS = "redemptions"
    /** Window deploys kicked off (startWindow, incl. dry-run and immediate failures). */
    const val DEPLOYS_STARTED = "deploysStarted"
    /** Deploys that settled LIVE. */
    const val DEPLOYS_SUCCEEDED = "deploysSucceeded"
    /** Deploys that settled DEPLOY_FAILED (immediate, LRO error or timeout). */
    const val DEPLOYS_FAILED = "deploysFailed"
    /** Sum of measured deploy durations (start → LIVE), for the panel average. */
    const val DEPLOY_SECONDS_TOTAL = "deploySecondsTotal"
    /** Last measured deploy duration — set, not incremented. */
    const val LAST_DEPLOY_SECONDS = "lastDeploySeconds"
    /** Serving windows started. */
    const val WINDOWS_STARTED = "windowsStarted"
    /** §14.1 window $-estimate at startWindow (preset hours × hourly rate) — set, not summed. */
    const val LAST_WINDOW_ESTIMATE_USD = "lastWindowEstimateUsd"
    /** Cumulative $-estimate across every window started. */
    const val WINDOW_ESTIMATE_USD_TOTAL = "windowEstimateUsdTotal"
}

/**
 * One subject's ops-counter row as the Advocates panel renders it (§14.2). Every value defaults to
 * zero — a subject with no doc yet reads as all-zeros, so the panel is trustworthy on day one
 * (missing never renders as blank).
 */
data class SubjectOpsCounters(
    val wallAttempts: Long = 0,
    val wallLockouts: Long = 0,
    val redemptions: Long = 0,
    val deploysStarted: Long = 0,
    val deploysSucceeded: Long = 0,
    val deploysFailed: Long = 0,
    val deploySecondsTotal: Long = 0,
    val lastDeploySeconds: Long? = null,
    val windowsStarted: Long = 0,
    val lastWindowEstimateUsd: Double? = null,
    val windowEstimateUsdTotal: Double = 0.0,
) {
    /** Mean measured deploy duration in whole minutes; null until one completes. */
    val avgDeployMinutes: Long?
        get() =
            if (deploysSucceeded > 0 && deploySecondsTotal > 0)
                deploySecondsTotal / deploysSucceeded / 60
            else null

    val lastDeployMinutes: Long?
        get() = lastDeploySeconds?.let { it / 60 }
}

/** App-level daily mail counters (`mail_counters/{date}`, LLD §11.1/§14.1). */
data class MailCounters(
    /** Sends admitted under the daily cap (the cap counter — failures were admitted too). */
    val counted: Long = 0,
    /** Admitted sends whose transport failed (§11.2 — marked, never thrown). */
    val failed: Long = 0,
    /** Sends refused because the daily cap was hit. */
    val skippedCap: Long = 0,
)

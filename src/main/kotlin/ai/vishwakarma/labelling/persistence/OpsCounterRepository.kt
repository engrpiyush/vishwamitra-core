package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.OpsCounters
import ai.vishwakarma.labelling.domain.SubjectOpsCounters
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository

/**
 * `ops_counters` collection (VA-68, LLD §14.1) — one doc per subject, fields = [OpsCounters] keys.
 * Writes are merge + FieldValue.increment: atomic server-side, no transaction, and immune to the
 * whole-doc `advocates` saves happening around them. [bump] never throws — a counter is
 * observability, and observability must never break the flow it observes (the §11.2 posture).
 */
@Repository
class OpsCounterRepository(private val db: Firestore) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val col
        get() = db.collection(COLLECTION)

    /**
     * Add [increments] (each +n) and stamp [sets] (absolute values) on the subject's row in one
     * merge write, creating the doc on first touch. Failures log WARN and are swallowed.
     */
    fun bump(
        subjectId: String,
        increments: Map<String, Number> = emptyMap(),
        sets: Map<String, Number> = emptyMap(),
    ) {
        if (increments.isEmpty() && sets.isEmpty()) return
        try {
            val fields = buildMap {
                increments.forEach { (key, by) ->
                    // Keep integer counters as Firestore longs; only $ totals are doubles.
                    put(
                        key,
                        when (by) {
                            is Double,
                            is Float -> FieldValue.increment(by.toDouble())
                            else -> FieldValue.increment(by.toLong())
                        },
                    )
                }
                sets.forEach { (key, value) -> put(key, value) }
            }
            col.document(subjectId).set(fields, SetOptions.merge()).await()
        } catch (e: Exception) {
            log.warn(
                "Ops counter bump failed for {} ({}): {}",
                subjectId,
                increments.keys,
                e.message
            )
        }
    }

    fun find(subjectId: String): SubjectOpsCounters =
        col.document(subjectId).get().await().takeIf { it.exists() }?.toCounters()
            ?: SubjectOpsCounters()

    /**
     * Every subject's row, keyed by subjectId — absent subjects read as all-zeros at the call site.
     */
    fun findAll(): Map<String, SubjectOpsCounters> =
        db.collection(COLLECTION).get().await().documents.associate { it.id to it.toCounters() }

    private fun DocumentSnapshot.toCounters(): SubjectOpsCounters =
        SubjectOpsCounters(
            wallAttempts = getLong(OpsCounters.WALL_ATTEMPTS) ?: 0,
            wallLockouts = getLong(OpsCounters.WALL_LOCKOUTS) ?: 0,
            redemptions = getLong(OpsCounters.REDEMPTIONS) ?: 0,
            deploysStarted = getLong(OpsCounters.DEPLOYS_STARTED) ?: 0,
            deploysSucceeded = getLong(OpsCounters.DEPLOYS_SUCCEEDED) ?: 0,
            deploysFailed = getLong(OpsCounters.DEPLOYS_FAILED) ?: 0,
            deploySecondsTotal = getLong(OpsCounters.DEPLOY_SECONDS_TOTAL) ?: 0,
            lastDeploySeconds = getLong(OpsCounters.LAST_DEPLOY_SECONDS),
            windowsStarted = getLong(OpsCounters.WINDOWS_STARTED) ?: 0,
            lastWindowEstimateUsd = getDouble(OpsCounters.LAST_WINDOW_ESTIMATE_USD),
            windowEstimateUsdTotal = getDouble(OpsCounters.WINDOW_ESTIMATE_USD_TOTAL) ?: 0.0,
        )

    companion object {
        const val COLLECTION = "ops_counters"
    }
}

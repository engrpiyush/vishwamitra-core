package ai.vishwakarma.labelling.persistence

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import org.springframework.stereotype.Repository

/**
 * Metadata for the subject's ONE current profile PDF (Stage 3.5 LLD §7, D6) — doc id = subjectId,
 * replace-on-set; the bytes live at [objectPath] in the intake bucket (or `var/intake/` in dev).
 * Regenerating overwrites both; deleting removes both. `scoreRunId` + [provisional] stamp which
 * run's numbers the report cites, so the dashboard can nag when it predates the latest publish.
 */
data class SubjectReport(
    val subjectId: String,
    val objectPath: String,
    val scoreRunId: String?,
    val score: Double,
    val display: Int,
    val band: String,
    /** Generated before the run published — the PROVISIONAL watermark was applied (D7). */
    val provisional: Boolean,
    val generatedAt: Instant?,
    val generatedBy: String?,
    val sizeBytes: Long,
)

@Repository
class SubjectReportRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun save(report: SubjectReport) {
        col.document(report.subjectId).set(report.toMap()).await()
    }

    fun find(subjectId: String): SubjectReport? =
        col.document(subjectId).get().await().takeIf { it.exists() }?.toReport()

    fun delete(subjectId: String) {
        col.document(subjectId).delete().await()
    }

    private fun SubjectReport.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "objectPath" to objectPath,
            "scoreRunId" to scoreRunId,
            "score" to score,
            "display" to display,
            "band" to band,
            "provisional" to provisional,
            "generatedAt" to generatedAt.toTimestamp(),
            "generatedBy" to generatedBy,
            "sizeBytes" to sizeBytes,
        )

    private fun DocumentSnapshot.toReport(): SubjectReport =
        SubjectReport(
            subjectId = getString("subjectId") ?: id,
            objectPath = getString("objectPath") ?: "",
            scoreRunId = getString("scoreRunId"),
            score = getDouble("score") ?: 0.0,
            display = (get("display") as? Number)?.toInt() ?: 0,
            band = getString("band") ?: "UNSUPPORTED",
            provisional = getBoolean("provisional") ?: false,
            generatedAt = instant("generatedAt"),
            generatedBy = getString("generatedBy"),
            sizeBytes = (get("sizeBytes") as? Number)?.toLong() ?: 0L,
        )

    companion object {
        const val COLLECTION = "subject_reports"
    }
}

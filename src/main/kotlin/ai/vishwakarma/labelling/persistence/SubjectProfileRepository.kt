package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.DoNotDiscussApproval
import ai.vishwakarma.labelling.domain.DoNotDiscussCustom
import ai.vishwakarma.labelling.domain.EmploymentType
import ai.vishwakarma.labelling.domain.SubjectProfile
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import java.time.Instant
import java.time.LocalDate
import org.springframework.stereotype.Repository

/**
 * `subject_profiles` (SubjectProfile LLD §2.2) — one doc per subject, doc id = subjectId, a sibling
 * of `subject_persona` rather than a field on the manifest: the manifest is rebuilt from the live
 * asset set on every read (`IntakeService.recomputeManifest`), so anything parked on it that is not
 * explicitly carried through is silently erased.
 *
 * Lock state is deliberately **not** stored here — the profile's frozen/editable state is read from
 * the live `IntakeManifest.sealed` flag (§6.1), never duplicated.
 */
@Repository
class SubjectProfileRepository(private val db: Firestore) {

    private val col
        get() = db.collection(COLLECTION)

    fun findBySubject(subjectId: String): SubjectProfile? =
        col.document(subjectId).get().await().takeIf { it.exists() }?.toProfile()

    fun save(profile: SubjectProfile) {
        col.document(profile.subjectId).set(profile.toMap()).await()
    }

    fun delete(subjectId: String) {
        col.document(subjectId).delete().await()
    }

    private fun SubjectProfile.toMap(): Map<String, Any?> =
        mapOf(
            "subjectId" to subjectId,
            "country" to country,
            "marketRegion" to marketRegion,
            "currency" to currency,
            "timezone" to timezone,
            "primaryLanguage" to primaryLanguage,
            // ISO-8601 text, not a Timestamp: knowledgeAsOf is a calendar date, and a timestamp
            // round-trip would drag a timezone into a field that has none.
            "knowledgeAsOf" to knowledgeAsOf?.toString(),
            // ---- C/D (declared evidence) ----
            "targetRoles" to targetRoles,
            "targetSeniority" to targetSeniority,
            "employmentType" to employmentType?.name,
            "openToRelocation" to openToRelocation,
            "aspirations" to aspirations,
            "statedPreferences" to statedPreferences,
            "doNotDiscussChecks" to doNotDiscussChecks,
            "doNotDiscussCustom" to
                doNotDiscussCustom?.let {
                    mapOf(
                        "text" to it.text,
                        "state" to it.state.name,
                        "approver" to it.approver,
                        "at" to it.at?.toTimestamp(),
                    )
                },
            "updatedBy" to updatedBy,
            "updatedAt" to (updatedAt ?: Instant.now()).toTimestamp(),
            "profileHash" to profileHash,
        )

    private fun DocumentSnapshot.toProfile(): SubjectProfile =
        SubjectProfile(
            subjectId = getString("subjectId") ?: id,
            country = getString("country"),
            marketRegion = getString("marketRegion"),
            currency = getString("currency"),
            timezone = getString("timezone"),
            primaryLanguage = getString("primaryLanguage"),
            knowledgeAsOf = localDate("knowledgeAsOf"),
            targetRoles = strings("targetRoles"),
            targetSeniority = getString("targetSeniority"),
            employmentType = EmploymentType.fromOrNull(getString("employmentType")),
            openToRelocation = getBoolean("openToRelocation"),
            aspirations = strings("aspirations"),
            statedPreferences = strings("statedPreferences"),
            doNotDiscussChecks = strings("doNotDiscussChecks"),
            doNotDiscussCustom = doNotDiscussCustom(),
            updatedBy = getString("updatedBy"),
            updatedAt = instant("updatedAt"),
            profileHash = getString("profileHash"),
        )

    private fun DocumentSnapshot.strings(field: String): List<String> =
        (get(field) as? List<*>)?.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
            ?: emptyList()

    /**
     * Tolerant read of the OD-9 custom entry: a misshapen or text-less map is treated as absent,
     * and an unparseable state falls back to PENDING — never APPROVED, so a poisoned document can
     * only ever fail *closed* (an unvetted string never materialises).
     */
    private fun DocumentSnapshot.doNotDiscussCustom(): DoNotDiscussCustom? {
        @Suppress("UNCHECKED_CAST") val raw = get("doNotDiscussCustom") as? Map<String, Any?>
        val text = (raw?.get("text") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return DoNotDiscussCustom(
            text = text,
            state =
                DoNotDiscussApproval.fromOrNull(raw["state"] as? String)
                    ?: DoNotDiscussApproval.PENDING,
            approver = raw["approver"] as? String,
            at =
                (raw["at"] as? Timestamp)?.let {
                    Instant.ofEpochSecond(it.seconds, it.nanos.toLong())
                },
        )
    }

    /** Tolerant read: an unparseable date is treated as absent, never a poisoned document. */
    private fun DocumentSnapshot.localDate(field: String): LocalDate? =
        getString(field)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    companion object {
        const val COLLECTION = "subject_profiles"
    }
}

package ai.vishwakarma.labelling.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * The manually-entered subject block (SubjectProfile LLD §2.1), `subject_profiles`, doc id =
 * subjectId — sparse and all-nullable exactly like [SubjectPersona]: a blank profile is legal and
 * resolves to today's behaviour. This carries **A** (locale & environment), **B** (knowledge
 * freshness), **C** (candidacy / targeting), **D** (declared narrative) and **E** (contact / PII,
 * slice 3).
 *
 * The three kinds of field are different *kinds* of thing and the code keeps them apart everywhere:
 * - **A/B are context.** They render into the generation prompt as `{{locale}}` /
 *   `{{knowledge_as_of}}`, never assert a fact about the person, and so carry no fabrication
 *   surface (§10).
 * - **C/D are declared evidence.** They become provenance-tagged `SUBJECT_DECLARED` claims at the
 *   seal ([ai.vishwakarma.labelling.service.ProfileClaimMaterialiser]) and travel the ordinary
 *   ledger path from there. Nothing in C/D reaches a prompt directly.
 * - **E is declared PII.** Each [ContactField] is **private by default**; a `shareable` one
 *   materialises at the seal as a `sensitive` declared claim opted-in through the *existing* O7
 *   machinery (§5.2), reaching Row-8 verbatim, while a non-shareable one is never materialised and
 *   stays under the trained REFUSE posture. `shareable` is a **training-time** gate, not a live
 *   toggle (§5.4): un-sharing after the advocate is tuned is a re-seal / re-tune, never a runtime
 *   change.
 *
 * [profileHash] is the SHA-256 over the *resolved* A/B scalars — the third drift axis beside
 * scoreRunId and personaHash ([Stage4Stamp], §6.4): an A/B-only edit changes no claim and no score,
 * so without it stale-locale notebooks would survive and export. C/D deliberately stay **out** of
 * that hash: a C/D edit rewrites the claim set, which forces a fresh Stage 3 run and moves
 * `scoreRunId`, so the drift sweep already catches it on the axis that actually changed. Folding
 * them in would additionally change the hash of every existing A/B profile and archive the lot.
 */
data class SubjectProfile(
    val subjectId: String,
    // ---- A. Locale & environment ----
    /** ISO-3166 alpha-2, e.g. "IN". */
    val country: String? = null,
    /** Free label, e.g. "IN", "EU", "US-West"; falls back to [country] in the locale label. */
    val marketRegion: String? = null,
    /** ISO-4217, e.g. "INR". */
    val currency: String? = null,
    /** IANA zone id, e.g. "Asia/Kolkata". */
    val timezone: String? = null,
    /** BCP-47, e.g. "en-IN". */
    val primaryLanguage: String? = null,
    // ---- B. Knowledge freshness ----
    /** Profile override for `{{knowledge_as_of}}`; null ⇒ the publish date is used (§4.3). */
    val knowledgeAsOf: LocalDate? = null,
    // ---- C. Candidacy / targeting (declared evidence) ----
    /** Roles the subject says he is targeting, e.g. "Staff Engineer". */
    val targetRoles: List<String> = emptyList(),
    /** Target band, e.g. "staff", "principal". */
    val targetSeniority: String? = null,
    val employmentType: EmploymentType? = null,
    val openToRelocation: Boolean? = null,
    // ---- D. Declared narrative (declared evidence) ----
    /** "Optimising for staff-level IC work, not management." — the O5 gate's real source. */
    val aspirations: List<String> = emptyList(),
    val statedPreferences: List<String> = emptyList(),
    /** Curated [DoNotDiscussVocabulary] keys the subject toggled (OD-9). */
    val doNotDiscussChecks: List<String> = emptyList(),
    /** One bespoke entry, inert until an admin approves it (OD-9). */
    val doNotDiscussCustom: DoNotDiscussCustom? = null,
    // ---- E. Contact / PII (declared PII) ----
    /**
     * Contact details the subject offered, each [ContactField.shareable] **private by default**
     * (§5). A shareable field is the O7 opt-in the seal-time materialiser translates to a
     * `sensitive` declared claim with `piiChoice = INCLUDE`; a non-shareable one is never spoken.
     */
    val contact: List<ContactField> = emptyList(),
    // ---- audit ----
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
    val profileHash: String? = null,
)

/**
 * One declared contact detail (SubjectProfile LLD §2.1/§5). [shareable] is the per-field O7 gate
 * and is **false by default** — the subject must actively opt each field in for the advocate to
 * ever speak it. It is a *training-time* consent: a shareable field materialises as a `sensitive`
 * declared claim with `piiChoice = INCLUDE` (§5.2), so un-sharing after the tune is a re-seal /
 * re-tune, never a live toggle (§5.4).
 */
data class ContactField(
    val kind: ContactKind,
    val value: String,
    val shareable: Boolean = false,
)

/**
 * The contact kinds a subject may declare (§2.1). Deliberately excludes home address, date of birth
 * and government ID — those have **no shareable form**: they map to the §14 100%-required O7 safety
 * bar and always REFUSE ([ai.vishwakarma.labelling.stage4.Stage4EvalProbes]), so they are never
 * offered as a field.
 */
enum class ContactKind {
    NAME,
    EMAIL,
    LINKEDIN,
    PORTFOLIO,
    PHONE;

    companion object {
        fun fromOrNull(raw: String?): ContactKind? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * A fully-materialized profile: every scalar non-null (absent ⇒ blank), the `{{locale}}` label
 * built. This — not the sparse stored doc — is what a Stage 4 run freezes. Mirrors
 * [ResolvedPersona], with one deliberate difference: a *blank* profile hashes to **null**
 * ([hashOrNull]), so subjects with no profile behave byte-for-byte as they do today and the drift
 * axis stays dormant until someone actually fills the form in.
 */
data class ResolvedSubjectProfile(
    val country: String = "",
    val marketRegion: String = "",
    val currency: String = "",
    val timezone: String = "",
    val primaryLanguage: String = "",
    val knowledgeAsOf: LocalDate? = null,
    // ---- C/D: declared evidence, normalised (trimmed, blank-dropped, de-duplicated) ----
    val targetRoles: List<String> = emptyList(),
    val targetSeniority: String = "",
    val employmentType: EmploymentType? = null,
    val openToRelocation: Boolean? = null,
    val aspirations: List<String> = emptyList(),
    val statedPreferences: List<String> = emptyList(),
    /** Only keys [DoNotDiscussVocabulary] still recognises, in vocabulary order. */
    val doNotDiscussChecks: List<String> = emptyList(),
    /** Present only when the admin approved it — [DoNotDiscussCustom.materialisable] (OD-9). */
    val approvedDoNotDiscussCustom: String? = null,
    /**
     * The declared contact fields, normalised (trimmed, blank-value-dropped), **both** shareable
     * and private kept — the materialiser reads [ContactField.shareable] per field to decide which
     * one becomes a `sensitive` opted-in claim and which stays unspoken (§5.2). Contact is *not* in
     * [profileHash] (it reaches Stage 4 as a claim, never a prompt, exactly like C/D — §12.8.2).
     */
    val contact: List<ContactField> = emptyList(),
) {

    /**
     * True when nothing in **A/B** is declared — the legacy-equivalent profile.
     *
     * Deliberately blind to C/D: this predicate gates the `{{knowledge_as_of}}` publish-date rung
     * (§12.2), so counting a declared aspiration here would start emitting a freshness clause into
     * the prompt of a subject who set no locale and no date. C/D never reach a prompt; ask
     * [declaredBlank] about them.
     */
    val blank: Boolean
        get() =
            country.isBlank() &&
                marketRegion.isBlank() &&
                currency.isBlank() &&
                timezone.isBlank() &&
                primaryLanguage.isBlank() &&
                knowledgeAsOf == null

    /**
     * True when nothing in C/D/E would materialise — the seal-time materialiser writes no claim for
     * such a subject. E counts only when a contact is **shareable**: a private-only contact is
     * stored but never becomes a claim (§5.2), so it does not lift this predicate.
     */
    val declaredBlank: Boolean
        get() =
            targetRoles.isEmpty() &&
                targetSeniority.isBlank() &&
                employmentType == null &&
                openToRelocation == null &&
                aspirations.isEmpty() &&
                statedPreferences.isEmpty() &&
                doNotDiscussChecks.isEmpty() &&
                approvedDoNotDiscussCustom == null &&
                contact.none { it.shareable }

    /**
     * The `{{locale}}` substitution value, e.g. "the India market (INR), primary language en-IN"
     * (§4.4). Blank when A carries nothing — the OD-6 infer-from-evidence marker, which drops the
     * context clause from the prompt entirely rather than voicing an empty locale.
     */
    val locale: String
        get() {
            val market = marketRegion.ifBlank { country }
            val parts = buildList {
                if (market.isNotBlank()) {
                    val label = regionLabel(market)
                    add(
                        if (currency.isNotBlank()) "the $label market ($currency)"
                        else "the $label market"
                    )
                } else if (currency.isNotBlank()) {
                    add("a $currency market")
                }
                if (primaryLanguage.isNotBlank()) add("primary language $primaryLanguage")
                if (timezone.isNotBlank()) add("timezone $timezone")
            }
            return parts.joinToString(", ")
        }

    /**
     * SHA-256 (hex) over the canonical `key=value` rendering of every resolved A/B scalar, sorted
     * by key — the [SubjectProfile.profileHash] contract. Null for a [blank] profile (see the class
     * doc): "no profile" and "a profile that happens to be empty" are the same state, and neither
     * may archive anything.
     */
    fun hashOrNull(): String? {
        // A/B only, by construction: see the [SubjectProfile] class doc — C/D drift is carried by
        // scoreRunId, and hashing them here would archive every existing A/B subject's notebooks.
        if (blank) return null
        val canonical =
            sortedMapOf(
                    "country" to country,
                    "marketRegion" to marketRegion,
                    "currency" to currency,
                    "timezone" to timezone,
                    "primaryLanguage" to primaryLanguage,
                    "knowledgeAsOf" to (knowledgeAsOf?.toString() ?: ""),
                    "locale" to locale,
                )
                .entries
                .joinToString("\n") { (k, v) -> "$k=$v" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun regionLabel(raw: String): String {
        if (raw.length != 2 || !raw.all { it.isLetter() }) return raw
        // Locale.ENGLISH deliberately: the label is prompt text and a hash ingredient, so it must
        // not shift with the JVM's default locale.
        val display =
            runCatching {
                    Locale.Builder()
                        .setRegion(raw.uppercase())
                        .build()
                        .getDisplayCountry(Locale.ENGLISH)
                }
                .getOrNull()
        return display?.takeIf { it.isNotBlank() && !it.equals(raw, ignoreCase = true) } ?: raw
    }
}

/**
 * Materializes a [ResolvedSubjectProfile] from a (possibly absent) stored doc. There are no "bold
 * defaults" here as there are for the persona — an unanswered A/B field means *unknown*, and
 * unknown resolves to blank, which the injection path reads as "say nothing about it".
 */
object SubjectProfileDefaults {

    fun resolve(stored: SubjectProfile?): ResolvedSubjectProfile =
        ResolvedSubjectProfile(
            country = stored?.country.clean(),
            marketRegion = stored?.marketRegion.clean(),
            currency = stored?.currency.clean(),
            timezone = stored?.timezone.clean(),
            primaryLanguage = stored?.primaryLanguage.clean(),
            knowledgeAsOf = stored?.knowledgeAsOf,
            targetRoles = stored?.targetRoles.cleanList(),
            targetSeniority = stored?.targetSeniority.clean(),
            employmentType = stored?.employmentType,
            openToRelocation = stored?.openToRelocation,
            aspirations = stored?.aspirations.cleanList(),
            statedPreferences = stored?.statedPreferences.cleanList(),
            // Unknown keys are dropped rather than carried: the write-time validator rejects them,
            // so a survivor here is a key retired from the vocabulary after the doc was stored, and
            // a retired boundary must stop materialising instead of failing the seal.
            doNotDiscussChecks =
                DoNotDiscussVocabulary.known(stored?.doNotDiscussChecks ?: emptyList()),
            approvedDoNotDiscussCustom =
                stored?.doNotDiscussCustom?.takeIf { it.materialisable }?.text?.trim(),
            contact = stored?.contact.cleanContacts(),
        )

    private fun String?.clean(): String = this?.trim() ?: ""

    /** Trim contact values, drop blank-valued entries; keep kind, order and the shareable flag. */
    private fun List<ContactField>?.cleanContacts(): List<ContactField> =
        orEmpty().map { it.copy(value = it.value.trim()) }.filter { it.value.isNotBlank() }

    /** Trim, drop blanks, de-duplicate case-insensitively, keep first-declared order. */
    private fun List<String>?.cleanList(): List<String> {
        val seen = mutableSetOf<String>()
        return orEmpty().map { it.trim() }.filter { it.isNotBlank() && seen.add(it.lowercase()) }
    }
}

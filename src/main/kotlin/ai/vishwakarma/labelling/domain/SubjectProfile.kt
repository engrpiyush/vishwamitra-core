package ai.vishwakarma.labelling.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * The manually-entered subject block (SubjectProfile LLD §2.1), `subject_profiles`, doc id =
 * subjectId — sparse and all-nullable exactly like [SubjectPersona]: a blank profile is legal and
 * resolves to today's behaviour. This slice carries **A** (locale & environment) and **B**
 * (knowledge freshness) only; the C/D declared-evidence fields and E contact fields arrive with
 * their own slices.
 *
 * A/B are *context*, never a claimed fact about the person — they render into the generation prompt
 * as `{{locale}}` / `{{knowledge_as_of}}` and can therefore never introduce a fabrication (§10).
 *
 * [profileHash] is the SHA-256 over the *resolved* A/B scalars — the third drift axis beside
 * scoreRunId and personaHash ([Stage4Stamp], §6.4): an A/B-only edit changes no claim and no score,
 * so without it stale-locale notebooks would survive and export.
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
    // ---- audit ----
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
    val profileHash: String? = null,
)

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
) {

    /** True when nothing at all is declared — the legacy-equivalent profile. */
    val blank: Boolean
        get() =
            country.isBlank() &&
                marketRegion.isBlank() &&
                currency.isBlank() &&
                timezone.isBlank() &&
                primaryLanguage.isBlank() &&
                knowledgeAsOf == null

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
        )

    private fun String?.clean(): String = this?.trim() ?: ""
}

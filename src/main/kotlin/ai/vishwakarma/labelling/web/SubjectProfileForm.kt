package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.ResolvedSubjectProfile
import ai.vishwakarma.labelling.service.DomainError
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/** One `<option>` of a profile dropdown: the stored value beside the label a human reads. */
data class ProfileOption(val value: String, val label: String)

/** One line of the read-only profile summary (post-seal / post-submit rendering). */
data class ProfileSummaryRow(val label: String, val value: String)

/**
 * The shared A/B form bridge for both SubjectProfile surfaces (profile LLD §7.1/§7.2, VA-140 +
 * VA-141): the dropdown catalogs, the read-only summary, and the subject-safe error copy — pure
 * functions in the [SubjectTraining]/[SubjectProvisioning] idiom so the rules are unit-testable and
 * the two controllers cannot drift apart.
 *
 * The catalogs exist for one hard reason: `SubjectProfileService.put` validates country against
 * ISO-3166, currency against ISO-4217, timezone against the IANA zone database and language against
 * BCP-47 (`service/SubjectProfileService.kt:150-163`), and rejects the whole save on a typo. A free
 * text box would therefore turn a plausible answer ("India", "Rupees") into a refusal — and on the
 * subject surface the refusal string is machinery vocabulary the §12.3 copy rule forbids rendering.
 * Every value below is drawn from the same JDK tables those validators consult, so a pick from the
 * list can never be refused (`SubjectProfileFormTest` pins exactly that).
 *
 * Labels render through [Locale.ENGLISH] deliberately, mirroring
 * `ResolvedSubjectProfile.regionLabel` (`domain/SubjectProfile.kt:119-132`): the country label is a
 * prompt-text and hash ingredient there, so the name an operator picks from must not shift with the
 * JVM's default locale.
 */
object SubjectProfileForm {

    /** ISO-3166 alpha-2 → English country name, e.g. `IN` → "India". */
    val countries: List<ProfileOption> by lazy {
        Locale.getISOCountries()
            .map { code -> ProfileOption(code, regionName(code)) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * ISO-4217 codes that a real locale actually uses — the full `Currency.getAvailableCurrencies`
     * table carries historical units nobody is paid in, and this list only has to cover markets a
     * subject works in.
     */
    val currencies: List<ProfileOption> by lazy {
        Locale.getAvailableLocales()
            .filter { it.country.isNotBlank() }
            .mapNotNull { runCatching { Currency.getInstance(it) }.getOrNull() }
            .distinctBy { it.currencyCode }
            .map { currency ->
                val name = currency.getDisplayName(Locale.ENGLISH)
                ProfileOption(currency.currencyCode, currency.currencyCode + " — " + name)
            }
            .sortedBy { it.value }
    }

    /**
     * IANA zone ids, region/city form only. The legacy aliases (the `Etc` and `SystemV` families,
     * bare `EST`) are dropped: they validate, but they are not what a person means by where they
     * work. (Spelled without a glob on purpose — a `/` followed by a star opens a nested block
     * comment and eats the rest of the file.)
     */
    val timezones: List<ProfileOption> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
            .map { ProfileOption(it, it.replace('_', ' ')) }
    }

    /**
     * Region-qualified BCP-47 tags (`en-IN`, not bare `en`) — the §2.1 example shape, and the one
     * that tells an advocate which English to write. Script/variant locales are filtered out: they
     * validate but they add thousands of near-duplicate rows to a form.
     */
    val languages: List<ProfileOption> by lazy {
        Locale.getAvailableLocales()
            .filter { it.language.isNotBlank() && it.country.isNotBlank() }
            .filter { it.script.isBlank() && it.variant.isBlank() }
            .distinctBy { it.toLanguageTag() }
            .map { ProfileOption(it.toLanguageTag(), it.getDisplayName(Locale.ENGLISH)) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The read-only rendering of a stored profile — what both surfaces show once the manifest is
     * sealed (and what the subject sees after submit). Blank fields are omitted rather than shown
     * as empty rows: an unanswered A/B field means *unknown*, not "none" (§2.1).
     *
     * [locale] is included because it is the value that actually reaches the prompt — the operator
     * and the subject should see the sentence their answers produce, not just the raw codes.
     */
    fun summary(resolved: ResolvedSubjectProfile): List<ProfileSummaryRow> = buildList {
        if (resolved.country.isNotBlank()) {
            add(ProfileSummaryRow("Where you're based", regionName(resolved.country)))
        }
        if (resolved.marketRegion.isNotBlank()) {
            add(ProfileSummaryRow("Market", resolved.marketRegion))
        }
        if (resolved.currency.isNotBlank()) {
            add(ProfileSummaryRow("Currency", resolved.currency))
        }
        if (resolved.primaryLanguage.isNotBlank()) {
            add(ProfileSummaryRow("Main language", resolved.primaryLanguage))
        }
        if (resolved.timezone.isNotBlank()) {
            add(ProfileSummaryRow("Time zone", resolved.timezone.replace('_', ' ')))
        }
        resolved.knowledgeAsOf?.let { add(ProfileSummaryRow("Current as of", it.toString())) }
        if (resolved.locale.isNotBlank()) {
            add(ProfileSummaryRow("Your advocate reads this as", resolved.locale))
        }
    }

    /**
     * A [DomainError] from `SubjectProfileService.put`, translated for a subject (§12.3: verbatim
     * service strings never render in `templates/subject`). The refusals carry a subject id, the
     * config key `app.stage4.profile-enabled` and the words "manifest"/"unseal" — every one of them
     * machinery vocabulary. Operator surfaces keep the raw message; only this path rewrites it.
     */
    fun friendly(error: DomainError): String =
        when (error) {
            // Conflict is the seal gate (and, unreachably from the UI, the feature flag): the
            // subject's own submit is what closed the window, so the copy names that, not the lock.
            is DomainError.Conflict ->
                "Your details are settled now that you've submitted — tell us if something needs " +
                    "changing and we'll sort it out."
            is DomainError.Invalid ->
                "Some of that didn't come through — please pick from the lists and give the date " +
                    "as a day on the calendar."
            is DomainError.NotFound -> SubjectTrainingController.GENERIC_SORRY
        }

    /** English country name for an alpha-2 code; falls back to the code itself. */
    private fun regionName(code: String): String =
        runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code
}

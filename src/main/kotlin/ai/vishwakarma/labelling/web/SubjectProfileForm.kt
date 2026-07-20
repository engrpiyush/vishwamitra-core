package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.DoNotDiscussVocabulary
import ai.vishwakarma.labelling.domain.EmploymentType
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
     * The C engagement-model choice (VA-149/150) — the [EmploymentType] enum behind subject-safe
     * labels. The stored value is the enum name (`EmploymentType.valueOf`), so `value` must stay
     * the name; only the `label` is friendly. Shared by both surfaces so the operator and the
     * subject read the same three options in the same words.
     */
    val employmentTypes: List<ProfileOption> =
        listOf(
            ProfileOption(EmploymentType.FTE.name, "A permanent role"),
            ProfileOption(EmploymentType.CONTRACT.name, "Contract work"),
            ProfileOption(EmploymentType.EITHER.name, "Either — I'm open to both"),
        )

    /**
     * The C seniority band (VA-149/150). `targetSeniority` is a free declared line (§2.1), not an
     * enum, so this is a *convenience* vocabulary rather than a validated table: the value equals
     * the label and each option is a plain word the service's declared-line validator already
     * accepts. A curated list keeps the two surfaces consistent and the stored text clean, while
     * the field stays free-form for any band an operator types directly through the API.
     */
    val seniorities: List<ProfileOption> =
        listOf(
                "Junior",
                "Mid-level",
                "Senior",
                "Lead",
                "Staff",
                "Principal",
                "Distinguished",
                "Manager",
                "Director",
                "VP or above",
            )
            .map { ProfileOption(it, it) }

    /**
     * The curated do-not-discuss checklist (VA-145/149/150) — passed straight through from the
     * code-governed [DoNotDiscussVocabulary] so both surfaces render the identical topics and the
     * checklist cannot drift from what `put` validates against. Each [DoNotDiscussVocabulary.Topic]
     * carries the stored `key` and the subject-facing `label`.
     */
    val doNotDiscussTopics: List<DoNotDiscussVocabulary.Topic> = DoNotDiscussVocabulary.topics

    /**
     * The read-only rendering of a stored profile — what both surfaces show once the manifest is
     * sealed (and what the subject sees after submit). Blank fields are omitted rather than shown
     * as empty rows: an unanswered field means *unknown*, not "none" (§2.1).
     *
     * The A/B rows come first and end on [locale] — the value that actually reaches the prompt, so
     * the operator and the subject see the sentence their answers produce, not just the raw codes.
     * The C/D rows follow: declared candidacy and narrative, in subject-safe words (§12.3), each
     * omitted when empty. The bespoke do-not-discuss entry is **not** here — it carries an approval
     * state the resolved profile has already collapsed (only an APPROVED one survives into
     * [ResolvedSubjectProfile.approvedDoNotDiscussCustom]); its pending/rejected life is rendered
     * from the stored doc by the templates, so a subject can see "waiting for review".
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
        // ---- C/D: declared candidacy & narrative, subject-safe (§12.3) ----
        if (resolved.targetRoles.isNotEmpty()) {
            add(
                ProfileSummaryRow(
                    "Roles you're aiming for",
                    resolved.targetRoles.joinToString("; ")
                )
            )
        }
        if (resolved.targetSeniority.isNotBlank()) {
            add(ProfileSummaryRow("Level you're targeting", resolved.targetSeniority))
        }
        resolved.employmentType?.let {
            add(ProfileSummaryRow("How you'd like to work", employmentLabel(it)))
        }
        resolved.openToRelocation?.let {
            add(ProfileSummaryRow("Open to relocating", if (it) "Yes" else "No"))
        }
        if (resolved.aspirations.isNotEmpty()) {
            add(
                ProfileSummaryRow(
                    "What you're optimising for",
                    resolved.aspirations.joinToString(" · "),
                )
            )
        }
        if (resolved.statedPreferences.isNotEmpty()) {
            add(
                ProfileSummaryRow(
                    "Preferences you've shared",
                    resolved.statedPreferences.joinToString(" · "),
                )
            )
        }
        if (resolved.doNotDiscussChecks.isNotEmpty()) {
            add(
                ProfileSummaryRow(
                    "Topics to keep private",
                    resolved.doNotDiscussChecks.joinToString("; ") {
                        DoNotDiscussVocabulary.labelOf(it) ?: it
                    },
                )
            )
        }
    }

    /** Subject-safe label for a stored [EmploymentType] — the read side of [employmentTypes]. */
    fun employmentLabel(type: EmploymentType): String =
        employmentTypes.firstOrNull { it.value == type.name }?.label ?: type.name

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

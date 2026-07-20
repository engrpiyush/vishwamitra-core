package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ResolvedSubjectProfile
import ai.vishwakarma.labelling.domain.SubjectProfile
import ai.vishwakarma.labelling.domain.SubjectProfileDefaults
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.SubjectProfileRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import org.springframework.stereotype.Service

/** `PUT /api/subjects/{id}/profile` body — raw A/B answers; null/blank = not declared (§7). */
data class SubjectProfileUpdateRequest(
    val country: String? = null,
    val marketRegion: String? = null,
    val currency: String? = null,
    val timezone: String? = null,
    val primaryLanguage: String? = null,
    /** ISO-8601 date text, e.g. "2026-07-15". */
    val knowledgeAsOf: String? = null,
)

/** What the profile form reads: the sparse stored doc beside its full materialization. */
data class SubjectProfileView(
    val subjectId: String,
    val stored: SubjectProfile?,
    /**
     * What [SubjectProfileService.resolved] would hand Stage 4 **right now** — so it is blank
     * whenever the surface is off, even though [stored] still holds the answers. A view reporting a
     * live locale line while generation injects nothing would invent a fourth drift story on the
     * one panel that exists to make the three real ones legible (§6.4).
     */
    val resolved: ResolvedSubjectProfile,
    /** Hash of [resolved] — what a generation run started now would freeze; null when blank. */
    val profileHash: String?,
    /**
     * Live manifest seal state: true ⇒ the profile is frozen and [SubjectProfileService.put]
     * refuses (the edit path is an audited ADMIN unseal, §6.2).
     */
    val sealed: Boolean,
    /**
     * Whether the profile surface is switched on at all (`app.stage4.profile-enabled`, §8). False ⇒
     * [SubjectProfileService.put] refuses every write, so the UI hides the form rather than
     * offering a box whose save can only fail — and, on the subject side, rather than surfacing a
     * refusal that names a config key (§12.3). Read-only views stay legal either way.
     */
    val enabled: Boolean,
)

/**
 * The SubjectProfile backend (LLD §2.2/§2.3/§6.1): stores the sparse A/B block, materializes the
 * resolved profile Stage 4 injects, and computes the profileHash that pins generation runs.
 *
 * It mirrors [PersonaService] with **one deliberate divergence** (§2.3): the persona is free-edit
 * at any time, while the profile is *evidence* and freezes at the Stage 1→2 seal. [put] therefore
 * reads the live `IntakeManifest.sealed` flag and refuses once sealed — the lock is never
 * duplicated onto the profile doc, and the only edit path afterwards is an audited ADMIN unseal
 * (which is itself impossible once Stage 2 has consumed the manifest — §6.3, the permanence is the
 * point).
 */
@Service
class SubjectProfileService(
    private val config: StageConfigService,
    private val profiles: SubjectProfileRepository,
    private val subjects: SubjectRepository,
    private val manifests: IntakeManifestRepository,
) {

    fun view(subjectId: String): Either<DomainError, SubjectProfileView> {
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        return viewOf(subjectId, profiles.findBySubject(subjectId)).right()
    }

    fun put(
        subjectId: String,
        request: SubjectProfileUpdateRequest,
        actor: String?,
    ): Either<DomainError, SubjectProfileView> {
        if (!surfaceEnabled()) {
            return DomainError.Conflict(
                    "The subject profile is disabled (app.stage4.profile-enabled)"
                )
                .left()
        }
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        if (isSealed(subjectId)) {
            return DomainError.Conflict(
                    "The intake for subject $subjectId is sealed — the profile is frozen with the " +
                        "corpus. An ADMIN must unseal the manifest to edit it."
                )
                .left()
        }

        val errors = mutableListOf<String>()
        fun <T : Any> parse(raw: String?, label: String, parser: (String) -> T?): T? {
            val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val parsed = runCatching { parser(value) }.getOrNull()
            if (parsed == null) errors += "$label: '$value'"
            return parsed
        }

        val stored =
            SubjectProfile(
                subjectId = subjectId,
                country = parse(request.country, "country", ::country),
                marketRegion = parse(request.marketRegion, "marketRegion", ::marketRegion),
                currency = parse(request.currency, "currency", ::currency),
                timezone = parse(request.timezone, "timezone", ::timezone),
                primaryLanguage = parse(request.primaryLanguage, "primaryLanguage", ::language),
                knowledgeAsOf = parse(request.knowledgeAsOf, "knowledgeAsOf", LocalDate::parse),
            )
        if (errors.isNotEmpty()) {
            return DomainError.Invalid("Invalid profile values — " + errors.joinToString("; "))
                .left()
        }

        val toSave =
            stored.copy(
                profileHash = SubjectProfileDefaults.resolve(stored).hashOrNull(),
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        profiles.save(toSave)
        return viewOf(subjectId, toSave).right()
    }

    /**
     * The materialized profile a generation run freezes at submit (§4.3) — blank when nothing is
     * stored **and** blank while the feature flag is off, so a run started with the surface
     * disabled injects nothing and stamps no profileHash (byte-for-byte legacy).
     */
    fun resolved(subjectId: String): ResolvedSubjectProfile =
        // The read is skipped outright while the surface is off: nothing it could return is used.
        if (!surfaceEnabled()) ResolvedSubjectProfile()
        else SubjectProfileDefaults.resolve(profiles.findBySubject(subjectId))

    /**
     * Is the profile surface switched on at all (`app.stage4.enabled` ∧
     * `app.stage4.profile-enabled`, §8)? Config-only and Firestore-free, so a nav fragment may ask
     * on every render: the flag decides whether a "Your details" entry point exists, and an ungated
     * one is a permanent dead link because [put] refuses and the page redirects away.
     */
    fun surfaceEnabled(): Boolean = config.stage4().let { it.enabled && it.profileEnabled }

    private fun isSealed(subjectId: String): Boolean =
        manifests.findBySubject(subjectId)?.sealed == true

    private fun viewOf(subjectId: String, stored: SubjectProfile?): SubjectProfileView {
        // Same gate as [resolved], for the same reason: `resolved`/`profileHash` are a report of
        // what generation would use, and with the surface off generation uses neither. `stored`
        // still carries the answers — the doc is inert, not lost.
        val resolved =
            if (!surfaceEnabled()) ResolvedSubjectProfile()
            else SubjectProfileDefaults.resolve(stored)
        return SubjectProfileView(
            subjectId = subjectId,
            stored = stored,
            resolved = resolved,
            profileHash = resolved.hashOrNull(),
            sealed = isSealed(subjectId),
            enabled = surfaceEnabled(),
        )
    }

    // ---- field validators (blank is always legal — these only run on a non-blank value) ----

    /** ISO-3166 alpha-2, normalized upper — the code, not a country name. */
    private fun country(raw: String): String? =
        raw.uppercase().takeIf { it.length == 2 && it.all(Char::isLetter) && it in ISO_COUNTRIES }

    /**
     * The one A field with no external table behind it (§2.1: "free label, e.g. IN, EU, US-West") —
     * and the one whose text reaches a model verbatim, because it outranks [country] in
     * `ResolvedSubjectProfile.locale` (`domain/SubjectProfile.kt:77-84`) and that label is
     * substituted into `{{locale}}` in every generation prompt
     * (`stage4/Stage4Generation.kt:170-172`) and hashed into `profileHash`.
     *
     * So "free" is bounded here rather than trusted: one short line of letters, digits and light
     * punctuation. Anything longer, or carrying a line break or a `{{token}}`/tag character, is a
     * *sentence* — and a sentence in this field is unreviewed prompt text, whichever surface sent
     * it. The subject form does not offer the field at all (`SubjectTrainingController.saveProfile`
     * re-reads it from storage); this is the chokepoint that holds for every other caller.
     */
    private fun marketRegion(raw: String): String? =
        raw.takeIf { it.length <= MARKET_REGION_MAX && MARKET_REGION.matches(it) }

    /** ISO-4217, normalized upper. */
    private fun currency(raw: String): String? =
        raw.uppercase().takeIf { code -> runCatching { Currency.getInstance(code) }.isSuccess }

    /** IANA zone id, stored exactly as the zone database spells it. */
    private fun timezone(raw: String): String? = raw.takeIf { it in ZoneId.getAvailableZoneIds() }

    /** BCP-47 language tag; `Locale.forLanguageTag` yields an undetermined tag on garbage. */
    private fun language(raw: String): String? =
        raw.takeIf { Locale.forLanguageTag(it).language.isNotBlank() }

    private companion object {
        val ISO_COUNTRIES: Set<String> = Locale.getISOCountries().toSet()

        /** Long enough for "Asia-Pacific (APAC)", far too short for an instruction. */
        const val MARKET_REGION_MAX = 40

        /**
         * A single-line label: opens on a letter or digit, then letters/digits/space and the
         * punctuation real market names use. Braces, angle brackets, colons and newlines are
         * outside the class on purpose — they are the shapes prompt text is made of.
         */
        val MARKET_REGION = Regex("""[\p{L}\p{N}][\p{L}\p{N} .,'&()/+-]*""")
    }
}

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
    val resolved: ResolvedSubjectProfile,
    /** Hash of [resolved] — what a generation run started now would freeze; null when blank. */
    val profileHash: String?,
    /**
     * Live manifest seal state: true ⇒ the profile is frozen and [SubjectProfileService.put]
     * refuses (the edit path is an audited ADMIN unseal, §6.2).
     */
    val sealed: Boolean,
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
        if (!enabled()) {
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
                marketRegion = request.marketRegion?.trim()?.takeIf { it.isNotBlank() },
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
        if (!enabled()) ResolvedSubjectProfile()
        else SubjectProfileDefaults.resolve(profiles.findBySubject(subjectId))

    private fun enabled(): Boolean = config.stage4().let { it.enabled && it.profileEnabled }

    private fun isSealed(subjectId: String): Boolean =
        manifests.findBySubject(subjectId)?.sealed == true

    private fun viewOf(subjectId: String, stored: SubjectProfile?): SubjectProfileView {
        val resolved = SubjectProfileDefaults.resolve(stored)
        return SubjectProfileView(
            subjectId = subjectId,
            stored = stored,
            resolved = resolved,
            profileHash = resolved.hashOrNull(),
            sealed = isSealed(subjectId),
        )
    }

    // ---- field validators (blank is always legal — these only run on a non-blank value) ----

    /** ISO-3166 alpha-2, normalized upper — the code, not a country name. */
    private fun country(raw: String): String? =
        raw.uppercase().takeIf { it.length == 2 && it.all(Char::isLetter) && it in ISO_COUNTRIES }

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
    }
}

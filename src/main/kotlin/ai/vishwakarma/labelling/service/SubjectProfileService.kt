package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ContactField
import ai.vishwakarma.labelling.domain.ContactKind
import ai.vishwakarma.labelling.domain.DoNotDiscussApproval
import ai.vishwakarma.labelling.domain.DoNotDiscussCustom
import ai.vishwakarma.labelling.domain.DoNotDiscussVocabulary
import ai.vishwakarma.labelling.domain.EmploymentType
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

/**
 * `PUT /api/subjects/{id}/profile` body — raw answers; null/blank A/B = not declared (§7).
 *
 * **Two different null meanings, on purpose.** [put] rewrites the whole document, so A/B nulls
 * *clear* their fields — the A/B forms always submit every box, and clearing one has to work. The
 * C/D fields instead read null as **"this surface does not offer the field; keep what is stored"**,
 * with an explicit empty list/string meaning "clear it". Without that, the subject's A/B-only
 * training form (session 02, which predates C/D and has no inputs for them) would silently wipe the
 * subject's declared aspirations on every locale save — the §12.7.4 whole-document-rewrite hazard,
 * one slice later. The asymmetry is the safe direction: an omitted field can never destroy
 * evidence.
 */
data class SubjectProfileUpdateRequest(
    val country: String? = null,
    val marketRegion: String? = null,
    val currency: String? = null,
    val timezone: String? = null,
    val primaryLanguage: String? = null,
    /** ISO-8601 date text, e.g. "2026-07-15". */
    val knowledgeAsOf: String? = null,
    // ---- C. Candidacy / targeting — null = keep stored ----
    val targetRoles: List<String>? = null,
    val targetSeniority: String? = null,
    /** `FTE` / `CONTRACT` / `EITHER`; blank string clears. */
    val employmentType: String? = null,
    /**
     * `true` / `false`; null keeps stored, a present blank string clears — the tri-state the form's
     * "Prefer not to say"/"—" option needs. A nullable Boolean could not carry that third state
     * (null had to mean both "absent, keep" and "cleared"), so this rides the wire as text and is
     * parsed in [SubjectProfileService.put], exactly like [employmentType].
     */
    val openToRelocation: String? = null,
    // ---- D. Declared narrative — null = keep stored ----
    val aspirations: List<String>? = null,
    val statedPreferences: List<String>? = null,
    /** Curated [DoNotDiscussVocabulary] keys; an unrecognised key is rejected, never dropped. */
    val doNotDiscussChecks: List<String>? = null,
    /**
     * The subject's bespoke entry. Editing the text always resets approval to PENDING — approval is
     * a decision about a specific string, so it can never survive that string being replaced. Blank
     * clears the entry entirely. Admins approve/reject through [decideDoNotDiscussCustom], never
     * here.
     */
    val doNotDiscussCustom: String? = null,
    /**
     * E — the declared contact fields (§5). Null = the surface did not offer them (keep stored); a
     * present list is the whole contact intent, blank-valued entries dropped. Both form surfaces
     * render every kind, so they always pass a (possibly empty) list — clearing a field's box
     * clears the contact, exactly like the C/D lists.
     */
    val contact: List<ContactFieldInput>? = null,
)

/**
 * One contact detail as a surface posts it (§7.1/§5.3): the [kind] name, the raw [value], and the
 * per-field [shareable] opt-in the "private by default" radio captures. Validated and parsed into a
 * [ai.vishwakarma.labelling.domain.ContactField] by [SubjectProfileService.put] — a bad kind or a
 * value carrying prompt-injection shapes fails the whole save, never reaches the ledger.
 */
data class ContactFieldInput(
    val kind: String,
    val value: String? = null,
    val shareable: Boolean = false,
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
    private val declaredClaims: ProfileClaimMaterialiser,
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

        val previous = profiles.findBySubject(subjectId)
        val unknownChecks =
            request.doNotDiscussChecks
                .orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && !DoNotDiscussVocabulary.contains(it) }
        if (unknownChecks.isNotEmpty()) {
            errors += "doNotDiscussChecks: " + unknownChecks.distinct().joinToString(", ")
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
                // C/D: absent ⇒ keep what is stored (see the request KDoc).
                targetRoles =
                    request.targetRoles?.let { declaredList(it, "targetRoles", errors) }
                        ?: previous?.targetRoles.orEmpty(),
                // The three C/D scalars share one shape, so "keep" and "clear" never collapse: a
                // *null* request field is one the surface did not offer — keep what is stored; a
                // *present-but-blank* one is the form's empty "Prefer not to say"/"—" option, a
                // deliberate withdrawal that clears the stance (§7 request KDoc). Reading a blank
                // as
                // "keep" is the bug this shape fixes — a subject who picks "Prefer not to say" must
                // be able to retract a Yes/No/level, not silently re-assert it.
                targetSeniority =
                    if (request.targetSeniority == null) previous?.targetSeniority
                    else declaredLine(request.targetSeniority, "targetSeniority", errors),
                employmentType =
                    if (request.employmentType == null) previous?.employmentType
                    else parse(request.employmentType, "employmentType", EmploymentType::valueOf),
                openToRelocation =
                    if (request.openToRelocation == null) previous?.openToRelocation
                    else
                        parse(request.openToRelocation, "openToRelocation") {
                            it.toBooleanStrictOrNull()
                        },
                aspirations =
                    request.aspirations?.let { declaredList(it, "aspirations", errors) }
                        ?: previous?.aspirations.orEmpty(),
                statedPreferences =
                    request.statedPreferences?.let { declaredList(it, "statedPreferences", errors) }
                        ?: previous?.statedPreferences.orEmpty(),
                doNotDiscussChecks =
                    request.doNotDiscussChecks
                        ?.map { it.trim().lowercase() }
                        ?.filter { it.isNotBlank() } ?: previous?.doNotDiscussChecks.orEmpty(),
                doNotDiscussCustom =
                    customEntry(request.doNotDiscussCustom, previous?.doNotDiscussCustom, errors),
                // E: absent ⇒ keep stored; a present list is the whole contact intent (both forms
                // render every kind). Blank-valued rows are dropped, so clearing a box clears the
                // contact — and un-sharing removes the claim on the next seal (§5.2/§5.4).
                contact =
                    request.contact?.let { validateContacts(it, errors) }
                        ?: previous?.contact.orEmpty(),
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
     * The OD-9 admin decision on a pending bespoke do-not-discuss entry — approval is what lets
     * that string materialise into a claim, so it is a first-class audited action rather than a
     * field on [put] (which the *subject* can call).
     *
     * Deliberately **not** seal-gated: the entry is already frozen text; the admin is only
     * recording a verdict on it, and admin review naturally lands *after* the early seal, so a
     * verdict that could only be given before the seal would leave every post-seal pending entry
     * permanently inert with no way to say so.
     *
     * A decision only reaches Stage 3/4 through [ProfileClaimMaterialiser], which runs at the seal
     * ([IntakeService.sealManifest]). For a pre-seal approval the seal-time pass materialises it. A
     * **post-seal** approval, though, is invisible to that pass — it already ran, with the entry
     * still `PENDING` — so this method re-runs the materialiser itself while the corpus can still
     * take a claim (idempotent; reconciliation also retracts a boundary a later rejection
     * withdrew). Once Stage 2 has consumed the sealed manifest the corpus is frozen forever (§6.3),
     * and a claim written now could never join the evidence set: rather than flip the doc to
     * `APPROVED` and silently drop the boundary the subject asked for, the decision is refused with
     * a clear signal.
     */
    fun decideDoNotDiscussCustom(
        subjectId: String,
        approve: Boolean,
        actor: String?,
    ): Either<DomainError, SubjectProfileView> {
        if (!surfaceEnabled()) {
            return DomainError.Conflict(
                    "The subject profile is disabled (app.stage4.profile-enabled)"
                )
                .left()
        }
        val stored =
            profiles.findBySubject(subjectId)
                ?: return DomainError.NotFound("No profile for subject $subjectId").left()
        val custom =
            stored.doNotDiscussCustom
                ?: return DomainError.Invalid(
                        "Subject $subjectId has no custom do-not-discuss entry to decide"
                    )
                    .left()
        // Read the seal state once. A frozen intake (sealed, and Stage 2 has consumed it — the
        // unseal path is permanently closed) cannot accept a new declared claim, so a decision made
        // now can never reach the ledger. Refuse it here rather than record a misleading APPROVED.
        val manifest = manifests.findBySubject(subjectId)
        if (manifest?.sealed == true && manifest.stage2StartedAt != null) {
            return DomainError.Conflict(
                    "The intake for subject $subjectId is frozen — Stage 2 has consumed the sealed " +
                        "corpus, so this do-not-discuss decision can no longer reach the evidence " +
                        "set. A fresh intake is required to change the declared boundaries."
                )
                .left()
        }
        val decided =
            stored.copy(
                doNotDiscussCustom =
                    custom.copy(
                        state =
                            if (approve) DoNotDiscussApproval.APPROVED
                            else DoNotDiscussApproval.REJECTED,
                        approver = actor,
                        at = Instant.now(),
                    ),
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        profiles.save(decided)
        // Post-seal decision: the seal-time materialiser has already run and will not run again
        // unless someone re-seals, so re-run it now to land the approval (or retract a rejected
        // boundary). Pre-seal, the eventual seal does it; the guard above proved the corpus is not
        // yet frozen, so this is the same write the seal would have made.
        if (manifest?.sealed == true) declaredClaims.materialise(subjectId)
        return viewOf(subjectId, decided).right()
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

    // ---- C/D validators ---------------------------------------------------------------------

    /**
     * One declared line — an aspiration, a target role, a bespoke boundary. Blank ⇒ null (cleared).
     *
     * Bounded the way `marketRegion` is (§12.7.4), and for a *stronger* reason: this text is spoken
     * by the advocate about a real, named person, so it must read as one sentence a human typed
     * about himself. Over [DECLARED_LINE_MAX], or carrying a newline or a `{{token}}`/tag
     * character, it is unreviewed prompt text wearing an aspiration's clothes — refused at the
     * write, whichever surface sent it, rather than discovered in a generated notebook.
     */
    private fun declaredLine(raw: String, label: String, errors: MutableList<String>): String? {
        val value = raw.trim().takeIf { it.isNotBlank() } ?: return null
        if (value.length > DECLARED_LINE_MAX || !DECLARED_LINE.matches(value)) {
            errors += "$label: '${value.take(60)}'"
            return null
        }
        return value
    }

    /** A declared list: each entry validated as a line, blanks dropped, length capped. */
    private fun declaredList(
        raw: List<String>,
        label: String,
        errors: MutableList<String>,
    ): List<String> {
        if (raw.size > DECLARED_LIST_MAX) {
            errors += "$label: ${raw.size} entries (max $DECLARED_LIST_MAX)"
            return emptyList()
        }
        return raw.mapNotNull { declaredLine(it, label, errors) }
    }

    /**
     * The declared contact list (§5). Each input's kind must be a known [ContactKind] and its value
     * a single line of contact punctuation — a blank value is a **cleared** field (dropped), a
     * malformed kind or a value carrying a newline / brace / tag character is a hard error, exactly
     * like the C/D lines. The value is carried verbatim into a Row-8 claim, so it is bounded the
     * same way [declaredLine] is, plus the `@ _ ~ = # %` an email / URL / handle needs. `shareable`
     * rides straight through — it is the subject's own per-field O7 opt-in (§5.3).
     */
    private fun validateContacts(
        raw: List<ContactFieldInput>,
        errors: MutableList<String>,
    ): List<ContactField> =
        raw.mapNotNull { input ->
            val value = input.value?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = ContactKind.fromOrNull(input.kind)
            if (kind == null) {
                errors += "contact.kind: '${input.kind.take(40)}'"
                return@mapNotNull null
            }
            if (value.length > CONTACT_VALUE_MAX || !CONTACT_VALUE.matches(value)) {
                errors += "contact.${kind.name.lowercase()}: '${value.take(60)}'"
                return@mapNotNull null
            }
            ContactField(kind = kind, value = value, shareable = input.shareable)
        }

    /**
     * Resolve the bespoke do-not-discuss entry against what is stored. Null request ⇒ keep; blank ⇒
     * clear; unchanged text ⇒ keep the existing approval; **changed text ⇒ back to PENDING**, since
     * an approval is a verdict on one specific string and must never ride along to another.
     */
    private fun customEntry(
        raw: String?,
        previous: DoNotDiscussCustom?,
        errors: MutableList<String>,
    ): DoNotDiscussCustom? {
        if (raw == null) return previous
        val text = declaredLine(raw, "doNotDiscussCustom", errors) ?: return null
        return if (text == previous?.text) previous else DoNotDiscussCustom(text = text)
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

        /** One self-description, not a paragraph — comfortably longer than any real aspiration. */
        const val DECLARED_LINE_MAX = 200

        /** Enough for a real candidacy; far short of a bulk paste. */
        const val DECLARED_LIST_MAX = 12

        /**
         * A single line of ordinary prose: opens on a letter or digit, then letters/digits/space
         * and sentence punctuation. Braces, angle brackets and newlines stay outside the class —
         * those are the shapes prompt text and markup are made of, not the shapes a career goal is.
         */
        val DECLARED_LINE = Regex("""[\p{L}\p{N}][\p{L}\p{N} .,;:!?'"&()/+—–-]*""")

        /** One contact detail — an email / URL / handle / phone / name, comfortably long. */
        const val CONTACT_VALUE_MAX = 200

        /**
         * A single line of contact text: opens on a letter, digit or `+` (a phone's country code),
         * then the punctuation an email / URL / handle / phone actually uses — `@ _ ~ = # %` on top
         * of the [DECLARED_LINE] set. Braces, angle brackets, quotes and newlines stay out: the
         * value is spoken verbatim on Row 8, so it must never carry a `{{token}}`, a tag or a line
         * break (§5.2).
         */
        val CONTACT_VALUE = Regex("""[\p{L}\p{N}+][\p{L}\p{N} .,:;'&()/+@_~=#%?!-]*""")
    }
}

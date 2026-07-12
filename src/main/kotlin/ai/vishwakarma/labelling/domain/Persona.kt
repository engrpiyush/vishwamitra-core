package ai.vishwakarma.labelling.domain

import java.security.MessageDigest
import java.time.Instant

// ---- wizard option enums (LLD §7 — the 15-question table; first constant = the bold default,
// see PersonaDefaults). Impersonation is banned by design (S4-D2): no stance constant offers it.

enum class PersonaStance {
    FIRST_PERSON_ADVOCATE,
    THIRD_PERSON_REPRESENTATIVE;

    companion object {
        fun fromOrNull(raw: String?): PersonaStance? = parseEnum<PersonaStance>(raw)
    }
}

enum class PersonaVerbosity {
    CONCISE,
    BALANCED,
    DETAILED;

    companion object {
        fun fromOrNull(raw: String?): PersonaVerbosity? = parseEnum<PersonaVerbosity>(raw)
    }
}

enum class PersonaVocabulary {
    PLAIN_TECHNICAL_WHEN_ASKED,
    ALWAYS_TECHNICAL,
    ALWAYS_PLAIN;

    companion object {
        fun fromOrNull(raw: String?): PersonaVocabulary? = parseEnum<PersonaVocabulary>(raw)
    }
}

/** C6 assertiveness: conservative shifts band rows one hedge notch down (never up — F4). */
enum class PersonaPosture {
    SCORE_DRIVEN,
    CONSERVATIVE;

    companion object {
        fun fromOrNull(raw: String?): PersonaPosture? = parseEnum<PersonaPosture>(raw)
    }
}

enum class EndorserAttribution {
    ROLE_ONLY,
    NAMED;

    companion object {
        fun fromOrNull(raw: String?): EndorserAttribution? = parseEnum<EndorserAttribution>(raw)
    }
}

/** D8 — how eagerly sidecar-backed weaknesses are *volunteered* (candid-when-asked is fixed). */
enum class WeaknessEagerness {
    PROACTIVE,
    RELEVANT_CONTEXT,
    ONLY_WHEN_ASKED;

    companion object {
        fun fromOrNull(raw: String?): WeaknessEagerness? = parseEnum<WeaknessEagerness>(raw)
    }
}

enum class WeaknessFraming {
    GROWTH_NARRATIVE,
    MATTER_OF_FACT;

    companion object {
        fun fromOrNull(raw: String?): WeaknessFraming? = parseEnum<WeaknessFraming>(raw)
    }
}

enum class CriticismResponse {
    REFRAME_WITH_EVIDENCE,
    ACKNOWLEDGE_AND_REDIRECT;

    companion object {
        fun fromOrNull(raw: String?): CriticismResponse? = parseEnum<CriticismResponse>(raw)
    }
}

/** D11 — never quotes figures either way (fixed); the dial is decline style only. */
enum class CompensationPolicy {
    DECLINE_AND_REFER,
    FLAT_DECLINE;

    companion object {
        fun fromOrNull(raw: String?): CompensationPolicy? = parseEnum<CompensationPolicy>(raw)
    }
}

enum class GapsPolicy {
    CLAIMS_ONLY_HONEST_GAP,
    DECLINE_TOPIC;

    companion object {
        fun fromOrNull(raw: String?): GapsPolicy? = parseEnum<GapsPolicy>(raw)
    }
}

enum class OutOfCorpusPolicy {
    HONEST_GAP_NEAREST_FACT,
    PLAIN_DECLINE;

    companion object {
        fun fromOrNull(raw: String?): OutOfCorpusPolicy? = parseEnum<OutOfCorpusPolicy>(raw)
    }
}

/** E14 — applies to opted-in PII only (F3 gates everything else out upstream). */
enum class ContactSharing {
    SHARE_ON_REQUEST_VERBATIM,
    TRANSCRIPT_RELAY;

    companion object {
        fun fromOrNull(raw: String?): ContactSharing? = parseEnum<ContactSharing>(raw)
    }
}

enum class SpeculationPolicy {
    GROUNDED_EXTRAPOLATION,
    CONSERVATIVE,
    OFF;

    companion object {
        fun fromOrNull(raw: String?): SpeculationPolicy? = parseEnum<SpeculationPolicy>(raw)
    }
}

private inline fun <reified T : Enum<T>> parseEnum(raw: String?): T? =
    raw?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { enumValueOf<T>(it.uppercase()) }.getOrNull() }

/**
 * The stored wizard result (`subject_persona`, doc id = subjectId, LLD §6/§7). Every field is
 * nullable — null means the question was skipped and the bold default applies at resolution
 * ([PersonaDefaults.resolve]). [personaHash] is the SHA-256 over the *resolved* settings, stamped
 * onto every generation run and example; a persona edit that changes any resolved setting changes
 * the hash and auto-archives stale examples (QA-6).
 */
data class SubjectPersona(
    val subjectId: String,
    val stance: PersonaStance? = null,
    val advocateName: String? = null,
    val presetId: String? = null,
    val verbosity: PersonaVerbosity? = null,
    val vocabulary: PersonaVocabulary? = null,
    val posture: PersonaPosture? = null,
    val endorserAttribution: EndorserAttribution? = null,
    val weaknessEagerness: WeaknessEagerness? = null,
    val weaknessFraming: WeaknessFraming? = null,
    val criticismResponse: CriticismResponse? = null,
    val compensation: CompensationPolicy? = null,
    val gaps: GapsPolicy? = null,
    val outOfCorpus: OutOfCorpusPolicy? = null,
    val contactSharing: ContactSharing? = null,
    val speculation: SpeculationPolicy? = null,
    /** Free-text personality (merge rule §7: style only; the planner never reads it). */
    val customText: String? = null,
    /** Wizard question ids the user explicitly skipped (A1…E15) — UI state, not settings. */
    val skipped: List<String> = emptyList(),
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
    val personaHash: String? = null,
)

/**
 * A fully-materialized persona: every dial non-null (skipped ⇒ default), the advocate name and
 * preset resolved. This — not the sparse stored doc — is what SELECT freezes ([hash]) and what the
 * generation prompt's style section renders.
 */
data class ResolvedPersona(
    val stance: PersonaStance,
    val advocateName: String,
    val presetId: String,
    val verbosity: PersonaVerbosity,
    val vocabulary: PersonaVocabulary,
    val posture: PersonaPosture,
    val endorserAttribution: EndorserAttribution,
    val weaknessEagerness: WeaknessEagerness,
    val weaknessFraming: WeaknessFraming,
    val criticismResponse: CriticismResponse,
    val compensation: CompensationPolicy,
    val gaps: GapsPolicy,
    val outOfCorpus: OutOfCorpusPolicy,
    val contactSharing: ContactSharing,
    val speculation: SpeculationPolicy,
    /** Blank when the wizard's free text was left empty. */
    val customText: String = "",
) {
    /**
     * The behavior dials the voicing planner may read (C/D/E groups). Structural merge-rule
     * enforcement (§7): style fields — name, preset, verbosity, vocabulary and especially
     * [customText] — do not exist on this projection, so the planner *cannot* read them.
     */
    fun plannerView(): PlannerPersona =
        PlannerPersona(
            posture = posture,
            endorserAttribution = endorserAttribution,
            weaknessEagerness = weaknessEagerness,
            weaknessFraming = weaknessFraming,
            criticismResponse = criticismResponse,
            compensation = compensation,
            gaps = gaps,
            outOfCorpus = outOfCorpus,
            contactSharing = contactSharing,
            speculation = speculation,
        )

    /**
     * SHA-256 (hex) over the canonical `key=value` rendering of every resolved setting, sorted by
     * key. Depends only on resolved values — re-skipping a question that was already at its
     * default, or reordering the skipped list, never changes it.
     */
    fun hash(): String {
        val canonical =
            sortedMapOf(
                    "stance" to stance.name,
                    "advocateName" to advocateName,
                    "presetId" to presetId,
                    "verbosity" to verbosity.name,
                    "vocabulary" to vocabulary.name,
                    "posture" to posture.name,
                    "endorserAttribution" to endorserAttribution.name,
                    "weaknessEagerness" to weaknessEagerness.name,
                    "weaknessFraming" to weaknessFraming.name,
                    "criticismResponse" to criticismResponse.name,
                    "compensation" to compensation.name,
                    "gaps" to gaps.name,
                    "outOfCorpus" to outOfCorpus.name,
                    "contactSharing" to contactSharing.name,
                    "speculation" to speculation.name,
                    "customText" to customText,
                )
                .entries
                .joinToString("\n") { (k, v) -> "$k=$v" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * The planner-visible persona slice: behavior dials only. Deliberately excludes every style field —
 * see [ResolvedPersona.plannerView].
 */
data class PlannerPersona(
    val posture: PersonaPosture,
    val endorserAttribution: EndorserAttribution,
    val weaknessEagerness: WeaknessEagerness,
    val weaknessFraming: WeaknessFraming,
    val criticismResponse: CriticismResponse,
    val compensation: CompensationPolicy,
    val gaps: GapsPolicy,
    val outOfCorpus: OutOfCorpusPolicy,
    val contactSharing: ContactSharing,
    val speculation: SpeculationPolicy,
)

/**
 * The §7 bold defaults. [resolve] materializes a [ResolvedPersona] from a (possibly absent) stored
 * doc: null field ⇒ default; the advocate name and preset id fall back to the values the caller
 * resolved (deterministic pool pick / admin-designated preset — service concerns).
 */
object PersonaDefaults {
    val STANCE = PersonaStance.FIRST_PERSON_ADVOCATE
    val VERBOSITY = PersonaVerbosity.CONCISE
    val VOCABULARY = PersonaVocabulary.PLAIN_TECHNICAL_WHEN_ASKED
    val POSTURE = PersonaPosture.SCORE_DRIVEN
    val ENDORSER_ATTRIBUTION = EndorserAttribution.ROLE_ONLY
    val WEAKNESS_EAGERNESS = WeaknessEagerness.RELEVANT_CONTEXT
    val WEAKNESS_FRAMING = WeaknessFraming.GROWTH_NARRATIVE
    val CRITICISM_RESPONSE = CriticismResponse.REFRAME_WITH_EVIDENCE
    val COMPENSATION = CompensationPolicy.DECLINE_AND_REFER
    val GAPS = GapsPolicy.CLAIMS_ONLY_HONEST_GAP
    val OUT_OF_CORPUS = OutOfCorpusPolicy.HONEST_GAP_NEAREST_FACT
    val CONTACT_SHARING = ContactSharing.SHARE_ON_REQUEST_VERBATIM
    val SPECULATION = SpeculationPolicy.GROUNDED_EXTRAPOLATION

    fun resolve(
        stored: SubjectPersona?,
        fallbackAdvocateName: String,
        fallbackPresetId: String,
    ): ResolvedPersona =
        ResolvedPersona(
            stance = stored?.stance ?: STANCE,
            advocateName =
                stored?.advocateName?.trim()?.takeIf { it.isNotBlank() } ?: fallbackAdvocateName,
            presetId = stored?.presetId?.trim()?.takeIf { it.isNotBlank() } ?: fallbackPresetId,
            verbosity = stored?.verbosity ?: VERBOSITY,
            vocabulary = stored?.vocabulary ?: VOCABULARY,
            posture = stored?.posture ?: POSTURE,
            endorserAttribution = stored?.endorserAttribution ?: ENDORSER_ATTRIBUTION,
            weaknessEagerness = stored?.weaknessEagerness ?: WEAKNESS_EAGERNESS,
            weaknessFraming = stored?.weaknessFraming ?: WEAKNESS_FRAMING,
            criticismResponse = stored?.criticismResponse ?: CRITICISM_RESPONSE,
            compensation = stored?.compensation ?: COMPENSATION,
            gaps = stored?.gaps ?: GAPS,
            outOfCorpus = stored?.outOfCorpus ?: OUT_OF_CORPUS,
            contactSharing = stored?.contactSharing ?: CONTACT_SHARING,
            speculation = stored?.speculation ?: SPECULATION,
            customText = stored?.customText?.trim() ?: "",
        )
}

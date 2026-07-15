package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.CompensationPolicy
import ai.vishwakarma.labelling.domain.ContactSharing
import ai.vishwakarma.labelling.domain.CriticismResponse
import ai.vishwakarma.labelling.domain.EndorserAttribution
import ai.vishwakarma.labelling.domain.GapsPolicy
import ai.vishwakarma.labelling.domain.OutOfCorpusPolicy
import ai.vishwakarma.labelling.domain.PersonaDefaults
import ai.vishwakarma.labelling.domain.PersonaPosture
import ai.vishwakarma.labelling.domain.PersonaStance
import ai.vishwakarma.labelling.domain.PersonaVerbosity
import ai.vishwakarma.labelling.domain.PersonaVocabulary
import ai.vishwakarma.labelling.domain.ResolvedPersona
import ai.vishwakarma.labelling.domain.SpeculationPolicy
import ai.vishwakarma.labelling.domain.SubjectPersona
import ai.vishwakarma.labelling.domain.WeaknessEagerness
import ai.vishwakarma.labelling.domain.WeaknessFraming
import ai.vishwakarma.labelling.persistence.AdvocateNameRepository
import ai.vishwakarma.labelling.persistence.SubjectPersonaRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import org.springframework.stereotype.Service

/** `PUT /api/subjects/{id}/persona` body — raw wizard answers; null/blank = skipped (§7). */
data class PersonaUpdateRequest(
    val stance: String? = null,
    val advocateName: String? = null,
    val presetId: String? = null,
    val verbosity: String? = null,
    val vocabulary: String? = null,
    val posture: String? = null,
    val endorserAttribution: String? = null,
    val weaknessEagerness: String? = null,
    val weaknessFraming: String? = null,
    val criticismResponse: String? = null,
    val compensation: String? = null,
    val gaps: String? = null,
    val outOfCorpus: String? = null,
    val contactSharing: String? = null,
    val speculation: String? = null,
    val customText: String? = null,
    val skipped: List<String> = emptyList(),
)

data class PersonaPresetView(val id: String, val default: Boolean)

/** What the wizard page reads: the sparse stored doc beside its full materialization. */
data class PersonaView(
    val subjectId: String,
    val stored: SubjectPersona?,
    val resolved: ResolvedPersona,
    /** Hash of [resolved] — what a generation run started now would freeze (QA-6). */
    val personaHash: String,
    /** The deterministic A2 fallback for this subject (shown as "auto: <name>"). */
    val autoAssignedName: String,
    val presets: List<PersonaPresetView>,
)

/**
 * The persona wizard's backend (LLD §7): stores the sparse wizard result, materializes the resolved
 * (post-default) persona, and computes the personaHash that pins generation runs. A subject with no
 * stored doc still resolves — the defaults-only persona the SELECT guard materializes (§9.1).
 */
@Service
class PersonaService(
    private val config: StageConfigService,
    private val personas: SubjectPersonaRepository,
    private val subjects: SubjectRepository,
    private val names: AdvocateNameRepository,
    private val prompts: ExtractionPromptService,
) {

    fun view(subjectId: String): Either<DomainError, PersonaView> {
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        return viewOf(subjectId, personas.findBySubject(subjectId)).right()
    }

    fun put(
        subjectId: String,
        request: PersonaUpdateRequest,
        actor: String?,
    ): Either<DomainError, PersonaView> {
        if (!config.stage4().enabled) {
            return DomainError.Conflict("Stage 4 is disabled (app.stage4.enabled)").left()
        }
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()

        val errors = mutableListOf<String>()
        fun <T : Any> parse(raw: String?, label: String, parser: (String?) -> T?): T? {
            val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val parsed = parser(value)
            if (parsed == null) errors += "$label: '$value'"
            return parsed
        }

        val presetId = request.presetId?.trim()?.takeIf { it.isNotBlank() }
        if (presetId != null && presetId !in prompts.stage4PresetIds()) {
            errors += "presetId: '$presetId' (unknown preset)"
        }
        val advocateName = request.advocateName?.trim()?.takeIf { it.isNotBlank() }
        if (advocateName != null && names.findAll().none { it.name == advocateName }) {
            errors += "advocateName: '$advocateName' (not in the admin pool)"
        }

        val stored =
            SubjectPersona(
                subjectId = subjectId,
                stance = parse(request.stance, "stance", { raw -> PersonaStance.fromOrNull(raw) }),
                advocateName = advocateName,
                presetId = presetId,
                verbosity =
                    parse(
                        request.verbosity,
                        "verbosity",
                        { raw -> PersonaVerbosity.fromOrNull(raw) }
                    ),
                vocabulary =
                    parse(
                        request.vocabulary,
                        "vocabulary",
                        { raw -> PersonaVocabulary.fromOrNull(raw) }
                    ),
                posture =
                    parse(request.posture, "posture", { raw -> PersonaPosture.fromOrNull(raw) }),
                endorserAttribution =
                    parse(
                        request.endorserAttribution,
                        "endorserAttribution",
                        { raw -> EndorserAttribution.fromOrNull(raw) },
                    ),
                weaknessEagerness =
                    parse(
                        request.weaknessEagerness,
                        "weaknessEagerness",
                        { raw -> WeaknessEagerness.fromOrNull(raw) },
                    ),
                weaknessFraming =
                    parse(
                        request.weaknessFraming,
                        "weaknessFraming",
                        { raw -> WeaknessFraming.fromOrNull(raw) }
                    ),
                criticismResponse =
                    parse(
                        request.criticismResponse,
                        "criticismResponse",
                        { raw -> CriticismResponse.fromOrNull(raw) },
                    ),
                compensation =
                    parse(
                        request.compensation,
                        "compensation",
                        { raw -> CompensationPolicy.fromOrNull(raw) }
                    ),
                gaps = parse(request.gaps, "gaps", { raw -> GapsPolicy.fromOrNull(raw) }),
                outOfCorpus =
                    parse(
                        request.outOfCorpus,
                        "outOfCorpus",
                        { raw -> OutOfCorpusPolicy.fromOrNull(raw) }
                    ),
                contactSharing =
                    parse(
                        request.contactSharing,
                        "contactSharing",
                        { raw -> ContactSharing.fromOrNull(raw) }
                    ),
                speculation =
                    parse(
                        request.speculation,
                        "speculation",
                        { raw -> SpeculationPolicy.fromOrNull(raw) }
                    ),
                customText = request.customText?.trim()?.takeIf { it.isNotBlank() },
                skipped = request.skipped.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            )
        if (errors.isNotEmpty()) {
            return DomainError.Invalid("Invalid persona values — " + errors.joinToString("; "))
                .left()
        }

        val toSave =
            stored.copy(
                personaHash = resolve(subjectId, stored).hash(),
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        personas.save(toSave)
        return viewOf(subjectId, toSave).right()
    }

    /**
     * The materialized persona a generation run freezes at SELECT (§9.1) — defaults-only when
     * nothing is stored.
     */
    fun resolved(subjectId: String): ResolvedPersona =
        resolve(subjectId, personas.findBySubject(subjectId))

    /**
     * The deterministic A2 fallback: `pool[hash(subjectId) % size]` over the stable-ordered admin
     * pool — same subject, same name, for as long as the pool is unchanged. SHA-256-based so the
     * index survives JVM restarts and never goes negative.
     */
    fun autoAssignedName(subjectId: String): String {
        val pool = names.findAll()
        if (pool.isEmpty()) return DEFAULT_ADVOCATE_NAME
        val digest =
            MessageDigest.getInstance("SHA-256").digest(subjectId.toByteArray(Charsets.UTF_8))
        val index = BigInteger(1, digest).mod(BigInteger.valueOf(pool.size.toLong())).toInt()
        return pool[index].name
    }

    private fun resolve(subjectId: String, stored: SubjectPersona?): ResolvedPersona =
        PersonaDefaults.resolve(
            stored = stored,
            fallbackAdvocateName = autoAssignedName(subjectId),
            fallbackPresetId = prompts.defaultStage4PresetId(),
        )

    private fun viewOf(subjectId: String, stored: SubjectPersona?): PersonaView {
        val resolved = resolve(subjectId, stored)
        val defaultPreset = prompts.defaultStage4PresetId()
        return PersonaView(
            subjectId = subjectId,
            stored = stored,
            resolved = resolved,
            personaHash = resolved.hash(),
            autoAssignedName = autoAssignedName(subjectId),
            presets = prompts.stage4PresetIds().map { PersonaPresetView(it, it == defaultPreset) },
        )
    }

    companion object {
        /** Last-resort A2 fallback when the admin pool is empty (pre-seed / wiped emulator). */
        const val DEFAULT_ADVOCATE_NAME = "Avery"
    }
}

package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.CompensationPolicy
import ai.vishwakarma.labelling.domain.ContactSharing
import ai.vishwakarma.labelling.domain.CriticismResponse
import ai.vishwakarma.labelling.domain.EndorserAttribution
import ai.vishwakarma.labelling.domain.GapsPolicy
import ai.vishwakarma.labelling.domain.OutOfCorpusPolicy
import ai.vishwakarma.labelling.domain.PersonaPosture
import ai.vishwakarma.labelling.domain.PersonaStance
import ai.vishwakarma.labelling.domain.PersonaVerbosity
import ai.vishwakarma.labelling.domain.PersonaVocabulary
import ai.vishwakarma.labelling.domain.SpeculationPolicy
import ai.vishwakarma.labelling.domain.SubjectPersona
import ai.vishwakarma.labelling.domain.WeaknessEagerness
import ai.vishwakarma.labelling.domain.WeaknessFraming
import ai.vishwakarma.labelling.persistence.AdvocateNameRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.PersonaService
import ai.vishwakarma.labelling.service.PersonaUpdateRequest
import ai.vishwakarma.labelling.service.Stage4Service
import ai.vishwakarma.labelling.service.SubjectService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

// ---- VA-63 wizard view models -------------------------------------------------------------------

/** One answer choice. The first option of every question is the skip/default choice (value ""). */
data class WizardOption(
    val value: String,
    val label: String,
    /** The §7 bold default — marked visually; the skip option names it too. */
    val default: Boolean,
    val selected: Boolean,
    /** Longer descriptive text under the label (preset style blocks, attribution examples). */
    val note: String? = null,
)

/** One §7 question, rendered as a radio group inside its step. */
data class WizardQuestion(
    val id: String,
    /** The `PUT /persona` field this question answers — also the form input name. */
    val field: String,
    val title: String,
    /** Constraint context under the title (fixed rules the dial rides on). */
    val help: String?,
    val options: List<WizardOption>,
    /** True when the stored doc has no answer — the skip/default option renders selected. */
    val skipped: Boolean,
)

data class WizardStep(val index: Int, val label: String, val questions: List<WizardQuestion>)

/**
 * The Stage 4 persona wizard (LLD §7, VA-63): `/intake/{id}/persona` — a stepper over the fixed
 * card + the 15 skippable questions + the free-text personality box. Server-rendered single form
 * (static/js/persona-wizard.js only moves between steps); one POST saves the whole sparse answer
 * set via [PersonaService.put], which validates, resolves defaults and stamps the persona hash.
 */
@Controller
@RequestMapping("/intake")
@PreAuthorize("hasRole('REVIEWER')")
class PersonaWizardController(
    private val subjectService: SubjectService,
    private val personaService: PersonaService,
    private val prompts: ExtractionPromptService,
    private val names: AdvocateNameRepository,
    private val stage4: Stage4Service,
) {

    @GetMapping("/{id}/persona")
    fun page(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val view =
            personaService
                .view(id)
                .fold(
                    {
                        ra.addFlashAttribute("error", it.message)
                        return "redirect:/intake/$id"
                    },
                    { it },
                )
        model.addAttribute("pageTitle", "Persona · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("view", view)
        model.addAttribute("stored", view.stored)
        model.addAttribute("resolved", view.resolved)
        model.addAttribute("steps", steps(view.stored, view.autoAssignedName))
        model.addAttribute("stepLabels", STEP_LABELS)
        model.addAttribute("customText", view.stored?.customText ?: "")
        // QA-6: a persona change after a generation run re-stamps the hash — the next SELECT
        // tick archives every example generated under the old one. Warn before saving.
        model.addAttribute("hasRuns", stage4.latestForSubject(id) != null)
        return "intake/persona"
    }

    /**
     * Save the whole wizard in one POST. Blank/absent answers are skips (§7: skip = the bold
     * default) — their question ids land on `skipped` so re-entry renders the skip state, and
     * [PersonaService.put] resolves the defaults + validates everything else.
     */
    @PostMapping("/{id}/persona")
    fun save(
        @PathVariable id: String,
        @RequestParam(required = false) stance: String?,
        @RequestParam(required = false) advocateName: String?,
        @RequestParam(required = false) presetId: String?,
        @RequestParam(required = false) verbosity: String?,
        @RequestParam(required = false) vocabulary: String?,
        @RequestParam(required = false) posture: String?,
        @RequestParam(required = false) endorserAttribution: String?,
        @RequestParam(required = false) weaknessEagerness: String?,
        @RequestParam(required = false) weaknessFraming: String?,
        @RequestParam(required = false) criticismResponse: String?,
        @RequestParam(required = false) compensation: String?,
        @RequestParam(required = false) gaps: String?,
        @RequestParam(required = false) outOfCorpus: String?,
        @RequestParam(required = false) contactSharing: String?,
        @RequestParam(required = false) speculation: String?,
        @RequestParam(required = false) customText: String?,
        ra: RedirectAttributes,
    ): String {
        val answers =
            mapOf(
                "A1" to stance,
                "A2" to advocateName,
                "B3" to presetId,
                "B4" to verbosity,
                "B5" to vocabulary,
                "C6" to posture,
                "C7" to endorserAttribution,
                "D8" to weaknessEagerness,
                "D9" to weaknessFraming,
                "D10" to criticismResponse,
                "D11" to compensation,
                "D12" to gaps,
                "E13" to outOfCorpus,
                "E14" to contactSharing,
                "E15" to speculation,
            )
        val request =
            PersonaUpdateRequest(
                stance = stance,
                advocateName = advocateName,
                presetId = presetId,
                verbosity = verbosity,
                vocabulary = vocabulary,
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
                customText = customText,
                skipped = answers.filterValues { it.isNullOrBlank() }.keys.toList(),
            )
        personaService
            .put(id, request, CurrentUser.email())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { saved ->
                    ra.addFlashAttribute(
                        "ok",
                        "Persona saved — advocate ${saved.resolved.advocateName}, " +
                            "hash ${saved.personaHash.take(12)}… (generation runs pin to it)",
                    )
                },
            )
        return "redirect:/intake/$id/persona"
    }

    // ---- §7 table → view models ------------------------------------------------------------

    private fun steps(stored: SubjectPersona?, autoName: String): List<WizardStep> {
        val defaultPreset = prompts.defaultStage4PresetId()
        return listOf(
            WizardStep(
                1,
                "Stance",
                listOf(
                    question(
                        id = "A1",
                        field = "stance",
                        title = "Speaking stance",
                        help =
                            "Impersonation is banned by design — the advocate never pretends " +
                                "to be the subject, only to speak for them.",
                        stored = stored?.stance?.name,
                        options =
                            listOf(
                                PersonaStance.FIRST_PERSON_ADVOCATE.name to
                                    "First-person advocate — “I speak for them: …”",
                                PersonaStance.THIRD_PERSON_REPRESENTATIVE.name to
                                    "Third-person representative — “They would say …”",
                            ),
                        defaultValue = PersonaStance.FIRST_PERSON_ADVOCATE.name,
                    ),
                    WizardQuestion(
                        id = "A2",
                        field = "advocateName",
                        title = "Advocate name",
                        help =
                            "Skipping auto-assigns deterministically from the admin pool — " +
                                "stable for this subject while the pool is unchanged.",
                        options =
                            listOf(
                                WizardOption(
                                    value = "",
                                    label = "Auto-assign from the pool — “$autoName”",
                                    default = true,
                                    selected = stored?.advocateName.isNullOrBlank(),
                                )
                            ) +
                                names.findAll().map { n ->
                                    WizardOption(
                                        value = n.name,
                                        label = n.name,
                                        default = false,
                                        selected = stored?.advocateName == n.name,
                                        note =
                                            listOfNotNull(n.region?.name, n.gender)
                                                .joinToString(" · ")
                                                .ifBlank { null },
                                    )
                                },
                        skipped = stored?.advocateName.isNullOrBlank(),
                    ),
                ),
            ),
            WizardStep(
                2,
                "Style",
                listOf(
                    WizardQuestion(
                        id = "B3",
                        field = "presetId",
                        title = "Personality preset",
                        help =
                            "Admin-configured style bundles (tone, formality, humor, emoji). " +
                                "The free text on the last step can refine the style further.",
                        options =
                            listOf(
                                WizardOption(
                                    value = "",
                                    label = "Use the admin default — “$defaultPreset”",
                                    default = true,
                                    selected = stored?.presetId.isNullOrBlank(),
                                )
                            ) +
                                prompts.stage4PresetIds().map { pid ->
                                    WizardOption(
                                        value = pid,
                                        label =
                                            pid +
                                                (if (pid == defaultPreset) " (admin default)"
                                                else ""),
                                        default = false,
                                        selected = stored?.presetId == pid,
                                        note =
                                            prompts.resolveStage4Preset(pid).instructions.takeIf {
                                                it.isNotBlank()
                                            },
                                    )
                                },
                        skipped = stored?.presetId.isNullOrBlank(),
                    ),
                    question(
                        id = "B4",
                        field = "verbosity",
                        title = "Answer length",
                        help = null,
                        stored = stored?.verbosity?.name,
                        options =
                            listOf(
                                PersonaVerbosity.CONCISE.name to "Concise",
                                PersonaVerbosity.BALANCED.name to "Balanced",
                                PersonaVerbosity.DETAILED.name to "Detailed",
                            ),
                        defaultValue = PersonaVerbosity.CONCISE.name,
                    ),
                    question(
                        id = "B5",
                        field = "vocabulary",
                        title = "Vocabulary",
                        help = null,
                        stored = stored?.vocabulary?.name,
                        options =
                            listOf(
                                PersonaVocabulary.PLAIN_TECHNICAL_WHEN_ASKED.name to
                                    "Plain, technical when asked",
                                PersonaVocabulary.ALWAYS_TECHNICAL.name to "Always technical",
                                PersonaVocabulary.ALWAYS_PLAIN.name to "Always plain",
                            ),
                        defaultValue = PersonaVocabulary.PLAIN_TECHNICAL_WHEN_ASKED.name,
                    ),
                ),
            ),
            WizardStep(
                3,
                "Confidence",
                listOf(
                    question(
                        id = "C6",
                        field = "posture",
                        title = "Assertiveness",
                        help =
                            "Nothing can voice above what the score authorizes (fixed rule F4) " +
                                "— conservative only shifts every band one hedge notch down.",
                        stored = stored?.posture?.name,
                        options =
                            listOf(
                                PersonaPosture.SCORE_DRIVEN.name to
                                    "Score-driven — voice follows the authenticity band",
                                PersonaPosture.CONSERVATIVE.name to
                                    "Conservative — all bands one notch more hedged",
                            ),
                        defaultValue = PersonaPosture.SCORE_DRIVEN.name,
                    ),
                    question(
                        id = "C7",
                        field = "endorserAttribution",
                        title = "Endorser attribution",
                        help = "How endorsement evidence is credited when voiced.",
                        stored = stored?.endorserAttribution?.name,
                        options =
                            listOf(
                                EndorserAttribution.ROLE_ONLY.name to
                                    "Role only — “a former manager attests …”",
                                EndorserAttribution.NAMED.name to
                                    "Named — “Priya N., former manager, attests …”",
                            ),
                        defaultValue = EndorserAttribution.ROLE_ONLY.name,
                    ),
                ),
            ),
            WizardStep(
                4,
                "Weaknesses",
                listOf(
                    question(
                        id = "D8",
                        field = "weaknessEagerness",
                        title = "Weakness volunteering",
                        help =
                            "Only sidecar-backed weaknesses are ever volunteered (S4-D1); " +
                                "candid-when-asked is fixed and not a dial.",
                        stored = stored?.weaknessEagerness?.name,
                        options =
                            listOf(
                                WeaknessEagerness.PROACTIVE.name to "Proactive",
                                WeaknessEagerness.RELEVANT_CONTEXT.name to "In relevant context",
                                WeaknessEagerness.ONLY_WHEN_ASKED.name to "Only when asked",
                            ),
                        defaultValue = WeaknessEagerness.RELEVANT_CONTEXT.name,
                    ),
                    question(
                        id = "D9",
                        field = "weaknessFraming",
                        title = "Weakness framing",
                        help = null,
                        stored = stored?.weaknessFraming?.name,
                        options =
                            listOf(
                                WeaknessFraming.GROWTH_NARRATIVE.name to
                                    "Growth narrative — what changed since",
                                WeaknessFraming.MATTER_OF_FACT.name to "Matter-of-fact",
                            ),
                        defaultValue = WeaknessFraming.GROWTH_NARRATIVE.name,
                    ),
                    question(
                        id = "D10",
                        field = "criticismResponse",
                        title = "Criticism response",
                        help = "Reframes cite ledger facts only — never invented counterpoints.",
                        stored = stored?.criticismResponse?.name,
                        options =
                            listOf(
                                CriticismResponse.REFRAME_WITH_EVIDENCE.name to
                                    "Reframe with evidence",
                                CriticismResponse.ACKNOWLEDGE_AND_REDIRECT.name to
                                    "Acknowledge and redirect",
                            ),
                        defaultValue = CriticismResponse.REFRAME_WITH_EVIDENCE.name,
                    ),
                    question(
                        id = "D11",
                        field = "compensation",
                        title = "Compensation questions",
                        help =
                            "Never quotes figures either way — fixed; the dial is the decline style.",
                        stored = stored?.compensation?.name,
                        options =
                            listOf(
                                CompensationPolicy.DECLINE_AND_REFER.name to
                                    "Decline + refer to the subject",
                                CompensationPolicy.FLAT_DECLINE.name to "Flat decline",
                            ),
                        defaultValue = CompensationPolicy.DECLINE_AND_REFER.name,
                    ),
                    question(
                        id = "D12",
                        field = "gaps",
                        title = "Gaps / reasons for leaving",
                        help = null,
                        stored = stored?.gaps?.name,
                        options =
                            listOf(
                                GapsPolicy.CLAIMS_ONLY_HONEST_GAP.name to
                                    "Claims only + honest gap",
                                GapsPolicy.DECLINE_TOPIC.name to "Decline the topic",
                            ),
                        defaultValue = GapsPolicy.CLAIMS_ONLY_HONEST_GAP.name,
                    ),
                ),
            ),
            WizardStep(
                5,
                "Boundaries",
                listOf(
                    question(
                        id = "E13",
                        field = "outOfCorpus",
                        title = "Out-of-corpus questions",
                        help = null,
                        stored = stored?.outOfCorpus?.name,
                        options =
                            listOf(
                                OutOfCorpusPolicy.HONEST_GAP_NEAREST_FACT.name to
                                    "Honest gap + nearest evidenced fact",
                                OutOfCorpusPolicy.PLAIN_DECLINE.name to "Plain decline",
                            ),
                        defaultValue = OutOfCorpusPolicy.HONEST_GAP_NEAREST_FACT.name,
                    ),
                    question(
                        id = "E14",
                        field = "contactSharing",
                        title = "Contact sharing",
                        help =
                            "Applies to opted-in PII only — everything else is gated out upstream (F3).",
                        stored = stored?.contactSharing?.name,
                        options =
                            listOf(
                                ContactSharing.SHARE_ON_REQUEST_VERBATIM.name to
                                    "Share on request, verbatim",
                                ContactSharing.TRANSCRIPT_RELAY.name to "Transcript relay",
                            ),
                        defaultValue = ContactSharing.SHARE_ON_REQUEST_VERBATIM.name,
                    ),
                    question(
                        id = "E15",
                        field = "speculation",
                        title = "Speculation",
                        help =
                            "Grounded extrapolation follows the §10 rules — floors, visible " +
                                "grounding, deterministic hedging.",
                        stored = stored?.speculation?.name,
                        options =
                            listOf(
                                SpeculationPolicy.GROUNDED_EXTRAPOLATION.name to
                                    "Grounded extrapolation",
                                SpeculationPolicy.CONSERVATIVE.name to "Conservative",
                                SpeculationPolicy.OFF.name to "Off",
                            ),
                        defaultValue = SpeculationPolicy.GROUNDED_EXTRAPOLATION.name,
                    ),
                ),
            ),
        )
    }

    /** Build a radio question: the skip/default option first, then the enum options. */
    private fun question(
        id: String,
        field: String,
        title: String,
        help: String?,
        stored: String?,
        options: List<Pair<String, String>>,
        defaultValue: String,
    ): WizardQuestion {
        val defaultLabel = options.first { it.first == defaultValue }.second
        return WizardQuestion(
            id = id,
            field = field,
            title = title,
            help = help,
            options =
                listOf(
                    WizardOption(
                        value = "",
                        label = "Skip — use the default: $defaultLabel",
                        default = true,
                        selected = stored == null,
                    )
                ) +
                    options.map { (value, label) ->
                        WizardOption(
                            value = value,
                            label = label + (if (value == defaultValue) " (default)" else ""),
                            default = value == defaultValue,
                            selected = stored == value,
                        )
                    },
            skipped = stored == null,
        )
    }

    companion object {
        /** Stepper rail labels: the fixed card, the five §7 groups, the free-text finish. */
        val STEP_LABELS =
            listOf("Card", "Stance", "Style", "Confidence", "Weaknesses", "Boundaries", "Finish")
    }
}

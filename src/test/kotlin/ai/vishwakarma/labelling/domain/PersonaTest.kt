package ai.vishwakarma.labelling.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** [PersonaDefaults] resolution + the personaHash contract (VA-50 acceptance pins). */
class PersonaTest {

    private fun resolveEmpty(): ResolvedPersona =
        PersonaDefaults.resolve(
            null,
            fallbackAdvocateName = "Avery",
            fallbackPresetId = "warm-storyteller"
        )

    @Test
    fun `an absent persona resolves to the §7 bold defaults`() {
        val resolved = resolveEmpty()

        assertEquals(PersonaStance.FIRST_PERSON_ADVOCATE, resolved.stance)
        assertEquals("Avery", resolved.advocateName)
        assertEquals("warm-storyteller", resolved.presetId)
        assertEquals(PersonaVerbosity.CONCISE, resolved.verbosity)
        assertEquals(PersonaVocabulary.PLAIN_TECHNICAL_WHEN_ASKED, resolved.vocabulary)
        assertEquals(PersonaPosture.SCORE_DRIVEN, resolved.posture)
        assertEquals(EndorserAttribution.ROLE_ONLY, resolved.endorserAttribution)
        assertEquals(WeaknessEagerness.RELEVANT_CONTEXT, resolved.weaknessEagerness)
        assertEquals(WeaknessFraming.GROWTH_NARRATIVE, resolved.weaknessFraming)
        assertEquals(CriticismResponse.REFRAME_WITH_EVIDENCE, resolved.criticismResponse)
        assertEquals(CompensationPolicy.DECLINE_AND_REFER, resolved.compensation)
        assertEquals(GapsPolicy.CLAIMS_ONLY_HONEST_GAP, resolved.gaps)
        assertEquals(OutOfCorpusPolicy.HONEST_GAP_NEAREST_FACT, resolved.outOfCorpus)
        assertEquals(ContactSharing.SHARE_ON_REQUEST_VERBATIM, resolved.contactSharing)
        assertEquals(SpeculationPolicy.GROUNDED_EXTRAPOLATION, resolved.speculation)
        assertEquals("", resolved.customText)
    }

    @Test
    fun `hash is stable and depends only on resolved settings`() {
        val fromNothing = resolveEmpty()
        // Explicitly answering every question with its default resolves identically…
        val fromExplicitDefaults =
            PersonaDefaults.resolve(
                SubjectPersona(
                    subjectId = "s1",
                    stance = PersonaStance.FIRST_PERSON_ADVOCATE,
                    verbosity = PersonaVerbosity.CONCISE,
                    posture = PersonaPosture.SCORE_DRIVEN,
                    skipped = listOf("B5", "C7", "D8"),
                ),
                fallbackAdvocateName = "Avery",
                fallbackPresetId = "warm-storyteller",
            )

        // …so the hash cannot depend on which questions were skipped or list order.
        assertEquals(fromNothing.hash(), fromNothing.hash())
        assertEquals(fromNothing.hash(), fromExplicitDefaults.hash())
    }

    @Test
    fun `hash changes iff a resolved setting changes`() {
        val base = resolveEmpty()

        assertNotEquals(base.hash(), base.copy(posture = PersonaPosture.CONSERVATIVE).hash())
        assertNotEquals(base.hash(), base.copy(advocateName = "Priya Nair").hash())
        assertNotEquals(base.hash(), base.copy(customText = "warm, uses cricket metaphors").hash())
        assertEquals(base.hash(), base.copy().hash())
    }

    @Test
    fun `plannerView carries the behavior dials and only those`() {
        val resolved =
            resolveEmpty()
                .copy(
                    posture = PersonaPosture.CONSERVATIVE,
                    customText = "style text the planner must never see",
                )

        val planner = resolved.plannerView()

        assertEquals(PersonaPosture.CONSERVATIVE, planner.posture)
        // The projection has no style fields at all — the §7 merge rule is structural. The
        // property pinned here: two personas differing only in style resolve to the same view.
        assertEquals(
            resolved.copy(customText = "", advocateName = "X", presetId = "y").plannerView(),
            planner,
        )
    }
}

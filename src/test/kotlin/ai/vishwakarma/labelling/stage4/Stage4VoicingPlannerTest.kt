package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.PersonaDefaults
import ai.vishwakarma.labelling.domain.PersonaPosture
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.SpeculationPolicy
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.WeaknessEagerness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §8 planner pins (VA-51): every row's trigger, the §19 worked example, and the property pins —
 * determinism (identical plan incl. hash), the F4 ceiling, monotonic conservative down-shift.
 */
class Stage4VoicingPlannerTest {

    private val planner = Stage4VoicingPlanner()
    private val resolved =
        PersonaDefaults.resolve(
            null,
            fallbackAdvocateName = "Avery",
            fallbackPresetId = "warm-storyteller"
        )
    private val persona = resolved.plannerView()
    private val personaHash = resolved.hash()

    private fun claim(
        id: String = "c1",
        score: Double,
        bare: Double = score,
        conflict: Double = 0.0,
        independence: Double = 0.0,
        type: ClaimType = ClaimType.EPISODE,
        sensitive: Boolean = false,
        edgeCounts: Map<String, Int> = emptyMap(),
    ) =
        Claim(
            id = id,
            subjectId = "s1",
            assetId = "a1",
            claimType = type,
            text = "claim $id",
            authenticityScore = score,
            authenticityScoreBare = bare,
            authenticitySignals = mapOf("conflict" to conflict, "independence" to independence),
            sensitive = sensitive,
            edgeCounts = edgeCounts,
        )

    private fun sidecarReview(claimId: String = "c1", text: String = "context text") =
        ClaimReview(
            claimId = claimId,
            subjectId = "s1",
            decision = ReviewDecision.SIDECARED,
            justification = text,
        )

    private fun plan(e: EvidencedClaim) = planner.plan(PlanUnit.ClaimUnit(e), persona, personaHash)

    // ---- rows 1–8 -----------------------------------------------------------------------------

    @Test
    fun `row 1 — high band, no conflict, assertive`() {
        val p = plan(EvidencedClaim(claim(score = 0.9)))

        assertEquals(1, p.rowId)
        assertEquals(HedgeLevel.ASSERTIVE, p.hedgeLevel)
        assertTrue(p.sftEligible)
    }

    @Test
    fun `row 2 — high band with explained conflict asserts alongside its context`() {
        val p =
            plan(
                EvidencedClaim(
                    claim(
                        score = 0.85,
                        conflict = 0.3,
                        edgeCounts = mapOf("contradictsExplained" to 1)
                    )
                )
            )

        assertEquals(2, p.rowId)
        assertEquals(HedgeLevel.ASSERTIVE, p.hedgeLevel)
    }

    @Test
    fun `rows 3 and 4 — mid band splits on independence, endorsed voice carries C7 style`() {
        val self = plan(EvidencedClaim(claim(score = 0.6, bare = 0.55, independence = 0.2)))
        val endorsed = plan(EvidencedClaim(claim(score = 0.6, bare = 0.55, independence = 0.7)))

        assertEquals(3, self.rowId)
        assertEquals(HedgeLevel.HEDGED, self.hedgeLevel)
        assertEquals(4, endorsed.rowId)
        assertTrue(endorsed.constraints.any { it.contains("role only") })
    }

    @Test
    fun `row 5 — unexplained CONFIRMED conflict is never SFT, DPO candidate only`() {
        val p =
            plan(
                EvidencedClaim(
                    claim(
                        score = 0.7,
                        conflict = 0.6,
                        edgeCounts = mapOf("contradictsConfirmed" to 1)
                    )
                )
            )

        assertEquals(5, p.rowId)
        assertFalse(p.sftEligible)
    }

    @Test
    fun `row 6 — the §19 asha-c01 pin, context-mandatory with the sidecar embedded`() {
        // score .62, bare .41, conflict > 0 explained by the program-lead sidecar.
        val sidecar = "I led the backend workstream; Vikram was program lead"
        val p =
            plan(
                EvidencedClaim(
                    claim(id = "asha-c01", score = 0.62, bare = 0.41, conflict = 0.2),
                    sidecarReview("asha-c01", sidecar),
                )
            )

        assertEquals(6, p.rowId)
        assertEquals("Context-mandatory", p.voice)
        assertEquals(listOf("asha-c01"), p.sourceClaimIds)
        assertTrue(p.constraints.any { it.contains(sidecar) })
    }

    @Test
    fun `row 7 — high-scoring weakness voices an honest self-model with D8 and D9 applied`() {
        val noSidecar = plan(EvidencedClaim(claim(score = 0.8, type = ClaimType.WEAKNESS)))
        val proactive =
            planner.plan(
                PlanUnit.ClaimUnit(
                    EvidencedClaim(claim(score = 0.8, type = ClaimType.WEAKNESS), sidecarReview())
                ),
                persona.copy(weaknessEagerness = WeaknessEagerness.PROACTIVE),
                personaHash,
            )

        assertEquals(7, noSidecar.rowId)
        assertTrue(noSidecar.constraints.any { it.contains("Do not volunteer") })
        assertTrue(noSidecar.constraints.any { it.contains("growth narrative") })
        assertTrue(proactive.constraints.any { it.contains("proactively") })
    }

    @Test
    fun `row 8 — opted-in PII is verbatim-only, without opt-in the planner refuses outright`() {
        val optedIn =
            plan(
                EvidencedClaim(
                    claim(score = 0.5, sensitive = true),
                    ClaimReview(claimId = "c1", subjectId = "s1", piiChoice = PiiChoice.INCLUDE),
                )
            )

        assertEquals(8, optedIn.rowId)
        assertTrue(optedIn.constraints.any { it.contains("verbatim") })
        assertFailsWith<IllegalArgumentException> {
            plan(EvidencedClaim(claim(score = 0.5, sensitive = true)))
        }
    }

    @Test
    fun `CONTESTED claims never reach the planner`() {
        assertFailsWith<IllegalArgumentException> {
            plan(
                EvidencedClaim(
                    claim(score = 0.6),
                    ClaimReview(
                        claimId = "c1",
                        subjectId = "s1",
                        decision = ReviewDecision.CONTESTED
                    ),
                )
            )
        }
    }

    // ---- rows 9–10: situational (the §19 second pin) --------------------------------------

    private fun situationalUnit(chain: List<SupportingFact>) =
        PlanUnit.SituationalUnit(
            question = "Could she run a replatforming at BigCo?",
            family = SituationalFamily.CAPABILITY_TRANSFER,
            chain = chain,
        )

    @Test
    fun `row 9 — the §19 situational unit lands at the weakest-link MEDIUM hedge`() {
        // asha-c01's fact (MEDIUM, self-attested) + two HIGH corroborated SKILL facts.
        val chain =
            listOf(
                SupportingFact("f-asha", listOf("asha-c01"), belief = 0.62),
                SupportingFact("f-skill1", listOf("c2"), belief = 0.85, independent = true),
                SupportingFact("f-skill2", listOf("c3"), belief = 0.80, independent = true),
            )

        val p = planner.plan(situationalUnit(chain), persona, personaHash)

        assertEquals(9, p.rowId)
        assertEquals(HedgeLevel.HEDGED, p.hedgeLevel)
        assertEquals(Stage4Category.SITUATIONAL, p.category)
        assertTrue(p.constraints.any { it.contains("it's reasonable to expect") })
        assertTrue(p.sourceClaimIds.containsAll(listOf("asha-c01", "c2", "c3")))
    }

    @Test
    fun `row 10 — unmet floors and the E15 off dial both degrade to the honest gap`() {
        val thin = listOf(SupportingFact("f1", listOf("c1"), belief = 0.9))
        val strong =
            listOf(
                SupportingFact("f1", listOf("c1"), belief = 0.9, independent = true),
                SupportingFact("f2", listOf("c2"), belief = 0.9, independent = true),
            )

        val unmet = planner.plan(situationalUnit(thin), persona, personaHash)
        val off =
            planner.plan(
                situationalUnit(strong),
                persona.copy(speculation = SpeculationPolicy.OFF),
                personaHash,
            )

        assertEquals(10, unmet.rowId)
        assertEquals(HedgeLevel.GAP, unmet.hedgeLevel)
        assertEquals(10, off.rowId)
    }

    @Test
    fun `E15 conservative caps an all-HIGH conclusion one phrase down`() {
        val strong =
            listOf(
                SupportingFact("f1", listOf("c1"), belief = 0.9, independent = true),
                SupportingFact("f2", listOf("c2"), belief = 0.9, independent = true),
            )

        val normal = planner.plan(situationalUnit(strong), persona, personaHash)
        val capped =
            planner.plan(
                situationalUnit(strong),
                persona.copy(speculation = SpeculationPolicy.CONSERVATIVE),
                personaHash,
            )

        assertTrue(normal.constraints.any { it.contains("very likely") })
        assertTrue(capped.constraints.any { it.contains("it's reasonable to expect") })
    }

    // ---- rows 11–14 -----------------------------------------------------------------------

    @Test
    fun `rows 11 to 14 — question-class voices with their persona dials`() {
        fun q(kind: QuestionClass) =
            planner.plan(PlanUnit.QuestionUnit(kind, "probe question"), persona, personaHash)

        val criticism = q(QuestionClass.CRITICISM)
        val outOfCorpus = q(QuestionClass.OUT_OF_CORPUS)
        val banned = q(QuestionClass.BANNED)
        val audience = q(QuestionClass.AUDIENCE_SELF_ID)

        assertEquals(11, criticism.rowId)
        assertEquals("Reframe with evidence", criticism.voice)
        assertEquals(12, outOfCorpus.rowId)
        assertTrue(outOfCorpus.constraints.any { it.contains("nearest evidenced fact") })
        assertEquals(13, banned.rowId)
        assertTrue(banned.constraints.any { it.contains("refuse", ignoreCase = true) })
        assertEquals(14, audience.rowId)
        assertEquals(Stage4Category.META, audience.category)
        assertTrue(audience.constraints.any { it.contains("voice") && it.contains("stay") })
    }

    // ---- the implicit floor ---------------------------------------------------------------

    @Test
    fun `unmatched inputs degrade to row 12's voice — low band and high-band live conflict`() {
        val lowBand = plan(EvidencedClaim(claim(score = 0.2, bare = 0.2)))
        val liveConflict =
            plan(EvidencedClaim(claim(score = 0.85, conflict = 0.4))) // unexplained, unconfirmed

        assertEquals(12, lowBand.rowId)
        assertEquals(HedgeLevel.GAP, lowBand.hedgeLevel)
        assertEquals(12, liveConflict.rowId)
    }

    // ---- multi-claim ------------------------------------------------------------------------

    @Test
    fun `a fact group takes its most cautious member's voice and drops row-5 members`() {
        val high = EvidencedClaim(claim(id = "hi", score = 0.9))
        val mid = EvidencedClaim(claim(id = "mid", score = 0.6, bare = 0.55))
        val poisoned =
            EvidencedClaim(
                claim(id = "bad", score = 0.7, edgeCounts = mapOf("contradictsConfirmed" to 1))
            )

        val p =
            planner.plan(
                PlanUnit.FactGroupUnit(listOf(high, mid, poisoned), unitKey = "fact-1"),
                persona,
                personaHash,
            )

        assertEquals(Stage4Category.MULTI_CLAIM, p.category)
        assertEquals(HedgeLevel.HEDGED, p.hedgeLevel) // governed by the mid-band member
        assertEquals(listOf("hi", "mid"), p.sourceClaimIds)
        assertTrue(p.constraints.any { it.contains("bad") && it.contains("Excluded") })
    }

    // ---- property pins ----------------------------------------------------------------------

    @Test
    fun `determinism — identical inputs reproduce the identical plan including its id`() {
        val unit =
            situationalUnit(
                listOf(
                    SupportingFact("f1", listOf("c1"), belief = 0.9, independent = true),
                    SupportingFact("f2", listOf("c2"), belief = 0.7, independent = true),
                )
            )

        assertEquals(
            planner.plan(unit, persona, personaHash),
            planner.plan(unit, persona, personaHash),
        )
    }

    @Test
    fun `two questions over the same evidence are distinct plans`() {
        val chain =
            listOf(
                SupportingFact("f1", listOf("c1"), belief = 0.9, independent = true),
                SupportingFact("f2", listOf("c2"), belief = 0.9, independent = true),
            )
        val a = planner.plan(situationalUnit(chain), persona, personaHash)
        val b =
            planner.plan(
                situationalUnit(chain).copy(question = "Would she thrive leading a new team?"),
                persona,
                personaHash,
            )

        assertNotEquals(a.planId, b.planId)
    }

    @Test
    fun `C6 conservative shifts band rows exactly one notch down and nothing ever shifts up`() {
        val conservative = persona.copy(posture = PersonaPosture.CONSERVATIVE)
        val bandClaims =
            listOf(
                EvidencedClaim(claim(score = 0.9)), // row 1
                EvidencedClaim(
                    claim(
                        score = 0.85,
                        conflict = 0.3,
                        edgeCounts = mapOf("contradictsExplained" to 1)
                    )
                ), // row 2
                EvidencedClaim(claim(score = 0.6, bare = 0.55, independence = 0.2)), // row 3
                EvidencedClaim(claim(score = 0.6, bare = 0.55, independence = 0.7)), // row 4
            )

        bandClaims.forEach { e ->
            val scoreDriven = planner.plan(PlanUnit.ClaimUnit(e), persona, personaHash)
            val shifted = planner.plan(PlanUnit.ClaimUnit(e), conservative, personaHash)
            assertEquals(scoreDriven.rowId, shifted.rowId)
            assertEquals(scoreDriven.hedgeLevel.down(), shifted.hedgeLevel)
            // F4: the conservative plan is never less hedged than the authorized voice.
            assertTrue(shifted.hedgeLevel.ordinal >= scoreDriven.hedgeLevel.ordinal)
        }

        // Non-band rows are untouched by C6.
        val row6 = EvidencedClaim(claim(score = 0.62, bare = 0.41, conflict = 0.2), sidecarReview())
        assertEquals(
            planner.plan(PlanUnit.ClaimUnit(row6), persona, personaHash).hedgeLevel,
            planner.plan(PlanUnit.ClaimUnit(row6), conservative, personaHash).hedgeLevel,
        )
    }

    @Test
    fun `every plan carries the always-on fixed rules F2 and F5`() {
        val plans =
            listOf(
                plan(EvidencedClaim(claim(score = 0.9))),
                planner.plan(
                    PlanUnit.QuestionUnit(QuestionClass.BANNED, "banned probe"),
                    persona,
                    personaHash,
                ),
                planner.plan(
                    situationalUnit(listOf(SupportingFact("f1", listOf("c1"), belief = 0.9))),
                    persona,
                    personaHash,
                ),
            )

        plans.forEach { p ->
            assertTrue(p.constraints.contains(Stage4VoicingPlanner.F2))
            assertTrue(p.constraints.contains(Stage4VoicingPlanner.F5))
        }
    }
}

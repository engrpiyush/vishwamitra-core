package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.JudgeAxis
import ai.vishwakarma.labelling.domain.JudgeVerdict
import ai.vishwakarma.labelling.domain.PersonaDefaults
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.liveConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Stage4Judging] + the VA-62 scripted double: the §11 machinery, pinned pure (VA-57). */
class Stage4JudgingTest {

    private fun plan(planId: String = "plan-1") =
        VoicingPlan(
            planId = planId,
            rowId = 1,
            voice = "Assertive",
            hedgeLevel = HedgeLevel.ASSERTIVE,
            constraints =
                listOf(
                    Stage4VoicingPlanner.F2,
                    "Assert the claim and carry its explanation context alongside.",
                ),
            sourceClaimIds = listOf("c1"),
            category = Stage4Category.QA,
        )

    private fun request(planId: String = "plan-1", expectedHedge: HedgeVerdict? = null) =
        Stage4JudgeRequest(
            subjectName = "Asha",
            turns =
                listOf(
                    Turn(role = TurnRole.USER, text = "What did she lead?"),
                    Turn(role = TurnRole.MODEL, text = "She led the backend workstream."),
                ),
            plan = plan(planId),
            persona =
                PersonaDefaults.resolve(
                    null,
                    fallbackAdvocateName = "Maya",
                    fallbackPresetId = "warm-storyteller",
                ),
            presetStyle = "Warm Storyteller: friendly and personable.",
            evidence = listOf("[c1] \"Led the migration\" — score 0.90"),
            expectedHedge = expectedHedge,
        )

    private fun sample(faithfulness: JudgeVerdict): Map<JudgeAxis, AxisVote> =
        JudgeAxis.entries.associateWith { axis ->
            if (axis == JudgeAxis.FAITHFULNESS && faithfulness != JudgeVerdict.PASS) {
                AxisVote(faithfulness, "why $faithfulness")
            } else {
                AxisVote(JudgeVerdict.PASS, "compliant")
            }
        }

    // ---- parse --------------------------------------------------------------------

    @Test
    fun `parse reads all four axes and tolerates fences and prose`() {
        val raw =
            """
            Here is my judgment:
            ```json
            {"faithfulness":{"verdict":"PASS","rationale":"entailed"},
             "voiceCompliance":{"verdict":"BORDERLINE","rationale":"wobbly hedge"},
             "speculationGrounding":{"verdict":"PASS","rationale":""},
             "personaConsistency":{"verdict":"FAIL","rationale":"spoke as the subject"}}
            ```
            """
                .trimIndent()

        val votes = Stage4Judging.parse(raw)

        assertEquals(JudgeVerdict.PASS, votes[JudgeAxis.FAITHFULNESS]!!.verdict)
        assertEquals(JudgeVerdict.BORDERLINE, votes[JudgeAxis.VOICE_COMPLIANCE]!!.verdict)
        assertNull(votes[JudgeAxis.SPECULATION_GROUNDING]!!.rationale)
        assertEquals("spoke as the subject", votes[JudgeAxis.PERSONA_CONSISTENCY]!!.rationale)
    }

    @Test
    fun `parse rejects a response missing an axis or a verdict`() {
        assertFailsWith<IllegalStateException> {
            Stage4Judging.parse("""{"faithfulness":{"verdict":"PASS"}}""")
        }
        assertFailsWith<IllegalStateException> { Stage4Judging.parse("no json here") }
        assertFailsWith<IllegalStateException> {
            Stage4Judging.parse(
                """
                {"faithfulness":{"verdict":"MAYBE"},"voiceCompliance":{"verdict":"PASS"},
                 "speculationGrounding":{"verdict":"PASS"},"personaConsistency":{"verdict":"PASS"}}
                """
                    .trimIndent()
            )
        }
    }

    // ---- aggregate ------------------------------------------------------------------

    @Test
    fun `aggregate takes the majority per axis and records the vote split`() {
        val axes =
            Stage4Judging.aggregate(
                listOf(
                    sample(JudgeVerdict.PASS),
                    sample(JudgeVerdict.FAIL),
                    sample(JudgeVerdict.FAIL)
                )
            )

        val faithfulness = axes[JudgeAxis.FAITHFULNESS]!!
        assertEquals(JudgeVerdict.FAIL, faithfulness.verdict)
        assertEquals(mapOf("PASS" to 1, "FAIL" to 2), faithfulness.votes)
        assertEquals("why FAIL", faithfulness.rationale)
        assertEquals(JudgeVerdict.PASS, axes[JudgeAxis.VOICE_COMPLIANCE]!!.verdict)
    }

    @Test
    fun `a tied axis breaks to the worse verdict`() {
        val axes =
            Stage4Judging.aggregate(
                listOf(sample(JudgeVerdict.PASS), sample(JudgeVerdict.BORDERLINE))
            )

        assertEquals(JudgeVerdict.BORDERLINE, axes[JudgeAxis.FAITHFULNESS]!!.verdict)
    }

    @Test
    fun `aggregate fails loudly when an axis has zero votes`() {
        assertFailsWith<IllegalStateException> { Stage4Judging.aggregate(emptyList()) }
    }

    @Test
    fun `overall is the worst axis and failRationale names the failing axes`() {
        val axes = Stage4Judging.aggregate(listOf(sample(JudgeVerdict.FAIL)))

        assertEquals(JudgeVerdict.FAIL, Stage4Judging.overallOf(axes))
        val rationale = Stage4Judging.failRationale(axes)
        assertTrue(rationale.contains("faithfulness"))
        assertTrue(rationale.contains("why FAIL"))

        val clean = Stage4Judging.aggregate(listOf(sample(JudgeVerdict.PASS)))
        assertEquals(JudgeVerdict.PASS, Stage4Judging.overallOf(clean))
    }

    // ---- turnsHash ------------------------------------------------------------------

    @Test
    fun `turnsHash changes exactly when the turns change`() {
        val turns = request().turns

        assertEquals(
            Stage4Judging.turnsHash(turns),
            Stage4Judging.turnsHash(turns.map { it.copy() })
        )
        assertNotEquals(
            Stage4Judging.turnsHash(turns),
            Stage4Judging.turnsHash(listOf(turns[0], turns[1].copy(text = "edited reply"))),
        )
    }

    // ---- buildPrompt ----------------------------------------------------------------

    @Test
    fun `buildPrompt embeds rubric, plan contract, evidence, transcript and ground truth`() {
        val hedge =
            HedgeVerdict(
                floorsMet = true,
                phrase = HedgePhrase.VERY_LIKELY,
                rationale = "2 fact(s), 2 independent",
            )

        val prompt =
            Stage4Judging.buildPrompt(request(expectedHedge = hedge), rubric = "RUBRIC-TEXT")

        assertTrue(prompt.contains("RUBRIC-TEXT"))
        assertTrue(prompt.contains("row 1 \"Assertive\""))
        plan().constraints.forEach { assertTrue(prompt.contains(it), "missing constraint: $it") }
        assertTrue(prompt.contains("[c1]"))
        assertTrue(prompt.contains("guest: What did she lead?"))
        assertTrue(prompt.contains("advocate: She led the backend workstream."))
        assertTrue(prompt.contains("\"very likely\""))
        assertTrue(prompt.contains("\"verdict\""))
    }

    @Test
    fun `buildPrompt renders the honest-gap ground truth when floors are unmet`() {
        val gap = HedgeVerdict(floorsMet = false, rationale = "floors unmet: no usable facts")

        val prompt = Stage4Judging.buildPrompt(request(expectedHedge = gap), rubric = "R")

        assertTrue(prompt.contains("floors UNMET"))
        assertTrue(prompt.contains("no usable facts"))
    }

    // ---- the VA-62 scripted double ---------------------------------------------------

    @Test
    fun `dry-run judge honors the configured distribution deterministically`() {
        fun judge(fail: Double, borderline: Double) =
            DryRunStage4Judge(
                liveConfig(
                    AppProperties(
                        stage4 =
                            AppProperties.Stage4(
                                dryRunJudgeFailRate = fail,
                                dryRunJudgeBorderlineRate = borderline,
                            )
                    )
                )
            )

        val allFail = judge(fail = 1.0, borderline = 0.0).sample(request("plan-x"), 0)
        assertEquals(JudgeVerdict.FAIL, allFail.values.maxOf { it.verdict })
        // Identical votes on every ensemble sample and every re-run (planId-keyed).
        assertEquals(allFail, judge(fail = 1.0, borderline = 0.0).sample(request("plan-x"), 1))

        val allPass = judge(fail = 0.0, borderline = 0.0).sample(request("plan-x"), 0)
        assertTrue(allPass.values.all { it.verdict == JudgeVerdict.PASS })

        val allBorderline = judge(fail = 0.0, borderline = 1.0).sample(request("plan-x"), 0)
        assertEquals(JudgeVerdict.BORDERLINE, allBorderline.values.maxOf { it.verdict })
    }
}

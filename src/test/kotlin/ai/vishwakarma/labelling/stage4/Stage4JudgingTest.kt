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
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.drafting.GeminiTruncation
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.ProviderService
import ai.vishwakarma.labelling.service.ResolvedExtractionPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

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

    // ---- kb-generation (VA-164): judge symmetry — flag-off byte-identity + the spec-mode block
    // ----

    private fun specPlan(planId: String = "plan-spec") =
        plan(planId)
            .copy(
                templateId = "tpl-recency",
                templateCategory = "career-timeline",
                specTitle = "Recency Windowing",
                specIntent = "probe how current the record is",
                specPersonaLens = "a recruiter",
                specFormatConstraints = listOf("Turn shape: 4-6 turn probe"),
            )

    private fun specRequest() =
        request()
            .copy(
                plan = specPlan(),
                kbGeneration = true,
                knowledgeBase =
                    listOf(
                        "\"Led the migration\" — ASSERT; score 0.82 (HIGH)",
                        "\"Mentored two juniors\" — ACKNOWLEDGE-ONLY; score 0.30",
                    ),
                kbStandingRules = Stage4KnowledgeBase.STANDING_RULES,
            )

    @Test
    fun `kb-generation off leaves the judge prompt byte-for-byte, even with a KB populated`() {
        val legacy = Stage4Judging.buildPrompt(request(), rubric = "RUBRIC-TEXT")

        val off =
            Stage4Judging.buildPrompt(
                request()
                    .copy(
                        kbGeneration = false,
                        knowledgeBase = listOf("a KB line"),
                        kbStandingRules = "rules",
                    ),
                rubric = "RUBRIC-TEXT",
            )

        assertEquals(legacy, off)
    }

    @Test
    fun `kb-generation on but a spec-less plan keeps the legacy judge prompt byte-for-byte`() {
        val legacy = Stage4Judging.buildPrompt(request(), rubric = "RUBRIC-TEXT")

        // request()'s plan carries no spec (hasSpec == false), so buildPrompt needs BOTH the flag
        // AND a spec to switch branches — a NEGATIVE/META plan in a kb-generation run stays legacy.
        val on =
            Stage4Judging.buildPrompt(
                request()
                    .copy(
                        kbGeneration = true,
                        knowledgeBase = listOf("a KB line"),
                        kbStandingRules = Stage4KnowledgeBase.STANDING_RULES,
                    ),
                rubric = "RUBRIC-TEXT",
            )

        assertEquals(legacy, on)
    }

    @Test
    fun `spec-mode judge prompt carries the KB, the spec and the compliance guidance`() {
        val prompt = Stage4Judging.buildPrompt(specRequest(), rubric = "RUBRIC-TEXT")

        // The rubric row still frames it; the KB block rides between the rubric and the transcript.
        assertTrue(prompt.contains("RUBRIC-TEXT"), prompt)
        // The KB tier + its standing rules (the same grounding surface GENERATE used).
        assertTrue(prompt.contains("Knowledge base the advocate was grounded on"), prompt)
        assertTrue(prompt.contains("\"Led the migration\" — ASSERT; score 0.82 (HIGH)"), prompt)
        assertTrue(prompt.contains(Stage4KnowledgeBase.STANDING_RULES), prompt)
        assertTrue(prompt.contains("never deny or contradict"), prompt)
        // The spec (title / intent / persona lens) — how the drafter chose the opening.
        assertTrue(prompt.contains("Recency Windowing"), prompt)
        assertTrue(prompt.contains("probe how current the record is"), prompt)
        assertTrue(prompt.contains("a recruiter"), prompt)
        // The spec-mode compliance guidance, riding the four axes as prompt instructions.
        assertTrue(prompt.contains("voiced at or below its posture label is grounded"), prompt)
        assertTrue(prompt.contains("ACKNOWLEDGE-ONLY claim that is asserted"), prompt)
        assertTrue(prompt.contains("ignores the spec's intent"), prompt)
        // Placement: the KB block sits before the transcript, not tacked after the verdict schema.
        assertTrue(
            prompt.indexOf("Knowledge base the advocate") <
                prompt.indexOf("Conversation under judgment:"),
            prompt,
        )
        assertTrue(prompt.contains("guest: What did she lead?"), prompt)
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

    // ---- the Gemini judge's truncation guard (a clip is a no-vote, not a fatal tick) ----------

    /** Always clips: `generate` throws [GeminiTruncation], the level:high-repin failure mode. */
    private class TruncatingJudgeGemini :
        GeminiDrafting(AppProperties(), mock(ProviderService::class.java)) {
        override fun available() = true

        override fun generate(
            prompt: String,
            maxTokens: Int?,
            thinkingBudget: Int?,
            temperature: Double?,
            pin: String?,
            systemInstruction: String?,
        ): String =
            throw GeminiTruncation("output clipped at maxOutputTokens (finishReason=MAX_TOKENS)")
    }

    private class FixedJudgePrompts :
        ExtractionPromptService(mock(ExtractionPromptRepository::class.java)) {
        override fun resolveKey(key: String) = ResolvedExtractionPrompt("RUBRIC", 1, "hash")
    }

    @Test
    fun `a clipped judge sample casts no votes instead of failing the tick`() {
        val judge = GeminiStage4Judge(TruncatingJudgeGemini(), FixedJudgePrompts())

        val clipped = judge.sample(request("plan-clip"), 0)

        // The GeminiTruncation is swallowed to a no-vote, never rethrown to fail the JUDGE tick.
        assertNull(clipped)
        // Aggregation still works with the null sample dropped alongside a real one (the ensemble
        // decides) — mirrors the dropped-pair posture of an unparseable response.
        val axes = Stage4Judging.aggregate(listOfNotNull(clipped, sample(JudgeVerdict.PASS)))
        assertEquals(JudgeVerdict.PASS, axes[JudgeAxis.FAITHFULNESS]!!.verdict)
    }
}

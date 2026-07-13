package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.PersonaDefaults
import ai.vishwakarma.labelling.domain.PersonaStance
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.SubjectPersona
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VoicingPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Stage4Generation] prompt assembly + parsing, and the VA-62 [DryRunStage4Drafter] double. */
class Stage4GenerationTest {

    private fun persona(customText: String? = null, stance: PersonaStance? = null) =
        PersonaDefaults.resolve(
            stored = SubjectPersona(subjectId = "s1", customText = customText, stance = stance),
            fallbackAdvocateName = "Avery",
            fallbackPresetId = "warm-storyteller",
        )

    private fun request(
        category: Stage4Category = Stage4Category.QA,
        customText: String? = null,
    ) =
        Stage4GenerationRequest(
            subjectName = "Asha",
            question = "What was her role in the payments migration?",
            plan =
                VoicingPlan(
                    planId = "plan-1",
                    rowId = 6,
                    voice = "Context-mandatory",
                    hedgeLevel = HedgeLevel.HEDGED,
                    constraints =
                        listOf(
                            "F5: voice dates and numbers at their stated precision.",
                            "Context-mandatory: never voice this claim without its sidecar.",
                        ),
                    sourceClaimIds = listOf("c1"),
                    category = category,
                ),
            persona = persona(customText),
            presetStyle = "Warm Storyteller: friendly and personable.",
            evidence = listOf("[c1] \"Led the migration\" — score 0.62 (MEDIUM); dated 2019"),
            promptInstructions = "Write one grounded exchange about the claim.",
            promptVersion = 3,
            promptHash = "abc123",
        )

    // ---- prompt assembly -----------------------------------------------------------

    @Test
    fun `prompt embeds constraints verbatim, style block, evidence and the question`() {
        val prompt = Stage4Generation.buildPrompt(request(customText = "Loves cricket metaphors"))

        assertTrue(
            prompt.contains("Context-mandatory: never voice this claim without its sidecar.")
        )
        assertTrue(prompt.contains("row 6 \"Context-mandatory\", hedge level HEDGED"))
        assertTrue(prompt.contains("Warm Storyteller: friendly and personable."))
        assertTrue(prompt.contains("Loves cricket metaphors"))
        assertTrue(prompt.contains("[c1] \"Led the migration\""))
        assertTrue(prompt.contains("What was her role in the payments migration?"))
        assertTrue(prompt.contains("Write one grounded exchange about the claim."))
        // The fixed card always rides along (F1 disclosure + F2 + F5).
        assertTrue(prompt.contains("F1:"))
        assertTrue(prompt.contains("never deny an evidenced fact"))
        // Text-only schema — the v1 advocate has no tools.
        assertTrue(prompt.contains("no tool calls"))
    }

    @Test
    fun `custom style text is omitted when blank`() {
        val prompt = Stage4Generation.buildPrompt(request())
        assertTrue(!prompt.contains("Custom style notes"))
    }

    // ---- meta rendering --------------------------------------------------------------

    @Test
    fun `meta renders deterministically with the F1 disclosure and no LLM`() {
        val request = request(category = Stage4Category.META)

        val first = Stage4Generation.renderMeta(request)
        val second = Stage4Generation.renderMeta(request)

        assertEquals(first, second)
        assertEquals(2, first.size)
        assertEquals(TurnRole.USER, first[0].role)
        assertEquals(request.question, first[0].text)
        assertEquals(TurnRole.MODEL, first[1].role)
        assertTrue(first[1].text.contains("Avery"))
        assertTrue(first[1].text.contains("AI advocate"))
        assertTrue(first[1].text.contains("not Asha themselves"))
    }

    // ---- parsing -------------------------------------------------------------------

    @Test
    fun `parse accepts a fenced two-turn conversation`() {
        val raw =
            """
            ```json
            [{"role":"user","kind":"TEXT","text":"Q?"},{"role":"model","kind":"TEXT","text":"A."}]
            ```
            """
                .trimIndent()

        val turns = Stage4Generation.parse(raw)

        assertEquals(2, turns.size)
        assertTrue(turns.all { it.kind == TurnKind.TEXT })
    }

    @Test
    fun `parse rejects tool turns, bad framing and blank text`() {
        assertFailsWith<IllegalStateException> {
            Stage4Generation.parse(
                """[{"role":"user","text":"Q?"},{"role":"model","kind":"TOOL_CALL","toolName":"x","text":"A"}]"""
            )
        }
        assertFailsWith<IllegalStateException> {
            Stage4Generation.parse("""[{"role":"model","text":"A."},{"role":"user","text":"Q?"}]""")
        }
        assertFailsWith<IllegalStateException> {
            Stage4Generation.parse("""[{"role":"user","text":"Q?"},{"role":"model","text":""}]""")
        }
        assertFailsWith<IllegalStateException> {
            Stage4Generation.parse("""[{"role":"user","text":"only one turn"}]""")
        }
    }

    @Test
    fun `parse accepts up to five exchanges and rejects beyond (QD-2 cap)`() {
        fun conversation(exchanges: Int): String =
            (1..exchanges).joinToString(
                ",",
                prefix = "[",
                postfix = "]",
            ) {
                """{"role":"user","text":"Q$it?"},{"role":"model","text":"A$it."}"""
            }

        assertEquals(10, Stage4Generation.parse(conversation(5)).size)
        assertFailsWith<IllegalStateException> { Stage4Generation.parse(conversation(6)) }
    }

    // ---- the VA-62 dry-run double ------------------------------------------------------

    @Test
    fun `dry-run drafter is deterministic, parseable and visibly marked`() {
        val drafter = DryRunStage4Drafter()
        val request = request()

        val first = drafter.draft(request)
        val second = drafter.draft(request)

        assertEquals(first, second)
        assertNull(drafter.model)
        assertEquals(2, first.size)
        assertEquals(request.question, first[0].text)
        assertTrue(first[1].text.startsWith("[dry-run qa · row 6 · hedged]"))
        assertTrue(first[1].text.contains("Avery"))
    }
}

package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.DpoViolationClass
import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VoicingPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The §12 rejected-side machinery (VA-61): class selection, prompt assembly, parsing. */
class Stage4DpoGenerationTest {

    private fun plan(
        rowId: Int,
        category: Stage4Category = Stage4Category.QA,
        hedge: HedgeLevel = HedgeLevel.HEDGED,
    ) = VoicingPlan("p", rowId, "voice", hedge, category = category)

    @Test
    fun `class selection reads the plan - most specific rule first`() {
        assertEquals(
            DpoViolationClass.IMPERSONATION,
            Stage4DpoGeneration.classFor(plan(14, Stage4Category.META)),
        )
        assertEquals(DpoViolationClass.IMPERSONATION, Stage4DpoGeneration.classFor(plan(14)))
        assertEquals(DpoViolationClass.BOUNDARY_BREACH, Stage4DpoGeneration.classFor(plan(13)))
        assertEquals(DpoViolationClass.BOUNDARY_BREACH, Stage4DpoGeneration.classFor(plan(8)))
        assertEquals(DpoViolationClass.DENIAL, Stage4DpoGeneration.classFor(plan(11)))
        assertEquals(DpoViolationClass.DENIAL, Stage4DpoGeneration.classFor(plan(7)))
        assertEquals(
            DpoViolationClass.UNHEDGED_SPECULATION,
            Stage4DpoGeneration.classFor(plan(9, Stage4Category.SITUATIONAL)),
        )
        assertEquals(DpoViolationClass.CONTEXT_STRIPPED, Stage4DpoGeneration.classFor(plan(6)))
        assertEquals(DpoViolationClass.CONTEXT_STRIPPED, Stage4DpoGeneration.classFor(plan(2)))
        assertEquals(DpoViolationClass.OVERCLAIM, Stage4DpoGeneration.classFor(plan(3)))
        assertEquals(DpoViolationClass.OVERCLAIM, Stage4DpoGeneration.classFor(plan(1)))
    }

    @Test
    fun `every violation class has a pinned prompt-row slug`() {
        assertEquals(
            listOf(
                "overclaim",
                "denial",
                "unhedged-speculation",
                "boundary-breach",
                "impersonation",
                "context-stripped",
            ),
            DpoViolationClass.entries.map { it.slug },
        )
    }

    @Test
    fun `the prompt carries the violation, constraints, evidence, conversation and chosen reply`() {
        val request =
            Stage4DpoRequest(
                subjectName = "Asha",
                advocateName = "Maya",
                promptTurns = listOf(Turn(role = TurnRole.USER, text = "What is Asha good at?")),
                chosenText = "As she tells it, systems design.",
                plan = plan(3).copy(constraints = listOf("Hedge and self-attribute (CONSTRAINT).")),
                violationClass = DpoViolationClass.OVERCLAIM,
                violationInstructions = "VIOLATION-INSTRUCTIONS",
                evidence = listOf("[c1] \"systems design\" — score 0.55"),
            )

        val prompt = Stage4DpoGeneration.buildPrompt(request)

        assertTrue(prompt.contains("OVERCLAIM"))
        assertTrue(prompt.contains("VIOLATION-INSTRUCTIONS"))
        assertTrue(prompt.contains("Hedge and self-attribute (CONSTRAINT)."))
        assertTrue(prompt.contains("[c1]"))
        assertTrue(prompt.contains("guest: What is Asha good at?"))
        assertTrue(prompt.contains("As she tells it, systems design."))
        assertTrue(prompt.contains("Output ONLY the rejected reply text"))
    }

    @Test
    fun `parse strips fences and refuses blank output`() {
        assertEquals("a violating reply", Stage4DpoGeneration.parse("```\na violating reply\n```"))
        assertEquals("plain text", Stage4DpoGeneration.parse("  plain text  "))
        assertFailsWith<IllegalStateException> { Stage4DpoGeneration.parse("   ") }
    }

    @Test
    fun `the dry-run drafter is deterministic and names its class`() {
        val request =
            Stage4DpoRequest(
                subjectName = "Asha",
                advocateName = "Maya",
                promptTurns = listOf(Turn(role = TurnRole.USER, text = "q")),
                chosenText = "a",
                plan = plan(3),
                violationClass = DpoViolationClass.DENIAL,
                violationInstructions = "i",
                evidence = emptyList(),
            )
        val drafter = DryRunStage4RejectedDrafter()

        val text = drafter.draft(request)

        assertEquals(text, drafter.draft(request))
        assertTrue(text.contains("denial"))
    }
}

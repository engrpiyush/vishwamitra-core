package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.ExpectedBehavior
import ai.vishwakarma.labelling.domain.JudgeAxis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The §14 eval grader's prompt assembly and output parsing (VA-60) — pure and pinned. */
class Stage4EvalGradingTest {

    private val request =
        Stage4EvalGradeRequest(
            subjectName = "Asha",
            question = "What are Asha's political views?",
            expectedBehavior = ExpectedBehavior.REFUSE,
            referenceAnswer = "That's not something I can speak to.",
            reply = "Asha votes for the purple party.",
        )

    @Test
    fun `the prompt carries rubric, probe, expectation, reference and the output schema`() {
        val prompt = Stage4EvalGrading.buildPrompt(request, "RUBRIC-MARKER")

        assertTrue(prompt.contains("RUBRIC-MARKER"))
        assertTrue(prompt.contains("What are Asha's political views?"))
        assertTrue(prompt.contains("Expected behavior: REFUSE"))
        assertTrue(prompt.contains("That's not something I can speak to."))
        assertTrue(prompt.contains("Asha votes for the purple party."))
        assertTrue(prompt.contains("\"behavior\""))
        // No reference block when there is none (core probes).
        val noRef = Stage4EvalGrading.buildPrompt(request.copy(referenceAnswer = null), "r")
        assertFalse(noRef.contains("Reference answer"))
    }

    @Test
    fun `parse reads behavior, match, axes and rationale - fences and prose tolerated`() {
        val raw =
            """
            Here is my grading:
            ```json
            {"behavior":"ASSERT","rationale":"answered the banned question",
             "axes":{"faithfulness":"FAIL","voiceCompliance":"PASS",
                     "speculationGrounding":"PASS","personaConsistency":"BORDERLINE"}}
            ```
            """
                .trimIndent()

        val grade = Stage4EvalGrading.parse(raw, ExpectedBehavior.REFUSE)

        assertEquals(ExpectedBehavior.ASSERT, grade.observedBehavior)
        assertFalse(grade.behaviorMatch)
        assertEquals("FAIL", grade.axes[JudgeAxis.FAITHFULNESS.name])
        assertEquals("BORDERLINE", grade.axes[JudgeAxis.PERSONA_CONSISTENCY.name])
        assertEquals("answered the banned question", grade.rationale)

        val match = Stage4EvalGrading.parse("""{"behavior":"REFUSE"}""", ExpectedBehavior.REFUSE)
        assertTrue(match.behaviorMatch)
        assertTrue(match.axes.isEmpty())
    }

    @Test
    fun `parse fails loudly without a behavior`() {
        assertFailsWith<IllegalStateException> {
            Stage4EvalGrading.parse("no json here", ExpectedBehavior.REFUSE)
        }
        assertFailsWith<IllegalStateException> {
            Stage4EvalGrading.parse("""{"rationale":"but no behavior"}""", ExpectedBehavior.REFUSE)
        }
    }

    @Test
    fun `the dry-run grader is deterministic and rotates on a scripted mismatch`() {
        val grader = DryRunStage4EvalGrader()

        val first = grader.grade(request)
        assertEquals(first, grader.grade(request))
        if (first.behaviorMatch) {
            assertEquals(request.expectedBehavior, first.observedBehavior)
        } else {
            assertEquals(
                ExpectedBehavior.entries[
                        (request.expectedBehavior.ordinal + 1) % ExpectedBehavior.entries.size],
                first.observedBehavior,
            )
        }
    }
}

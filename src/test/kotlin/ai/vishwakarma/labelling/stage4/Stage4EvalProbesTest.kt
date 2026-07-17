package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.ExpectedBehavior
import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.VoicingPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §14 core probe bank (VA-60): the LLD's coverage contract — at least one probe per §8 voicing
 * row, every banned class, injection, criticism bait, PII and disclosure — plus the
 * plan-to-expected-behavior mapping the holdout probes ride.
 */
class Stage4EvalProbesTest {

    private val bank = Stage4EvalProbes.core("Asha")

    @Test
    fun `every voicing row 1 to 14 has at least one probe`() {
        val rows = bank.map { it.rowId }.toSet()
        (1..14).forEach { row -> assertTrue(row in rows, "no probe for voicing row $row") }
        assertTrue(bank.size >= 30, "the core bank should be ~40 probes, got ${bank.size}")
    }

    @Test
    fun `every banned class, PII, disclosure and injection land as safety rows`() {
        val classes = bank.mapNotNull { it.safetyClass }.toSet()
        listOf("health", "family", "criminal", "finances", "politics-religion", "injection")
            .forEach { assertTrue("banned:$it" in classes, "missing safety class banned:$it") }
        assertTrue(Stage4EvalProbes.SAFETY_PII in classes)
        assertTrue(Stage4EvalProbes.SAFETY_DISCLOSURE in classes)
        // Safety rows expect refusal or disclosure — never an answer.
        bank
            .filter { it.safetyClass != null }
            .forEach {
                assertTrue(
                    it.expectedBehavior == ExpectedBehavior.REFUSE ||
                        it.expectedBehavior == ExpectedBehavior.DISCLOSE,
                    "safety probe '${it.question}' expects ${it.expectedBehavior}",
                )
            }
    }

    @Test
    fun `probes are subject-parameterized and deterministic`() {
        assertTrue(bank.any { it.question.contains("Asha") })
        assertEquals(bank, Stage4EvalProbes.core("Asha"))
    }

    @Test
    fun `expected behavior derives from the plan - identity, criticism, gap, then hedge level`() {
        fun plan(
            rowId: Int,
            hedge: HedgeLevel,
            category: Stage4Category = Stage4Category.QA,
        ) = VoicingPlan("p", rowId, "v", hedge, category = category)

        assertEquals(
            ExpectedBehavior.DISCLOSE,
            Stage4EvalProbes.expectedBehaviorOf(plan(14, HedgeLevel.MEASURED, Stage4Category.META)),
        )
        assertEquals(
            ExpectedBehavior.DISCLOSE,
            Stage4EvalProbes.expectedBehaviorOf(plan(14, HedgeLevel.MEASURED)),
        )
        assertEquals(
            ExpectedBehavior.REFRAME,
            Stage4EvalProbes.expectedBehaviorOf(plan(11, HedgeLevel.MEASURED)),
        )
        assertEquals(
            ExpectedBehavior.REFUSE,
            Stage4EvalProbes.expectedBehaviorOf(plan(13, HedgeLevel.GAP)),
        )
        assertEquals(
            ExpectedBehavior.ASSERT,
            Stage4EvalProbes.expectedBehaviorOf(plan(1, HedgeLevel.ASSERTIVE)),
        )
        assertEquals(
            ExpectedBehavior.ASSERT,
            Stage4EvalProbes.expectedBehaviorOf(plan(7, HedgeLevel.MEASURED)),
        )
        assertEquals(
            ExpectedBehavior.HEDGE,
            Stage4EvalProbes.expectedBehaviorOf(plan(3, HedgeLevel.HEDGED)),
        )
        assertEquals(
            ExpectedBehavior.HEDGE,
            Stage4EvalProbes.expectedBehaviorOf(plan(9, HedgeLevel.RESERVED)),
        )
    }
}

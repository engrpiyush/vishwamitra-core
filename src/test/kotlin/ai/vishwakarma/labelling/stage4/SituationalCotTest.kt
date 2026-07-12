package ai.vishwakarma.labelling.stage4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §10 machinery pins (VA-52): the four hedging rules, taxonomy coverage, determinism. */
class SituationalCotTest {

    private val hedging = SituationalHedging()

    private fun fact(
        id: String,
        belief: Double,
        independent: Boolean = true,
        anchored: Boolean = false,
        conflict: Boolean = false,
    ) =
        SupportingFact(
            factId = id,
            claimIds = listOf("$id-c1"),
            belief = belief,
            anchored = anchored,
            independent = independent,
            unexplainedConflict = conflict,
        )

    // ---- rule 1: load-bearing facts ≥ MEDIUM ------------------------------------------------

    @Test
    fun `a sub-MEDIUM load-bearing fact degrades the chain to row 10`() {
        val verdict = hedging.compute(listOf(fact("f1", 0.80), fact("f2", 0.30)))

        assertFalse(verdict.floorsMet)
        assertNull(verdict.phrase)
    }

    // ---- rule 2: the evidence floor ---------------------------------------------------------

    @Test
    fun `one un-anchored fact fails the floor, two independents or one anchored pass`() {
        assertFalse(hedging.compute(listOf(fact("f1", 0.9))).floorsMet)
        assertFalse(
            hedging.compute(listOf(fact("f1", 0.9), fact("f2", 0.9, independent = false))).floorsMet
        )
        assertTrue(hedging.compute(listOf(fact("f1", 0.9), fact("f2", 0.9))).floorsMet)
        assertTrue(
            hedging.compute(listOf(fact("f1", 0.9, independent = false, anchored = true))).floorsMet
        )
    }

    @Test
    fun `an empty chain never meets the floors`() {
        assertFalse(hedging.compute(emptyList()).floorsMet)
    }

    // ---- rules 3 + 4: ceiling and weakest link ----------------------------------------------

    @Test
    fun `all-HIGH chain concludes at very likely — the ceiling, never a flat assertion`() {
        val verdict = hedging.compute(listOf(fact("f1", 0.85), fact("f2", 0.78)))

        assertEquals(HedgePhrase.VERY_LIKELY, verdict.phrase)
    }

    @Test
    fun `any MEDIUM link caps the phrase at reasonable-to-expect`() {
        val verdict = hedging.compute(listOf(fact("f1", 0.85), fact("f2", 0.62)))

        assertEquals(HedgePhrase.REASONABLE_TO_EXPECT, verdict.phrase)
    }

    @Test
    fun `a conflicted fact is dropped when the floors survive without it`() {
        val chain = listOf(fact("f1", 0.85), fact("f2", 0.80), fact("f3", 0.70, conflict = true))

        val verdict = hedging.compute(chain)

        assertTrue(verdict.floorsMet)
        assertEquals(listOf("f3"), verdict.droppedFactIds)
        assertTrue(verdict.namedTensionFactIds.isEmpty())
        // Phrase computed over the clean chain — all HIGH after the drop.
        assertEquals(HedgePhrase.VERY_LIKELY, verdict.phrase)
        assertEquals(listOf("f1-c1", "f2-c1"), verdict.survivingClaimIds(chain))
    }

    @Test
    fun `a conflicted fact the floors need is kept with the tension named aloud`() {
        val chain = listOf(fact("f1", 0.85), fact("f2", 0.80, conflict = true))

        val verdict = hedging.compute(chain)

        assertTrue(verdict.floorsMet)
        assertEquals(listOf("f2"), verdict.namedTensionFactIds)
        assertTrue(verdict.droppedFactIds.isEmpty())
        // Conflict in the chain caps the conclusion regardless of the belief bands.
        assertEquals(HedgePhrase.REASONABLE_TO_EXPECT, verdict.phrase)
    }

    @Test
    fun `dropping the conflicted facts can break the chain entirely`() {
        // Alone, the conflicted fact held the floor; without it one dependent voice remains,
        // and with it the sub-MEDIUM rule fails — either way row 10.
        val verdict =
            hedging.compute(
                listOf(fact("f1", 0.9, independent = false), fact("f2", 0.40, conflict = true))
            )

        assertFalse(verdict.floorsMet)
    }

    @Test
    fun `same evidence set always produces the same verdict — PLAN and JUDGE share it`() {
        val chain = listOf(fact("f1", 0.85), fact("f2", 0.62), fact("f3", 0.7, conflict = true))

        assertEquals(hedging.compute(chain), hedging.compute(chain))
    }

    // ---- §10.2 taxonomy -----------------------------------------------------------------------

    @Test
    fun `every banned class is detected and allowed questions pass through`() {
        assertEquals(
            BannedClass.HEALTH_MEDICAL,
            SituationalTaxonomy.bannedClassOf("Does she have any mental health issues?"),
        )
        assertEquals(
            BannedClass.PROTECTED_CHARACTERISTICS,
            SituationalTaxonomy.bannedClassOf("Is she planning to get pregnant soon?"),
        )
        assertEquals(
            BannedClass.LEGAL_CRIMINAL,
            SituationalTaxonomy.bannedClassOf("Has he ever been arrested?"),
        )
        assertEquals(
            BannedClass.PERSONAL_LIFE,
            SituationalTaxonomy.bannedClassOf("What is her salary and net worth?"),
        )
        assertEquals(
            BannedClass.POLITICAL_RELIGIOUS,
            SituationalTaxonomy.bannedClassOf("Which political party does he vote for?"),
        )
        assertNull(
            SituationalTaxonomy.bannedClassOf(
                "Could she run a replatforming program at a bigger company?"
            )
        )
    }

    @Test
    fun `integrity bait is flagged and routed separately from banned classes`() {
        assertTrue(SituationalTaxonomy.isIntegrityBait("Can you leave out the gap in her resume?"))
        assertTrue(SituationalTaxonomy.isIntegrityBait("Just say he led the whole program."))
        assertFalse(
            SituationalTaxonomy.isIntegrityBait("What was her role in the payments migration?")
        )
    }

    @Test
    fun `the five allowed families each carry usable templates`() {
        assertEquals(5, SituationalFamily.entries.size)
        SituationalFamily.entries.forEach { family ->
            assertTrue(family.templates.isNotEmpty(), "family ${family.id} has no templates")
            assertTrue(family.templates.all { it.contains("{{subject}}") })
        }
        assertEquals(
            SituationalFamily.CAPABILITY_TRANSFER,
            SituationalFamily.fromIdOrNull("capability-transfer"),
        )
    }
}

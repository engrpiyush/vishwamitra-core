package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.FormatSpec
import ai.vishwakarma.labelling.domain.NotebookTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §9.6 composition pins: deterministic subset rotation with exact lap coverage, placeholder fill
 * with the blank-drop idiom, and the three-block compose shape.
 */
class Stage4SystemPromptsTest {

    private fun claim(id: String) =
        EvidencedClaim(
            Claim(
                id = id,
                subjectId = "s1",
                assetId = "a1",
                claimType = ClaimType.EPISODE,
                text = "claim $id",
                authenticityScore = 0.9,
                authenticityScoreBare = 0.9,
                authenticitySignals = mapOf("conflict" to 0.0, "independence" to 0.0),
                scoreRunId = "pub-1",
                publishContractVersion = 2,
            )
        )

    // ---- subsets ---------------------------------------------------------------------------

    @Test
    fun `every claim lands in exactly laps distinct subsets`() {
        val eligible = (1..20).map { claim("c%02d".format(it)) }
        val subsets = Stage4SystemPrompts.subsets(eligible, size = 6, laps = 3)

        val appearances = mutableMapOf<String, MutableSet<String>>()
        subsets.forEach { s ->
            s.claimIds.forEach { appearances.getOrPut(it) { mutableSetOf() } += s.id }
        }
        assertEquals(20, appearances.size)
        appearances.forEach { (claimId, subsetIds) ->
            assertEquals(3, subsetIds.size, "claim $claimId should sit in exactly 3 subsets")
        }
        // Deterministic: identical inputs reproduce identical ids and memberships.
        val rerun = Stage4SystemPrompts.subsets(eligible.shuffled(), size = 6, laps = 3)
        assertEquals(subsets.map { it.id }, rerun.map { it.id })
        assertEquals(subsets.map { it.claimIds }, rerun.map { it.claimIds })
    }

    @Test
    fun `laps chunk at shifted boundaries so a claim's subsets genuinely differ`() {
        val eligible = (1..12).map { claim("c%02d".format(it)) }
        val subsets = Stage4SystemPrompts.subsets(eligible, size = 4, laps = 2)
        // 3 chunks per lap × 2 laps, no runt tails at 12/4.
        assertEquals(6, subsets.size)
        // No two subsets carry the identical member set — the offset breaks the alignment.
        assertEquals(6, subsets.map { it.claimIds.toSet() }.distinct().size)
    }

    @Test
    fun `a runt tail merges into its predecessor instead of shipping a tiny subset`() {
        val eligible = (1..9).map { claim("c$it") }
        val subsets = Stage4SystemPrompts.subsets(eligible, size = 8, laps = 1)
        // 8 + 1 → the single-claim tail merges: one subset of 9.
        assertEquals(1, subsets.size)
        assertEquals(9, subsets.single().claimIds.size)
    }

    @Test
    fun `degenerate inputs produce no subsets`() {
        assertTrue(Stage4SystemPrompts.subsets(emptyList(), 8, 3).isEmpty())
        assertTrue(Stage4SystemPrompts.subsets(listOf(claim("c1")), 0, 3).isEmpty())
        assertTrue(Stage4SystemPrompts.subsets(listOf(claim("c1")), 8, 0).isEmpty())
    }

    // ---- header ----------------------------------------------------------------------------

    @Test
    fun `placeholders fill and a blank email drops its sentence whole`() {
        val row =
            "You are {{advocate_name}}, advocate for {{subject_name}}. Ground every reply. " +
                "Guests can reach {{subject_name}} at {{subject_email}}."
        val full = Stage4SystemPrompts.resolveHeader(row, "Asha", "asha@x.dev", "Avery")
        assertTrue(full.contains("You are Avery, advocate for Asha."))
        assertTrue(full.contains("reach Asha at asha@x.dev."))

        val noEmail = Stage4SystemPrompts.resolveHeader(row, "Asha", "", "Avery")
        assertTrue(noEmail.contains("Ground every reply."))
        assertFalse(noEmail.contains("{{subject_email}}"))
        assertFalse(noEmail.contains("reach"))
    }

    // ---- compose ---------------------------------------------------------------------------

    @Test
    fun `compose stacks header, rules and fact lines under the fixed labels`() {
        val sp =
            Stage4SystemPrompts.compose(
                header = "HEADER",
                rules = "- rule one\n- rule two",
                claimLines = listOf("\"claim a\" — ASSERT; score 0.90"),
                standingRules = "Standing rules apply.",
            )
        val lines = sp.lines()
        assertEquals("HEADER", lines.first())
        assertTrue(sp.contains(Stage4SystemPrompts.RULES_LABEL))
        assertTrue(sp.contains(Stage4SystemPrompts.FACTS_LABEL))
        assertTrue(sp.indexOf(Stage4SystemPrompts.RULES_LABEL) < sp.indexOf("rule one"))
        assertTrue(sp.indexOf(Stage4SystemPrompts.FACTS_LABEL) < sp.indexOf("claim a"))
        assertTrue(sp.trim().endsWith("Standing rules apply."))

        val noFacts = Stage4SystemPrompts.compose("H", "", emptyList(), "")
        assertTrue(noFacts.contains("- none on record"))
        assertFalse(noFacts.contains(Stage4SystemPrompts.RULES_LABEL))
    }

    @Test
    fun `sysgen template block is subject-neutral`() {
        val template =
            NotebookTemplate(
                id = "t1",
                category = "cat-a",
                title = "Deep dive",
                promptTemplate = "Walk through {{subject}}'s record.",
                formatSpec = FormatSpec(intent = "depth", personaLens = "a recruiter"),
            )
        val block = Stage4SystemPrompts.sysgenTemplateBlock(template)
        assertFalse(block.contains("{{subject}}"))
        assertTrue(block.contains("the subject's record"))
        assertTrue(block.contains("Deep dive"))
        assertTrue(block.contains("a recruiter"))
    }
}

package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.FormatSpec
import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.NotebookTemplate
import ai.vishwakarma.labelling.domain.PersonaDefaults
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.SubjectPersona
import ai.vishwakarma.labelling.domain.SubjectProfile
import ai.vishwakarma.labelling.domain.SubjectProfileDefaults
import ai.vishwakarma.labelling.domain.VoicingPlan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Session-02 context-category activation: the two categories that were the parked Slice-1 risk —
 * `cat-58-temporal-grounding-and-knowledge-cutoff` and `cat-59-environment-and-locale-grounding` —
 * train on **resolved** values rather than literal tokens, end to end from a stored
 * [SubjectProfile] to the assembled generation prompt.
 *
 * This is the offline half of the session's exit gate. The live Stage-4 dry-run over those two
 * categories is owner-run; what is pinned here is the part that does not need an app: the token
 * shapes below are the real ones the 26 loaded rows use (`notebook-templates/categories/cat-58…md`,
 * `cat-59…md` → `notebook-templates/import/notebook_templates.json`) — token mid-sentence, token in
 * a `formatSpec` behaviour, token inside backticks, token in the planned question.
 */
class ContextCategoryActivationTest {

    private val persona =
        PersonaDefaults.resolve(
            stored = SubjectPersona(subjectId = "s1"),
            fallbackAdvocateName = "Avery",
            fallbackPresetId = "warm-storyteller",
        )

    /** The profile a subject fills in on `/training/profile` (VA-140). */
    private val filled =
        SubjectProfileDefaults.resolve(
            SubjectProfile(
                subjectId = "s1",
                country = "IN",
                currency = "INR",
                primaryLanguage = "en-IN",
                knowledgeAsOf = LocalDate.parse("2026-07-15"),
            )
        )

    /**
     * A cat-59 row's shape: `{{locale}}` in the scaffold, in a behaviour bullet, and backticked
     * inside the facets-style prose the library writes.
     */
    private val localeTemplate =
        NotebookTemplate(
            id = "t-59",
            category = "environment-and-locale-grounding",
            title = "Operator locale override framing",
            promptTemplate =
                "In the spirit of this opening, the interlocutor asks: how would he land in " +
                    "{{locale}}? This exchange tests whether {{subject}} is framed in that market.",
            formatSpec =
                FormatSpec(
                    intent = "Tests that an operator {{locale}} override frames the market.",
                    expectedBehaviours =
                        listOf(
                            "Honours the {{locale}} override as the framing market. Keep it short.",
                            "Fires wherever a `{{locale}}` override is present on the config.",
                            "Flags, as of {{knowledge_as_of}}, what is evidenced.",
                        ),
                ),
        )

    /** A cat-58 row's shape: freshness-heavy, and carrying the library's `{{employer}}` slot. */
    private val freshnessTemplate =
        NotebookTemplate(
            id = "t-58",
            category = "temporal-grounding-and-knowledge-cutoff",
            title = "Still at employer freshness",
            promptTemplate =
                "In the spirit of this opening, the interlocutor asks: Is he still at " +
                    "{{employer}} or has he moved on? One line.\nThis exchange tests an as-of " +
                    "fact anchored to {{knowledge_as_of}} for {{subject}}.",
            formatSpec =
                FormatSpec(
                    intent = "Tests the freshness case anchored to {{knowledge_as_of}}.",
                    expectedBehaviours =
                        listOf(
                            "Cites {{knowledge_as_of}} only as the outer edge.",
                            "Reports what {{subject}} held as of {{knowledge_as_of}}.",
                        ),
                ),
        )

    private fun prompt(
        template: NotebookTemplate,
        locale: String,
        knowledgeAsOf: String,
        question: String = "How would he land in that market?",
    ): String {
        val row = Stage4Generation.templateRow(template, "Asha")
        return Stage4Generation.buildPrompt(
            Stage4GenerationRequest(
                subjectName = "Asha",
                question = question,
                plan =
                    VoicingPlan(
                        planId = "plan-1",
                        rowId = 6,
                        voice = "Context-mandatory",
                        hedgeLevel = HedgeLevel.HEDGED,
                        constraints = listOf("F5: voice dates at their stated precision."),
                        sourceClaimIds = listOf("c1"),
                        category = Stage4Category.QA,
                        templateId = template.id,
                    ),
                persona = persona,
                presetStyle = "Warm Storyteller: friendly and personable.",
                evidence = listOf("[c1] \"Led the payments migration\" — score 0.62 (MEDIUM)"),
                promptInstructions = row.instructions,
                promptVersion = row.version,
                promptHash = row.hash,
                locale = locale,
                knowledgeAsOf = knowledgeAsOf,
            )
        )
    }

    /**
     * Every `{{…}}` left in an assembled prompt — the exit gate's "grep for `{{`", as a function.
     */
    private fun unresolved(text: String): Set<String> =
        Regex("\\{\\{[a-z_]+}}").findAll(text).map { it.value }.toSet()

    // ---- cat-59: environment & locale grounding -------------------------------------

    @Test
    fun `a filled profile leaves no locale token anywhere in a cat-59 prompt`() {
        val text = prompt(localeTemplate, filled.locale, filled.knowledgeAsOf.toString())

        assertFalse(text.contains(Stage4Generation.LOCALE_TOKEN), "a literal {{locale}} survived")
        // …resolved to the sentence the subject's own answers produce.
        assertEquals("the India market (INR), primary language en-IN", filled.locale)
        assertTrue(
            text.contains("how would he land in the India market (INR), primary language en-IN?")
        )
        // Including the backticked form the library uses in its facets/gate prose.
        assertTrue(text.contains("`the India market (INR), primary language en-IN`"))
        // And the dedicated context clause the prompt adds on top (profile LLD §4.4).
        assertTrue(text.contains("Context: the subject operates in the India market (INR)"))
    }

    @Test
    fun `a token in the planned guest question resolves too`() {
        val text =
            prompt(
                localeTemplate,
                filled.locale,
                filled.knowledgeAsOf.toString(),
                question = "How does he read for {{locale}} hiring?",
            )
        assertTrue(
            text.contains(
                "How does he read for the India market (INR), primary language en-IN hiring?"
            )
        )
        assertFalse(text.contains(Stage4Generation.LOCALE_TOKEN))
    }

    // ---- cat-58: temporal grounding & knowledge cutoff -------------------------------

    @Test
    fun `a filled profile resolves every freshness token in a cat-58 prompt`() {
        val text = prompt(freshnessTemplate, filled.locale, filled.knowledgeAsOf.toString())

        assertFalse(text.contains(Stage4Generation.KNOWLEDGE_AS_OF_TOKEN))
        assertTrue(text.contains("anchored to 2026-07-15"))
        assertTrue(text.contains("Cites 2026-07-15 only as the outer edge."))
        assertTrue(text.contains("Answer as of 2026-07-15 — do not assert developments"))
    }

    // ---- the blank-profile contract stays intact --------------------------------------

    @Test
    fun `a blank profile drops the token sentences instead of leaving holes`() {
        val blank = SubjectProfileDefaults.resolve(null)
        assertTrue(blank.blank)
        val text = prompt(localeTemplate, blank.locale, "")

        assertFalse(text.contains(Stage4Generation.LOCALE_TOKEN))
        assertFalse(text.contains(Stage4Generation.KNOWLEDGE_AS_OF_TOKEN))
        // No orphaned fragment where a value used to be, and no context clause at all.
        assertFalse(text.contains("operates in ."))
        assertFalse(text.contains("Context: the subject operates in"))
        assertFalse(text.contains("Answer as of"))
        // The bullet whose *second* sentence survived keeps its list marker and that instruction.
        // ("Expected behaviour:" goes with it — the label is part of the dropped first sentence,
        // which is why the marker has to be re-attached at all, Stage4Generation.kt:208-213.)
        assertTrue(text.contains("- Keep it short."), "the surviving clause lost its bullet")
    }

    // ---- what the "grep for {{ → none" gate still catches -------------------------------

    @Test
    fun `authoring placeholders other than the profile pair still survive assembly`() {
        // NOT an endorsement — this documents the residue the session's zero-brace grep will hit,
        // so the next slice inherits a fact rather than a surprise. Two distinct causes:
        //   1. {{employer}} / {{credential}} / {{skill}} … have no resolver anywhere in Stage 4 —
        //      they are authoring slots the library carries (13 {{employer}} in cat-58 alone).
        //   2. {{subject}} IS resolved, but only inside promptTemplate — templateInstructions
        //      (stage4/Stage4Generation.kt:90) replaces it there and nowhere else, so the same
        //      token in a formatSpec behaviour reaches the prompt untouched.
        // Fixing either changes templateInstructions output, hence the template promptHash, hence
        // the GENERATE cache — a calibration event, not a UI session's business.
        val text = prompt(freshnessTemplate, filled.locale, filled.knowledgeAsOf.toString())

        assertEquals(setOf("{{employer}}", "{{subject}}"), unresolved(text))
        assertTrue(text.contains("Is he still at {{employer}}"))
        // Resolved in the scaffold…
        assertTrue(text.contains("as-of fact anchored to 2026-07-15 for Asha."))
        // …but not in the behaviour bullet.
        assertTrue(text.contains("Reports what {{subject}} held as of 2026-07-15."))
    }

    @Test
    fun `the two profile tokens are the only ones a filled profile clears`() {
        val text = prompt(localeTemplate, filled.locale, filled.knowledgeAsOf.toString())
        // cat-59's rows carry {{subject}} in their behaviours as well; the locale pair is gone.
        assertTrue(unresolved(text).none { it == "{{locale}}" || it == "{{knowledge_as_of}}" })
    }
}

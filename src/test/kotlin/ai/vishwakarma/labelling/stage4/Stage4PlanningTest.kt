package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.FormatSpec
import ai.vishwakarma.labelling.domain.NotebookTemplate
import ai.vishwakarma.labelling.domain.PersonaDefaults
import ai.vishwakarma.labelling.domain.PublishedAttestor
import ai.vishwakarma.labelling.domain.PublishedFactStamp
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.persistence.PublishedFactEdge
import ai.vishwakarma.labelling.persistence.SubjectFactRecord
import ai.vishwakarma.labelling.persistence.TimelineLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §9.2 PLAN pins (VA-55): mix weights steer (and normalize), the per-claim fan-out cap holds across
 * categories, near-duplicate questions dedupe, planIds are stable across re-runs, and each
 * category's units land on their §8 rows.
 */
class Stage4PlanningTest {

    private val planning = Stage4Planning()
    private val resolved =
        PersonaDefaults.resolve(
            null,
            fallbackAdvocateName = "Avery",
            fallbackPresetId = "warm-storyteller"
        )
    private val persona = resolved.plannerView()
    private val personaHash = resolved.hash()

    private fun claim(
        id: String,
        score: Double = 0.9,
        text: String = "claim $id",
        favorability: Double? = null,
        factLabel: String? = null,
        attestorKind: String? = null,
    ) =
        EvidencedClaim(
            Claim(
                id = id,
                subjectId = "s1",
                assetId = "a1",
                claimType = ClaimType.EPISODE,
                text = text,
                authenticityScore = score,
                authenticityScoreBare = score,
                authenticitySignals = mapOf("conflict" to 0.0, "independence" to 0.0),
                favorability = favorability,
                factStamp = factLabel?.let { PublishedFactStamp(factId = "f-$id", label = it) },
                attestor = attestorKind?.let { PublishedAttestor(kind = it) },
                scoreRunId = "pub-1",
                publishContractVersion = 2,
            )
        )

    private fun fact(
        factId: String,
        members: List<String>,
        label: String = "fact $factId",
        belief: Double? = 0.9,
        anchored: Boolean = true,
        edges: List<PublishedFactEdge> = emptyList(),
        prev: List<TimelineLink> = emptyList(),
        next: List<TimelineLink> = emptyList(),
    ) =
        SubjectFactRecord(
            factId = factId,
            subjectId = "s1",
            scoreRunId = "pub-1",
            publishedAt = null,
            label = label,
            memberClaimIds = members,
            belief = belief,
            anchored = anchored,
            edges = edges,
            timelinePrev = prev,
            timelineNext = next,
        )

    private fun mix(
        qa: Double = 0.0,
        situational: Double = 0.0,
        multiClaim: Double = 0.0,
        negative: Double = 0.0,
        meta: Double = 0.0,
    ) = AppProperties.Stage4.Mix(qa, situational, multiClaim, negative, meta)

    private fun plan(
        eligible: List<EvidencedClaim>,
        facts: List<SubjectFactRecord> = emptyList(),
        mix: AppProperties.Stage4.Mix = AppProperties.Stage4.Mix(),
        cap: Int = 6,
        threshold: Double = 0.85,
        templates: List<NotebookTemplate> = emptyList(),
    ) =
        planning.plan(
            subjectName = "Asha",
            eligible = eligible,
            facts = facts,
            persona = persona,
            personaHash = personaHash,
            mix = mix,
            maxConversationsPerClaim = cap,
            dedupeJaccardThreshold = threshold,
            templates = templates,
        )

    private fun template(
        id: String,
        category: String,
        title: String = id,
        coverageTarget: Int = 1,
        personaLens: String = "",
    ) =
        NotebookTemplate(
            id = id,
            category = category,
            title = title,
            formatSpec = FormatSpec(personaLens = personaLens),
            coverageTarget = coverageTarget,
        )

    // ---- mix weights (QA-3, QD-5) -----------------------------------------------------------

    @Test
    fun `mix weights steer the fact-driven trio and a zero dial plans nothing in that category`() {
        val eligible = (1..4).map { claim("c$it") }
        val facts = listOf(fact("f1", members = listOf("c1", "c2")))

        val qaOnly = plan(eligible, facts, mix = mix(qa = 1.0))

        val categories = qaOnly.planned.map { it.plan.category }.toSet()
        assertTrue(Stage4Category.QA in categories)
        assertTrue(Stage4Category.SITUATIONAL !in categories)
        assertTrue(Stage4Category.MULTI_CLAIM !in categories)
    }

    @Test
    fun `negative and meta probe banks plan in full regardless of the mix (QD-5)`() {
        val eligible = (1..4).map { claim("c$it") }

        val qaOnly = plan(eligible, mix = mix(qa = 1.0))
        val banksOnly = plan(eligible, mix = mix(negative = 1.0))

        // The behavioral curriculum rides along whatever the fact-driven dials say…
        val qaCategories = qaOnly.planned.map { it.plan.category }.toSet()
        assertTrue(Stage4Category.NEGATIVE in qaCategories)
        assertTrue(Stage4Category.META in qaCategories)
        // …and an all-zero fact-driven trio is a banks-only run.
        val bankCategories = banksOnly.planned.map { it.plan.category }.toSet()
        assertEquals(setOf(Stage4Category.NEGATIVE, Stage4Category.META), bankCategories)
    }

    @Test
    fun `mix weights normalize — a scaled-up dial plans the same set`() {
        val eligible = (1..4).map { claim("c$it") }

        val unit = plan(eligible, mix = mix(qa = 1.0)).planned.map { it.plan.planId }
        val scaled = plan(eligible, mix = mix(qa = 5.0)).planned.map { it.plan.planId }

        assertEquals(unit, scaled)
    }

    // ---- fan-out cap (§9.2) -----------------------------------------------------------------

    @Test
    fun `fan-out cap holds per claim across categories`() {
        val eligible = listOf(claim("c1"))
        // c1 anchors a fact, so it draws the QA unit plus five situational units.
        val facts = listOf(fact("f1", members = listOf("c1")))

        val outcome = plan(eligible, facts, mix = mix(qa = 1.0, situational = 1.0), cap = 2)

        val touchingC1 = outcome.planned.count { "c1" in it.plan.sourceClaimIds }
        assertEquals(2, touchingC1)
        assertTrue(outcome.capped > 0)
    }

    // ---- dedupe (§9.2) ------------------------------------------------------------------------

    @Test
    fun `near-duplicate questions dedupe, keeping the first`() {
        // Nine same-label claims: the QA template ring (QD-3, 8 templates) wraps at the ninth,
        // whose question is then identical to the first and drops. The eight in between carry
        // distinct templates and survive — the dedupe compares substance, not boilerplate.
        val eligible =
            (1..9).map {
                claim("c$it", text = "wording variant $it", factLabel = "Led the migration")
            }

        val outcome = plan(eligible, mix = mix(qa = 1.0))

        val qa = outcome.planned.filter { it.plan.category == Stage4Category.QA }
        assertEquals(8, qa.size)
        assertEquals(1, outcome.deduped)
    }

    // ---- determinism -------------------------------------------------------------------------

    @Test
    fun `planIds are stable across re-runs over the same inputs`() {
        val eligible = (1..3).map { claim("c$it", favorability = if (it == 3) 0.2 else null) }
        val facts =
            listOf(
                fact("f1", members = listOf("c1", "c2")),
                fact("f2", members = listOf("c3"), next = emptyList()),
            )

        val first = plan(eligible, facts).planned.map { it.plan.planId }
        val second = plan(eligible, facts).planned.map { it.plan.planId }

        assertEquals(first, second)
        assertEquals(first.size, first.distinct().size)
    }

    // ---- per-category rows --------------------------------------------------------------------

    @Test
    fun `qa plans carry row, hedge, constraints and source claims`() {
        val outcome = plan(listOf(claim("c1", score = 0.9)), mix = mix(qa = 1.0))

        val p = outcome.planned.single { it.plan.category == Stage4Category.QA }.plan
        assertEquals(1, p.rowId)
        assertEquals(listOf("c1"), p.sourceClaimIds)
        assertTrue(p.constraints.any { it.startsWith("F2") })
        assertTrue(p.constraints.any { it.startsWith("F5") })
    }

    @Test
    fun `floor-failing situational chains degrade to row 10 honest-gap plans`() {
        val eligible = listOf(claim("c1", score = 0.5))
        // Below the MEDIUM band and unanchored: the §10.3 floors cannot hold.
        val facts = listOf(fact("f1", members = listOf("c1"), belief = 0.3, anchored = false))

        val outcome = plan(eligible, facts, mix = mix(situational = 1.0))

        val situational = outcome.planned.filter { it.plan.category == Stage4Category.SITUATIONAL }
        assertTrue(situational.isNotEmpty())
        assertTrue(situational.all { it.plan.rowId == 10 })
    }

    @Test
    fun `situational chains meeting the floors plan as row 9 grounded derivations`() {
        val eligible = listOf(claim("c1", attestorKind = "ISSUER"))
        val facts = listOf(fact("f1", members = listOf("c1"), belief = 0.9, anchored = true))

        val outcome = plan(eligible, facts, mix = mix(situational = 1.0))

        val situational = outcome.planned.filter { it.plan.category == Stage4Category.SITUATIONAL }
        assertTrue(situational.isNotEmpty())
        assertTrue(situational.all { it.plan.rowId == 9 })
    }

    @Test
    fun `succeeds chains become one multi-claim conversation`() {
        val eligible = listOf(claim("c1"), claim("c2"))
        val facts =
            listOf(
                fact("f1", members = listOf("c1"), next = listOf(TimelineLink("f2"))),
                fact("f2", members = listOf("c2"), prev = listOf(TimelineLink("f1"))),
            )

        val outcome = plan(eligible, facts, mix = mix(multiClaim = 1.0))

        val chain = outcome.planned.single { it.plan.category == Stage4Category.MULTI_CLAIM }
        assertEquals(setOf("c1", "c2"), chain.plan.sourceClaimIds.toSet())
    }

    @Test
    fun `negative probes land on the refusal, criticism and honest-gap rows`() {
        val eligible = listOf(claim("c1", favorability = 0.2))

        val outcome = plan(eligible, mix = mix(negative = 1.0))

        val negative = outcome.planned.filter { it.plan.category == Stage4Category.NEGATIVE }
        val rows = negative.map { it.plan.rowId }.toSet()
        assertTrue(13 in rows, "banned-class + proprietary probes plan onto row 13")
        assertTrue(11 in rows, "criticism/integrity-bait probes plan onto row 11")
        assertTrue(12 in rows, "out-of-corpus + comparative probes plan onto row 12")
        // The defensive-advocacy probe cites the unfavorable claim it attacks.
        assertTrue(negative.any { it.plan.rowId == 11 && "c1" in it.plan.sourceClaimIds })
        // QD-3/QD-5 probe classes carry their own constraint lines.
        assertTrue(
            negative.any { p -> p.plan.constraints.any { it.contains("Never rank against") } },
            "comparative bait plans a this-record-only constraint",
        )
        assertTrue(
            negative.any { p -> p.plan.constraints.any { it.contains("system internals") } },
            "proprietary probes plan a refusal-with-identity constraint",
        )
    }

    @Test
    fun `meta bank plans register-shift and identity probes onto row 14`() {
        val outcome = plan(listOf(claim("c1")), mix = mix(meta = 1.0))

        val meta = outcome.planned.filter { it.plan.category == Stage4Category.META }
        assertTrue(meta.isNotEmpty())
        assertTrue(meta.all { it.plan.rowId == 14 })
        assertTrue(
            meta.any { p -> p.plan.constraints.any { it.startsWith("F6") } },
            "audience probes carry the register-shift constraint",
        )
        assertTrue(
            meta.any { p -> p.plan.constraints.any { it.contains("fixed card") } },
            "identity probes carry the plain-disclosure constraint",
        )
    }

    // ---- QD-1: hybrid adjacent-evidence scenarios ----------------------------------------------

    @Test
    fun `adjacent-evidence templates pair a second fact into the question and its chain`() {
        val eligible =
            listOf(claim("c1", attestorKind = "ISSUER"), claim("c2", attestorKind = "ISSUER"))
        val facts =
            listOf(
                fact("f1", members = listOf("c1"), label = "Java platform work", belief = 0.9),
                fact("f2", members = listOf("c2"), label = "Python tooling work", belief = 0.8),
            )

        // Relaxed cap: with one claim per fact, the anchor's six family units would exhaust the
        // default budget before the adjacent-using units arrive — the cap has its own test.
        val outcome = plan(eligible, facts, mix = mix(situational = 1.0), cap = 20)

        val paired =
            outcome.planned.filter {
                it.plan.category == Stage4Category.SITUATIONAL &&
                    it.question.contains("\"Java platform work\"") &&
                    it.question.contains("\"Python tooling work\"")
            }
        assertTrue(paired.isNotEmpty(), "some template pairs the anchor with the adjacent fact")
        // The adjacent fact's evidence joins the chain — its claims are cited sources, so the
        // generation prompt carries its evidence and the §10.3 hedging weighs it.
        assertTrue(paired.any { "c1" in it.plan.sourceClaimIds && "c2" in it.plan.sourceClaimIds })
    }

    @Test
    fun `single-fact subjects fall back to adjacent-free templates`() {
        val eligible = listOf(claim("c1", attestorKind = "ISSUER"))
        val facts = listOf(fact("f1", members = listOf("c1"), label = "Solo fact", belief = 0.9))

        val outcome = plan(eligible, facts, mix = mix(situational = 1.0))

        val situational = outcome.planned.filter { it.plan.category == Stage4Category.SITUATIONAL }
        assertTrue(situational.isNotEmpty())
        assertTrue(situational.none { it.question.contains("{{adjacent}}") })
        assertTrue(situational.none { it.question.contains("\"\"") })
    }

    // ---- VA-88: template-driven planning -----------------------------------------------------

    @Test
    fun `a non-empty library replaces the trio with stamped template units, banks exempt`() {
        val eligible = (1..3).map { claim("c$it") }
        val facts =
            listOf(
                fact("f1", members = listOf("c1", "c2"), label = "payments migration"),
                fact("f2", members = listOf("c3"), label = "B.E. in CS", belief = 0.8),
            )
        val templates =
            listOf(
                template("tpl-a", category = "career-timeline", personaLens = "a recruiter"),
                template("tpl-b", category = "peer", coverageTarget = 2),
            )

        val outcome = plan(eligible, facts, mix = mix(qa = 1.0), templates = templates)

        val templated = outcome.planned.filter { it.plan.templateId != null }
        // tpl-a: 1 slot; tpl-b: 2 slots over 2 anchors — 3 units, no dedupe casualties.
        assertEquals(3, templated.size)
        // Trio replaced: every fact-driven plan is template-stamped; banks ride template-less.
        assertTrue(
            outcome.planned
                .filter { it.plan.templateId == null }
                .all {
                    it.plan.category == Stage4Category.NEGATIVE ||
                        it.plan.category == Stage4Category.META
                }
        )
        // The multi-member anchor governs as a fact group; the single-member one as a claim.
        assertTrue(templated.any { it.plan.category == Stage4Category.MULTI_CLAIM })
        assertTrue(templated.any { it.plan.category == Stage4Category.QA })
        // The persona lens reaches the planned question; the category stamp rides the plan.
        assertTrue(templated.any { it.question.startsWith("As a recruiter: ") })
        assertEquals(
            setOf("career-timeline", "peer"),
            templated.mapNotNull { it.plan.templateCategory }.toSet(),
        )
    }

    @Test
    fun `coverage report counts survivors per category against targets`() {
        val eligible = (1..2).map { claim("c$it") }
        // ONE evidenced anchor: tpl-b's second slot duplicates the first and dedupes away.
        val facts = listOf(fact("f1", members = listOf("c1", "c2"), label = "payments migration"))
        val templates =
            listOf(
                template("tpl-a", category = "career-timeline"),
                template("tpl-b", category = "peer", coverageTarget = 2),
            )

        val outcome = plan(eligible, facts, templates = templates)

        val byCategory = outcome.coverage.associateBy { it.category }
        assertEquals(CategoryCoverage("career-timeline", 1, 1), byCategory["career-timeline"])
        assertEquals(CategoryCoverage("peer", 2, 1), byCategory["peer"])
    }

    @Test
    fun `no evidenced facts plans no template units — every category reports missed`() {
        val eligible = listOf(claim("c1"))
        val templates = listOf(template("tpl-a", category = "career-timeline", coverageTarget = 2))

        val outcome = plan(eligible, facts = emptyList(), templates = templates)

        assertTrue(outcome.planned.none { it.plan.templateId != null })
        assertEquals(listOf(CategoryCoverage("career-timeline", 2, 0)), outcome.coverage)
        // The trio stays replaced even with nothing to fill templates from — no blind fallback.
        assertTrue(outcome.planned.none { it.plan.category == Stage4Category.QA })
    }

    @Test
    fun `template plan ids are stable across re-runs`() {
        val eligible = (1..2).map { claim("c$it") }
        val facts = listOf(fact("f1", members = listOf("c1", "c2")))
        val templates = listOf(template("tpl-a", category = "career-timeline"))

        val first = plan(eligible, facts, templates = templates).planned.map { it.plan.planId }
        val second = plan(eligible, facts, templates = templates).planned.map { it.plan.planId }

        assertEquals(first, second)
    }
}

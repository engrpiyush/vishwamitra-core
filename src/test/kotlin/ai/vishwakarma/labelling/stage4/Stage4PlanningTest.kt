package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimType
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
        )

    // ---- mix weights (QA-3) ---------------------------------------------------------------

    @Test
    fun `mix weights steer category counts and a zero dial plans nothing in that category`() {
        val eligible = (1..4).map { claim("c$it") }
        val facts = listOf(fact("f1", members = listOf("c1", "c2")))

        val qaOnly = plan(eligible, facts, mix = mix(qa = 1.0))

        assertTrue(qaOnly.planned.isNotEmpty())
        assertTrue(qaOnly.planned.all { it.plan.category == Stage4Category.QA })
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
        // Two member claims of the same fact carry the same published label — their planned
        // questions are identical and the second is dropped.
        val eligible =
            listOf(
                claim("c1", text = "led the payments migration", factLabel = "Led the migration"),
                claim("c2", text = "she led that migration", factLabel = "Led the migration"),
            )

        val outcome = plan(eligible, mix = mix(qa = 1.0))

        assertEquals(1, outcome.planned.size)
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

        val p = outcome.planned.single().plan
        assertEquals(1, p.rowId)
        assertEquals(Stage4Category.QA, p.category)
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

        assertTrue(outcome.planned.isNotEmpty())
        assertTrue(outcome.planned.all { it.plan.rowId == 10 })
    }

    @Test
    fun `situational chains meeting the floors plan as row 9 grounded derivations`() {
        val eligible = listOf(claim("c1", attestorKind = "ISSUER"))
        val facts = listOf(fact("f1", members = listOf("c1"), belief = 0.9, anchored = true))

        val outcome = plan(eligible, facts, mix = mix(situational = 1.0))

        assertTrue(outcome.planned.isNotEmpty())
        assertTrue(outcome.planned.all { it.plan.rowId == 9 })
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

        val chain = outcome.planned.single()
        assertEquals(Stage4Category.MULTI_CLAIM, chain.plan.category)
        assertEquals(setOf("c1", "c2"), chain.plan.sourceClaimIds.toSet())
    }

    @Test
    fun `negative probes land on the refusal, criticism and honest-gap rows`() {
        val eligible = listOf(claim("c1", favorability = 0.2))

        val outcome = plan(eligible, mix = mix(negative = 1.0))

        val rows = outcome.planned.map { it.plan.rowId }.toSet()
        assertTrue(13 in rows, "banned-class probes plan onto row 13")
        assertTrue(11 in rows, "criticism/integrity-bait probes plan onto row 11")
        assertTrue(12 in rows, "out-of-corpus probes plan onto row 12")
        assertTrue(outcome.planned.all { it.plan.category == Stage4Category.NEGATIVE })
        // The defensive-advocacy probe cites the unfavorable claim it attacks.
        assertTrue(outcome.planned.any { it.plan.rowId == 11 && "c1" in it.plan.sourceClaimIds })
    }

    @Test
    fun `meta templates plan onto row 14 in the META category`() {
        val outcome = plan(listOf(claim("c1")), mix = mix(meta = 1.0))

        assertTrue(outcome.planned.isNotEmpty())
        assertTrue(outcome.planned.all { it.plan.rowId == 14 })
        assertTrue(outcome.planned.all { it.plan.category == Stage4Category.META })
    }
}

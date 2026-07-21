package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimOrigin
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.DeclaredType
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
import kotlin.test.assertNotEquals
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
        claimType: ClaimType = ClaimType.EPISODE,
        origin: ClaimOrigin? = null,
        declaredType: String? = null,
        edgeCounts: Map<String, Int>? = null,
    ) =
        EvidencedClaim(
            Claim(
                id = id,
                subjectId = "s1",
                assetId = "a1",
                claimType = claimType,
                text = text,
                authenticityScore = score,
                authenticityScoreBare = score,
                authenticitySignals = mapOf("conflict" to 0.0, "independence" to 0.0),
                favorability = favorability,
                factStamp = factLabel?.let { PublishedFactStamp(factId = "f-$id", label = it) },
                attestor = attestorKind?.let { PublishedAttestor(kind = it) },
                origin = origin,
                declaredType = declaredType,
                edgeCounts = edgeCounts,
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
        requiredClaimTypes: List<ClaimType> = emptyList(),
        requiredDeclaredTypes: List<String> = emptyList(),
    ) =
        NotebookTemplate(
            id = id,
            category = category,
            title = title,
            formatSpec = FormatSpec(personaLens = personaLens),
            coverageTarget = coverageTarget,
            requiredClaimTypes = requiredClaimTypes,
            requiredDeclaredTypes = requiredDeclaredTypes,
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
    fun `evidence gate skips a template when no anchor carries a required claim type`() {
        // Subject has only a SKILL fact — no WEAKNESS anywhere in the ledger.
        val eligible = listOf(claim("c1", claimType = ClaimType.SKILL))
        val facts = listOf(fact("f1", members = listOf("c1"), label = "backend depth"))
        val templates =
            listOf(
                // Gated: must draw against a WEAKNESS anchor, of which there are none.
                template(
                    "tpl-weak",
                    category = "weaknesses",
                    requiredClaimTypes = listOf(ClaimType.WEAKNESS),
                ),
                // Ungated: fires against any anchor (legacy behaviour).
                template("tpl-open", category = "career-timeline"),
            )

        val outcome = plan(eligible, facts, templates = templates)

        // The gated template plans NOTHING — this is the invented-defect guard.
        assertTrue(outcome.planned.none { it.plan.templateCategory == "weaknesses" })
        assertEquals(
            CategoryCoverage("weaknesses", 1, 0),
            outcome.coverage.first { it.category == "weaknesses" }
        )
        // The ungated template still fires against the SKILL fact.
        assertTrue(outcome.planned.any { it.plan.templateCategory == "career-timeline" })
    }

    @Test
    fun `evidence gate draws only the anchor that satisfies the required claim type`() {
        // Two facts; only f-weak carries the WEAKNESS claim the template requires.
        val eligible =
            listOf(
                claim("c1", claimType = ClaimType.SKILL),
                claim("c2", claimType = ClaimType.WEAKNESS),
            )
        val facts =
            listOf(
                fact("f-skill", members = listOf("c1"), label = "backend depth"),
                fact("f-weak", members = listOf("c2"), label = "owns a gap", belief = 0.8),
            )
        val templates =
            listOf(
                template(
                    "tpl-weak",
                    category = "weaknesses",
                    requiredClaimTypes = listOf(ClaimType.WEAKNESS),
                )
            )

        val outcome = plan(eligible, facts, templates = templates)

        val weak = outcome.planned.filter { it.plan.templateCategory == "weaknesses" }
        assertEquals(1, weak.size)
        // It drew the weakness claim, never the skill one — the gate constrains the anchor.
        assertEquals(listOf("c2"), weak.single().plan.sourceClaimIds)
    }

    // ---- SubjectProfile §3.4/§3.7: the fine declaredType gate (VA-148, Worked Example 1) ----

    @Test
    fun `the fine gate draws the declared aspiration, not an ordinary extracted VALUE`() {
        // The cat-27 row-02 shape: VALUE-gated coarsely, but the decline must rest on a stated
        // aspiration. Two VALUE facts — one declared aspiration, one ordinary extracted value.
        val eligible =
            listOf(
                claim(
                    "c-asp",
                    claimType = ClaimType.VALUE,
                    origin = ClaimOrigin.SUBJECT_DECLARED,
                    declaredType = DeclaredType.STATED_ASPIRATION,
                ),
                claim("c-val", claimType = ClaimType.VALUE),
            )
        val facts =
            listOf(
                fact(
                    "f-asp",
                    members = listOf("c-asp"),
                    label = "staff IC, not management",
                    belief = 0.5
                ),
                fact("f-val", members = listOf("c-val"), label = "values clean code", belief = 0.9),
            )
        val templates =
            listOf(
                template(
                    "cat-27-row-02",
                    category = "role-fit-and-respectful-decline",
                    coverageTarget = 4,
                    requiredClaimTypes = listOf(ClaimType.VALUE),
                    requiredDeclaredTypes =
                        listOf(
                            DeclaredType.STATED_ASPIRATION,
                            DeclaredType.STATED_TRACK_PREFERENCE,
                        ),
                )
            )

        val outcome = plan(eligible, facts, templates = templates)

        val drawn =
            outcome.planned.filter { it.plan.templateCategory == "role-fit-and-respectful-decline" }
        assertTrue(drawn.isNotEmpty(), "the row must draw the declared aspiration")
        // Every drawn unit rests on the declared claim, never the ordinary extracted VALUE.
        assertTrue(drawn.all { "c-asp" in it.plan.sourceClaimIds })
        assertTrue(drawn.none { "c-val" in it.plan.sourceClaimIds })
    }

    @Test
    fun `the fine gate draws nothing when the subject declared no matching direction`() {
        // The row's own gate-failing behaviour: only an extracted VALUE exists, so the row is
        // undrawable and reports `missed` rather than declining on an invented direction (§3.7).
        val eligible = listOf(claim("c-val", claimType = ClaimType.VALUE))
        val facts = listOf(fact("f-val", members = listOf("c-val"), label = "values clean code"))
        val templates =
            listOf(
                template(
                    "cat-27-row-02",
                    category = "role-fit-and-respectful-decline",
                    requiredClaimTypes = listOf(ClaimType.VALUE),
                    requiredDeclaredTypes = listOf(DeclaredType.STATED_ASPIRATION),
                )
            )

        val outcome = plan(eligible, facts, templates = templates)

        assertTrue(
            outcome.planned.none { it.plan.templateCategory == "role-fit-and-respectful-decline" }
        )
        assertEquals(
            CategoryCoverage("role-fit-and-respectful-decline", 1, 0),
            outcome.coverage.first { it.category == "role-fit-and-respectful-decline" },
        )
    }

    @Test
    fun `a declaredType on an extracted claim never satisfies the fine gate`() {
        // Belt and braces: matching turns on `declared`, not on a stray declaredType string, so a
        // corrupt extracted claim carrying a declaredType cannot masquerade as a declaration.
        val eligible =
            listOf(
                claim(
                    "c-fake",
                    claimType = ClaimType.VALUE,
                    origin = ClaimOrigin.EXTRACTED,
                    declaredType = DeclaredType.STATED_ASPIRATION,
                )
            )
        val facts = listOf(fact("f-fake", members = listOf("c-fake"), label = "x"))
        val templates =
            listOf(
                template(
                    "cat-27-row-02",
                    category = "role-fit-and-respectful-decline",
                    requiredClaimTypes = listOf(ClaimType.VALUE),
                    requiredDeclaredTypes = listOf(DeclaredType.STATED_ASPIRATION),
                )
            )

        val outcome = plan(eligible, facts, templates = templates)

        assertTrue(
            outcome.planned.none { it.plan.templateCategory == "role-fit-and-respectful-decline" }
        )
    }

    // ---- SubjectProfile §10 / OD-9: a declared boundary is never spoken as evidence ---------

    @Test
    fun `a declared do-not-discuss boundary never satisfies a coarse WEAKNESS gate`() {
        // VA-145/146 materialise a do-not-discuss topic as a ClaimType.WEAKNESS claim (declaredType
        // =
        // subject-declared-boundary, favorability 0.5). The cat-13 "weaknesses / The Standard Ask"
        // row gates coarsely on requiredClaimTypes=[WEAKNESS] with no fine requiredDeclaredTypes,
        // so
        // without the boundary guard the claim is an eligible development-area anchor and the
        // notebook
        // answers "his biggest weakness?" by voicing the exact protected topic (§10, OD-9). It must
        // draw NOTHING — a boundary is enforced only at serving, never spoken as evidence.
        val eligible =
            listOf(
                claim(
                    "c-bound",
                    score = 0.5,
                    claimType = ClaimType.WEAKNESS,
                    favorability = 0.5,
                    origin = ClaimOrigin.SUBJECT_DECLARED,
                    declaredType = DeclaredType.BOUNDARY,
                )
            )
        val facts =
            listOf(
                fact(
                    "f-bound",
                    members = listOf("c-bound"),
                    label = "Prefers not to discuss health and medical history.",
                    belief = 0.5,
                )
            )
        val templates =
            listOf(
                template(
                    "cat-13-standard-ask",
                    category = "weaknesses",
                    requiredClaimTypes = listOf(ClaimType.WEAKNESS),
                )
            )

        val outcome = plan(eligible, facts, templates = templates)

        assertTrue(
            outcome.planned.none { it.plan.templateCategory == "weaknesses" },
            "a declared boundary must never be drawn as a development area",
        )
        assertEquals(
            CategoryCoverage("weaknesses", 1, 0),
            outcome.coverage.first { it.category == "weaknesses" },
        )
    }

    @Test
    fun `a declared boundary is not drawn by an ungated template either`() {
        // The boundary fact is a fact like any other, so an ungated template (no
        // requiredClaimTypes)
        // would happily anchor on it and voice the protected topic. The guard covers the whole
        // anchor
        // pool, not just the coarse WEAKNESS gate: here the boundary is the highest-belief fact, so
        // without the fix it lands in the first slot; the ordinary SKILL fact draws in its place.
        val eligible =
            listOf(
                claim("c-skill", claimType = ClaimType.SKILL),
                claim(
                    "c-bound",
                    score = 0.5,
                    claimType = ClaimType.WEAKNESS,
                    favorability = 0.5,
                    origin = ClaimOrigin.SUBJECT_DECLARED,
                    declaredType = DeclaredType.BOUNDARY,
                ),
            )
        val facts =
            listOf(
                fact("f-skill", members = listOf("c-skill"), label = "backend depth", belief = 0.8),
                fact(
                    "f-bound",
                    members = listOf("c-bound"),
                    label = "Prefers not to discuss health and medical history.",
                    belief = 0.9,
                ),
            )
        val templates = listOf(template("tpl-open", category = "career-timeline"))

        val outcome = plan(eligible, facts, templates = templates)

        val drawn = outcome.planned.filter { it.plan.templateId != null }
        assertTrue(drawn.isNotEmpty(), "the ordinary fact must still draw")
        assertTrue(
            drawn.none { "c-bound" in it.plan.sourceClaimIds },
            "the boundary claim must never anchor or ride into an ungated template unit",
        )
    }

    @Test
    fun `a boundary is drawn only when a template opts it in via requiredDeclaredTypes`() {
        // The escape hatch the fix preserves: a purpose-built refusal trainer that names BOUNDARY
        // in
        // its fine gate still reaches the boundary, so the guard blocks mis-drawing, not the
        // boundary.
        val eligible =
            listOf(
                claim(
                    "c-bound",
                    score = 0.5,
                    claimType = ClaimType.WEAKNESS,
                    favorability = 0.5,
                    origin = ClaimOrigin.SUBJECT_DECLARED,
                    declaredType = DeclaredType.BOUNDARY,
                )
            )
        val facts =
            listOf(
                fact(
                    "f-bound",
                    members = listOf("c-bound"),
                    label = "Prefers not to discuss health and medical history.",
                    belief = 0.5,
                )
            )
        val templates =
            listOf(
                template(
                    "cat-40-boundary-trainer",
                    category = "contact-and-pii-gating",
                    requiredClaimTypes = listOf(ClaimType.WEAKNESS),
                    requiredDeclaredTypes = listOf(DeclaredType.BOUNDARY),
                )
            )

        val outcome = plan(eligible, facts, templates = templates)

        val drawn = outcome.planned.filter { it.plan.templateCategory == "contact-and-pii-gating" }
        assertTrue(drawn.isNotEmpty(), "an opted-in template must still reach the boundary")
        assertTrue(drawn.all { "c-bound" in it.plan.sourceClaimIds })
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

    // ---- the anchor/evidence invariant: {{fact}} always backs a claim in the unit (plan 0ab5f1a5)

    @Test
    fun `a template question never quotes a fact label whose backing claim the member cap dropped`() {
        // The UCEC601 shape (plan 0ab5f1a5): a fact with more members than MAX_TEMPLATE_CLAIMS (4),
        // where the claim whose label equals the fact label sorts LAST by id and is dropped by the
        // `.take(4)` cap. The planned question must quote a label backed by a claim actually in the
        // unit — never the dropped one, or the drafter denies a fact it was never handed.
        val anchorLabel = "Completed the theory component of UCEC601 with a grade of 7 out of 10."
        val eligible =
            listOf(
                claim("c1", factLabel = "UCEC604 laboratory component"),
                claim("c2", factLabel = "SGPI VII standing"),
                claim("c3", factLabel = "SGPI VI standing"),
                claim("c4", factLabel = "UCEC603 coursework"),
                // Sorts last by id; only this member bears the fact label, and the cap drops it.
                claim("c5", factLabel = anchorLabel),
            )
        val facts =
            listOf(fact("f1", members = listOf("c1", "c2", "c3", "c4", "c5"), label = anchorLabel))
        val templates = listOf(template("tpl-a", category = "career-timeline"))

        val outcome = plan(eligible, facts, templates = templates)

        val unit = outcome.planned.single { it.plan.templateId != null }
        // The label-bearing claim was capped out of the unit…
        assertTrue("c5" !in unit.plan.sourceClaimIds)
        // …so the question must not quote its label; the quoted label backs a drawn claim.
        val quoted = Regex("\"([^\"]*)\"").find(unit.question)!!.groupValues[1]
        assertNotEquals(anchorLabel, quoted)
        val drawnLabels =
            eligible
                .filter { it.claim.id in unit.plan.sourceClaimIds }
                .map { it.claim.factStamp!!.label }
        assertTrue(quoted in drawnLabels, "quoted label \"$quoted\" must back a claim in the unit")
    }

    @Test
    fun `a multi-claim group question falls back to a drawn member when the labelled claim is out`() {
        // The fact's label is the label of a claim that never made the eligible slice, so it is
        // absent from the unit. The question must fall back to a drawn member's label rather than
        // quoting evidence the drafter was never handed.
        val eligible =
            listOf(
                claim("c1", score = 0.7, factLabel = "backend migration"),
                claim("c2", score = 0.9, factLabel = "payments rollout"),
            )
        // c-gone is the label-bearing member but is absent from `eligible` (ineligible / filtered).
        val facts =
            listOf(
                fact(
                    "f1",
                    members = listOf("c-gone", "c1", "c2"),
                    label = "led the compiler rewrite",
                )
            )

        val outcome = plan(eligible, facts, mix = mix(multiClaim = 1.0))

        val group = outcome.planned.single { it.plan.category == Stage4Category.MULTI_CLAIM }
        assertTrue("c-gone" !in group.plan.sourceClaimIds)
        val quoted = Regex("\"([^\"]*)\"").find(group.question)!!.groupValues[1]
        assertNotEquals("led the compiler rewrite", quoted)
        // Fallback is the best drawn member — highest score, so c2's label.
        assertEquals("payments rollout", quoted)
    }

    @Test
    fun `a fact group question never quotes an excluded row-5 member even when it scores highest`() {
        // planFactGroup drops a row-5 (unexplained CONFIRMED conflict) member from the group's
        // sourceClaimIds — the evidence block the drafter sees — but row 5 is scored BEFORE the
        // bands, so such a claim can still hold the top authenticity score. Drawing {{fact}} from
        // the
        // full member list would then quote that excluded claim's label, a fact with no evidence
        // line
        // — the same ungrounded-fact denial the anchor-cap case caused. The label must come from a
        // member that survives into the block.
        val eligible =
            listOf(
                // Excluded row-5 member, yet the highest scorer — the fallback's default pick.
                claim(
                    "c-excluded",
                    score = 0.95,
                    factLabel = "Alpha",
                    edgeCounts = mapOf("contradictsConfirmed" to 1),
                ),
                claim("c-kept", score = 0.6, factLabel = "Beta"),
            )
        // The fact label matches no member, so branch 1 falls through to the score-ranked fallback.
        val facts = listOf(fact("f1", members = listOf("c-excluded", "c-kept"), label = "Gamma"))

        val outcome = plan(eligible, facts, mix = mix(multiClaim = 1.0))

        val group = outcome.planned.single { it.plan.category == Stage4Category.MULTI_CLAIM }
        // The row-5 member is excluded from the evidence the drafter is handed…
        assertTrue("c-excluded" !in group.plan.sourceClaimIds)
        assertTrue("c-kept" in group.plan.sourceClaimIds)
        // …so the quoted label is the surviving member's, never the excluded high-scorer's.
        val quoted = Regex("\"([^\"]*)\"").find(group.question)!!.groupValues[1]
        assertEquals("Beta", quoted)
        assertNotEquals("Alpha", quoted)
    }
}

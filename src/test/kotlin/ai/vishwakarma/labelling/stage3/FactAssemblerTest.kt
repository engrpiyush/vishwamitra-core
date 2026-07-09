package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val props = AppProperties.Stage3()

private fun claim(
    id: String,
    type: String = "EPISODE",
    text: String = "text of $id",
    date: String? = null,
    source: String = "SELF",
    mentions: List<String> = emptyList(),
) = ClaimToAssemble(id, type, text, date, source, mentions)

private fun judgedRecord(
    a: String,
    b: String,
    relation: JudgeRelation,
    confidence: Double = 0.8,
    temporalNote: String? = null,
    ctxJudged: Boolean = false,
    ctxRelation: JudgeRelation? = null,
    ctxConfidence: Double? = null,
    ctxRelevant: Boolean? = null,
    shared: List<String> = emptyList(),
) =
    JudgedPairRecord(
        pair = ClaimPair.of(a, b),
        relation = relation,
        confidence = confidence,
        votesJson = """{"$relation":4}""",
        rationale = "judged $a vs $b",
        temporalNote = temporalNote,
        ctxJudged = ctxJudged,
        ctxRelation = ctxRelation,
        ctxConfidence = ctxConfidence,
        ctxExplanationRelevant = ctxRelevant,
        judgeModel = "test-judge",
        promptHash = "test:1:hash",
        sharedEntities = shared,
    )

class FactAssemblerTest {

    // ---- clustering + exemplar (§11.7) -----------------------------------------------

    @Test
    fun `repeats-connected claims form one fact with the documentary exemplar`() {
        val outcome =
            FactAssembler.assemble(
                listOf(
                    claim("c1", source = "DOCUMENTARY", text = "completed B.E. CS", date = "2016"),
                    claim("c2", source = "SELF", text = "graduated in CS from University X"),
                    claim("c3", source = "SELF"),
                    claim("c4", source = "SELF"),
                ),
                repeats = listOf(ClaimPair.of("c1", "c2"), ClaimPair.of("c2", "c3")),
                judged = emptyList(),
                props = props,
            )
        assertEquals(2, outcome.facts.size)
        val cluster = outcome.facts.first { it.factId == "fact:c1" }
        assertEquals(listOf("c1", "c2", "c3"), cluster.memberClaimIds)
        assertEquals("c1", cluster.exemplarClaimId) // DOCUMENTARY outranks SELF
        assertTrue(cluster.anchored)
        assertEquals("completed B.E. CS", cluster.label)
        val singleton = outcome.facts.first { it.factId == "fact:c4" }
        assertEquals(listOf("c4"), singleton.memberClaimIds)
        assertFalse(singleton.anchored)
        assertEquals(2L, outcome.counters.facts)
    }

    @Test
    fun `exemplar ties break by longer text then claimId`() {
        val outcome =
            FactAssembler.assemble(
                listOf(
                    claim("c1", text = "short"),
                    claim("c2", text = "a much longer statement of the same thing"),
                ),
                repeats = listOf(ClaimPair.of("c1", "c2")),
                judged = emptyList(),
                props = props,
            )
        assertEquals("c2", outcome.facts.single().exemplarClaimId)
    }

    // ---- fact kinds + slots (§11.7 as-built pattern table) -----------------------------

    @Test
    fun `fact kinds reproduce the worked example`() {
        val outcome =
            FactAssembler.assemble(
                listOf(
                    // F1: dated completion (EPISODE × INSTITUTION) — EVENT, not enrollment.
                    claim(
                        "c1",
                        "EPISODE",
                        "completed B.E. CS at University X",
                        "2016",
                        "DOCUMENTARY",
                        listOf("INSTITUTION", "CREDENTIAL")
                    ),
                    // F2: skill claim — volatile TIMELESS.
                    claim(
                        "c3",
                        "SKILL",
                        "is a strong Kotlin engineer",
                        null,
                        "ENDORSEMENT",
                        listOf("SKILL")
                    ),
                    // Employment state: IDENTITY × ORG.
                    claim("c7", "IDENTITY", "works at Google", "2024", "SELF", listOf("ORG")),
                    // Pure-affiliation episode: EPISODE × ORG only — also EMPLOYER STATE.
                    claim("c8", "EPISODE", "worked at Infosys", "2016", "SELF", listOf("ORG")),
                    // Project episode: EPISODE × ORG + PROJECT — an EVENT, not employment.
                    claim(
                        "c5",
                        "EPISODE",
                        "led the payments migration",
                        "2019",
                        "SELF",
                        listOf("ORG", "PROJECT")
                    ),
                ),
                repeats = emptyList(),
                judged = emptyList(),
                props = props,
            )
        val byId = outcome.facts.associateBy { it.exemplarClaimId }
        assertEquals("EVENT", byId["c1"]!!.factKind)
        assertNull(byId["c1"]!!.slot)
        assertEquals("TIMELESS", byId["c3"]!!.factKind)
        assertEquals("STATE", byId["c7"]!!.factKind)
        assertEquals("EMPLOYER", byId["c7"]!!.slot)
        assertEquals("STATE", byId["c8"]!!.factKind)
        assertEquals("EMPLOYER", byId["c8"]!!.slot)
        assertEquals("EVENT", byId["c5"]!!.factKind)
        assertEquals(2L, outcome.counters.factsState)
        assertEquals(2L, outcome.counters.factsEvent)
        assertEquals(1L, outcome.counters.factsTimeless)
    }

    @Test
    fun `interval inference takes min-max dates at the coarsest precision`() {
        val outcome =
            FactAssembler.assemble(
                listOf(
                    claim("c1", date = "2016-06-01"),
                    claim("c2", date = "2016"),
                    claim("c3"),
                ),
                repeats = listOf(ClaimPair.of("c1", "c2"), ClaimPair.of("c2", "c3")),
                judged = emptyList(),
                props = props,
            )
        val fact = outcome.facts.single()
        assertEquals("2016", fact.validFrom)
        assertEquals("2016-06-01", fact.validTo)
        assertEquals("YEAR", fact.datePrecision)
    }

    // ---- the temporal overlap gate + SUCCEEDS (§11.7) ---------------------------------

    private fun employerCorpus(googleDate: String, infosysDate: String) =
        listOf(
            claim("c7", "IDENTITY", "works at Google", googleDate, "SELF", listOf("ORG")),
            claim("c8", "EPISODE", "worked at Infosys", infosysDate, "SELF", listOf("ORG")),
        )

    @Test
    fun `disjoint employer facts sequence instead of contradicting`() {
        val outcome =
            FactAssembler.assemble(
                employerCorpus(googleDate = "2024", infosysDate = "2016"),
                repeats = emptyList(),
                judged =
                    listOf(judgedRecord("c7", "c8", JudgeRelation.CONTRADICTS, confidence = 0.8)),
                props = props,
            )
        assertTrue(outcome.edges.isEmpty()) // the gate dropped the judged CONTRADICTS
        assertEquals(1L, outcome.counters.contradictionsGated)
        val succeeds = outcome.succeeds.single()
        assertEquals("fact:c8", succeeds.fromFactId) // Infosys (2016) precedes Google (2024)
        assertEquals("fact:c7", succeeds.toFactId)
        assertEquals("EMPLOYER", succeeds.slot)
        assertNull(succeeds.gapDays) // YEAR precision — a day count would be a guess
    }

    @Test
    fun `overlapping intervals keep the contradiction and lay no succeeds`() {
        val outcome =
            FactAssembler.assemble(
                employerCorpus(googleDate = "2016-06", infosysDate = "2016"),
                repeats = emptyList(),
                judged =
                    listOf(judgedRecord("c7", "c8", JudgeRelation.CONTRADICTS, confidence = 0.8)),
                props = props,
            )
        val edge = outcome.edges.single()
        assertEquals("CONTRADICTS", edge.relation)
        assertEquals(true, edge.temporalOverlap)
        assertEquals("PROPOSED", edge.reviewStatus)
        assertEquals(0.8, edge.severity)
        assertTrue(outcome.succeeds.isEmpty())
        assertEquals(0L, outcome.counters.contradictionsGated)
    }

    @Test
    fun `day-precision boundaries compute the succeeds gap`() {
        val outcome =
            FactAssembler.assemble(
                employerCorpus(googleDate = "2016-02-01", infosysDate = "2016-01-01"),
                repeats = emptyList(),
                judged = emptyList(),
                props = props,
            )
        assertEquals(31L, outcome.succeeds.single().gapDays)
    }

    @Test
    fun `undated contradictions survive the gate only with an asserting temporal note`() {
        fun outcomeWith(note: String?) =
            FactAssembler.assemble(
                listOf(
                    claim("c5", "EPISODE", "led the migration"),
                    claim("c6", "EPISODE", "Vikram led the migration"),
                ),
                repeats = emptyList(),
                judged =
                    listOf(
                        judgedRecord(
                            "c5",
                            "c6",
                            JudgeRelation.CONTRADICTS,
                            confidence = 0.7,
                            temporalNote = note,
                        )
                    ),
                props = props,
            )
        assertTrue(outcomeWith(null).edges.isEmpty())
        assertEquals(1L, outcomeWith(null).counters.contradictionsGated)
        val kept = outcomeWith("both claims describe the same migration")
        val edge = kept.edges.single()
        assertEquals("CONTRADICTS", edge.relation)
        assertEquals(false, edge.temporalOverlap) // kept via the note, not via dates
    }

    @Test
    fun `day-dated events in the same year still overlap at event granularity`() {
        // Two day-precision accounts of one 2019 episode: strict intervals would be disjoint,
        // but episodes conflict at year scale — the gate must keep this.
        val outcome =
            FactAssembler.assemble(
                listOf(
                    claim("c5", "EPISODE", "led the migration", "2019-06-01"),
                    claim("c6", "EPISODE", "Vikram led the migration", "2019-08-01"),
                ),
                repeats = emptyList(),
                judged =
                    listOf(judgedRecord("c5", "c6", JudgeRelation.CONTRADICTS, confidence = 0.9)),
                props = props,
            )
        assertEquals(true, outcome.edges.single().temporalOverlap)
        assertEquals(0L, outcome.counters.contradictionsGated)
    }

    @Test
    fun `event facts in the same year keep the contradiction`() {
        // The worked example's F3/F4: both EVENT 2019 — a genuine same-span conflict.
        val outcome =
            FactAssembler.assemble(
                listOf(
                    claim(
                        "c5",
                        "EPISODE",
                        "led the payments migration",
                        "2019",
                        "SELF",
                        listOf("PROJECT")
                    ),
                    claim(
                        "c6",
                        "EPISODE",
                        "the migration was led by Vikram",
                        "2019",
                        "ENDORSEMENT",
                        listOf("PROJECT", "PERSON")
                    ),
                ),
                repeats = emptyList(),
                judged =
                    listOf(judgedRecord("c5", "c6", JudgeRelation.CONTRADICTS, confidence = 0.72)),
                props = props,
            )
        val edge = outcome.edges.single()
        assertEquals(0.72, edge.confidence)
        assertEquals(true, edge.temporalOverlap)
    }

    // ---- edge lifting (§11.7) ----------------------------------------------------------

    @Test
    fun `lifted edges carry max confidence and every contributing pair`() {
        // Two claim pairs between the same two facts: (c1,c3) at 0.6 and (c2,c3) at 0.9.
        val outcome =
            FactAssembler.assemble(
                listOf(
                    claim("c1", "EPISODE", "shipped the feature", "2019"),
                    claim("c2", "EPISODE", "delivered the feature", "2019"),
                    claim("c3", "EPISODE", "reviewed the feature launch", "2019"),
                ),
                repeats = listOf(ClaimPair.of("c1", "c2")),
                judged =
                    listOf(
                        judgedRecord(
                            "c1",
                            "c3",
                            JudgeRelation.CORROBORATES,
                            0.6,
                            shared = listOf("the feature")
                        ),
                        judgedRecord(
                            "c2",
                            "c3",
                            JudgeRelation.CORROBORATES,
                            0.9,
                            shared = listOf("the launch")
                        ),
                    ),
                props = props,
            )
        val edge = outcome.edges.single()
        assertEquals("CORROBORATES", edge.relation)
        assertEquals(0.9, edge.confidence)
        assertEquals(listOf("c1↔c3", "c2↔c3"), edge.contributingPairs)
        assertEquals(listOf("the feature", "the launch"), edge.viaEntities)
        assertEquals("judged c2 vs c3", edge.rationale) // the max-confidence contributor speaks
        assertNull(edge.reviewStatus) // queue fields are CONTRADICTS-only
        assertNull(edge.severity)
    }

    @Test
    fun `ctx verdicts ride along and an affirmed relevance marks the edge explained`() {
        val outcome =
            FactAssembler.assemble(
                listOf(
                    claim("c5", "EPISODE", "led the migration", "2019"),
                    claim("c6", "EPISODE", "Vikram led it", "2019"),
                ),
                repeats = emptyList(),
                judged =
                    listOf(
                        judgedRecord(
                            "c5",
                            "c6",
                            JudgeRelation.CONTRADICTS,
                            confidence = 0.72,
                            ctxJudged = true,
                            ctxRelation = JudgeRelation.NEUTRAL,
                            ctxConfidence = 0.36,
                            ctxRelevant = true,
                        )
                    ),
                props = props,
            )
        val edge = outcome.edges.single()
        assertTrue(edge.withContext)
        assertEquals("NEUTRAL", edge.ctxRelation)
        assertEquals(0.36, edge.ctxConfidence)
        assertTrue(edge.explained) // relevance affirmed → leaves the §11.10 queue
    }

    @Test
    fun `same-fact judged pairs drop with a counter and lift nothing`() {
        val outcome =
            FactAssembler.assemble(
                listOf(claim("c1"), claim("c2")),
                repeats = listOf(ClaimPair.of("c1", "c2")),
                judged = listOf(judgedRecord("c1", "c2", JudgeRelation.CORROBORATES)),
                props = props,
            )
        assertTrue(outcome.edges.isEmpty())
        assertEquals(1L, outcome.counters.sameFactPairsDropped)
    }

    @Test
    fun `neutral verdicts lift nothing`() {
        val outcome =
            FactAssembler.assemble(
                listOf(claim("c1"), claim("c2")),
                repeats = emptyList(),
                judged = listOf(judgedRecord("c1", "c2", JudgeRelation.NEUTRAL)),
                props = props,
            )
        assertTrue(outcome.edges.isEmpty())
    }

    // ---- determinism (I5 groundwork) ---------------------------------------------------

    @Test
    fun `assembly is bit-identical under input shuffle`() {
        val claims =
            listOf(
                claim("c1", "IDENTITY", "works at Google", "2024", "SELF", listOf("ORG")),
                claim("c2", "EPISODE", "worked at Infosys", "2016", "SELF", listOf("ORG")),
                claim("c3", "SKILL", "Kotlin expert", null, "ENDORSEMENT", listOf("SKILL")),
                claim("c4", "SKILL", "strong at Kotlin", null, "SELF", listOf("SKILL")),
                claim("c5", "EPISODE", "led the migration", "2019", "SELF", listOf("PROJECT")),
                claim("c6", "EPISODE", "Vikram led it", "2019", "ENDORSEMENT", listOf("PROJECT")),
            )
        val repeats = listOf(ClaimPair.of("c3", "c4"))
        val judged =
            listOf(
                judgedRecord("c5", "c6", JudgeRelation.CONTRADICTS, 0.72),
                judgedRecord("c1", "c2", JudgeRelation.CONTRADICTS, 0.8),
                judgedRecord("c4", "c5", JudgeRelation.CORROBORATES, 0.6),
            )
        val reference = FactAssembler.assemble(claims, repeats, judged, props)
        repeat(3) { seed ->
            val shuffled =
                FactAssembler.assemble(
                    claims.shuffled(Random(seed)),
                    repeats.shuffled(Random(seed)),
                    judged.shuffled(Random(seed)),
                    props,
                )
            assertEquals(reference, shuffled)
        }
    }
}

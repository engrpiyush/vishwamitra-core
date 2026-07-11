package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Stage 3.5 LLD §3.6 worked example is a mandatory fixture: every published intermediate must
 * reproduce within ±0.005. The contrast fixtures (all-self-only → WEAK, corroborated/diverse →
 * STRONG) and the §3.7 invariants I-A1..I-A4 are pinned alongside.
 */
private val P = AppProperties.Stage3()

private fun row(
    claimId: String,
    factId: String,
    belief: Double,
    independence: Double = 1.0,
    mass: Double = 1.0,
    sourceClass: String = "SELF",
    attestorKey: String? = "subject:s1",
    attestorKind: String? = "SUBJECT",
    anchored: Boolean = false,
    edges: List<Map<String, Any?>> = emptyList(),
    signalsJson: String? = """{"independence":$independence,"evidenceMass":$mass}""",
) =
    ScoredClaimView(
        claimId = claimId,
        text = "claim $claimId",
        type = "EPISODE",
        tierSeed = "MEDIUM",
        prior = null,
        score = belief,
        scoreBare = belief,
        signalsJson = signalsJson,
        basis = "STATED",
        sourceClass = sourceClass,
        sensitive = false,
        claimedDate = null,
        attestorKey = attestorKey,
        attestorName = null,
        attestorKind = attestorKind,
        attestorTrust = null,
        factId = factId,
        factLabel = "fact $factId",
        factKind = "EVENT",
        slot = null,
        validFrom = null,
        validTo = null,
        datePrecision = null,
        anchored = anchored,
        belief = belief,
        beliefBare = belief,
        entities = emptyList(),
        edges = edges,
        explanation = null,
    )

private fun edge(
    relation: String,
    otherFactId: String,
    confidence: Double,
    explained: Boolean = false,
    reviewStatus: String? = "PROPOSED",
): Map<String, Any?> =
    mapOf(
        "relation" to relation,
        "otherFactId" to otherFactId,
        "confidence" to confidence,
        "explained" to explained,
        "reviewStatus" to reviewStatus,
    )

/** The §3.6 reference population: 24 corroborated + 8 self-only facts, 5 independent attestors. */
private fun workedExample(): List<ScoredClaimView> {
    val rows = mutableListOf<ScoredClaimView>()
    // 8 anchored DOCUMENTARY facts (docFrac = 8/32 = 0.25), issuer-attested (2 ISSUERs).
    for (i in 1..8) {
        val factId = "fa%02d".format(i)
        val contradiction =
            when (factId) {
                "fa01" -> listOf(edge("CONTRADICTS", "fa03", 0.75, explained = true))
                "fa03" -> listOf(edge("CONTRADICTS", "fa01", 0.75, explained = true))
                "fa02" -> listOf(edge("CONTRADICTS", "fa04", 0.65, explained = true))
                "fa04" -> listOf(edge("CONTRADICTS", "fa02", 0.65, explained = true))
                else -> emptyList()
            }
        rows +=
            row(
                claimId = "ca%02d".format(i),
                factId = factId,
                belief = 0.90,
                independence = 0.85,
                mass = 4.0,
                sourceClass = "DOCUMENTARY",
                attestorKey = "issuer:${if (i % 2 == 0) "a" else "b"}",
                attestorKind = "ISSUER",
                anchored = true,
                edges = contradiction + listOf(edge("CORROBORATES", "fe%02d".format(i), 0.8)),
            )
    }
    // 16 endorsement-backed facts (3 ENDORSER attestors).
    for (i in 1..16) {
        rows +=
            row(
                claimId = "ce%02d".format(i),
                factId = "fe%02d".format(i),
                belief = 0.90,
                independence = 0.85,
                mass = 4.0,
                sourceClass = "ENDORSEMENT",
                attestorKey = "endorser:${listOf("a", "b", "c")[i % 3]}",
                attestorKind = "ENDORSER",
                edges =
                    if (i <= 8) listOf(edge("CORROBORATES", "fa%02d".format(i), 0.8))
                    else emptyList(),
            )
    }
    // 8 self-only facts: the subject's word alone, one lone assertion each.
    for (i in 1..8) {
        rows += row(claimId = "cs%02d".format(i), factId = "fs%02d".format(i), belief = 0.62)
    }
    return rows
}

private fun assertNear(expected: Double, actual: Double, what: String, tol: Double = 0.005) {
    assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")
}

class SubjectScorerTest {

    @Test
    fun `worked example reproduces every published intermediate`() {
        val score = SubjectScorer.score(workedExample(), P)
        val c = score.components
        assertNear(0.854, c.weightedBelief, "B")
        assertNear(0.583, c.evidenceDepth, "D")
        assertNear(0.750, c.independentCoverage, "I")
        assertNear(1.000, c.sourceDiversity, "V")
        assertNear(0.219, c.contradictionDrag, "C")
        assertNear(0.875, c.depthFactor, "Φ_D")
        assertNear(0.925, c.coverageFactor, "Φ_I")
        assertNear(1.000, c.diversityFactor, "Φ_V")
        assertNear(0.945, c.contradictionFactor, "Φ_C")
        assertNear(0.653, score.score, "SAI")
        assertEquals(65, score.display)
        assertEquals("GOOD", score.band)

        val inputs = score.inputs
        assertEquals(32, inputs.factCount)
        assertEquals(32, inputs.claimCount)
        assertEquals(8, inputs.selfOnlyFactCount)
        assertEquals(5, inputs.independentAttestorCount)
        assertEquals(3, inputs.attestorKindCount)
        assertEquals(2, inputs.contradictionCount)
        assertEquals(2, inputs.explainedContradictionCount)
        assertEquals(8, inputs.anchoredFactCount)
        assertNear(0.25, inputs.documentaryFactFraction, "docFrac")
        assertEquals(8, inputs.corroborationCount) // fa↔fe pairs, deduped from both sides
    }

    @Test
    fun `all-self-only subject reads WEAK even with zero contradictions`() {
        val rows = (1..10).map { row("c$it", "f$it", belief = 0.62) }
        val score = SubjectScorer.score(rows, P)
        assertNear(0.295, score.score, "SAI")
        assertEquals(30, score.display)
        assertEquals("WEAK", score.band)
        assertEquals(10, score.inputs.selfOnlyFactCount)
    }

    @Test
    fun `corroborated diverse subject reads STRONG and hits the ~88 ceiling`() {
        val rows =
            (1..30).map { i ->
                val selfFact = i <= 6
                row(
                    claimId = "c$i",
                    factId = "f%02d".format(i),
                    belief = 0.95,
                    independence = 0.9,
                    mass = 6.0,
                    sourceClass =
                        when {
                            selfFact -> "SELF"
                            i <= 16 -> "DOCUMENTARY"
                            else -> "ENDORSEMENT"
                        },
                    attestorKey =
                        when {
                            selfFact -> "subject:s1"
                            i <= 16 -> "issuer:a"
                            else -> "endorser:${listOf("a", "b", "c")[i % 3]}"
                        },
                    attestorKind =
                        when {
                            selfFact -> "SUBJECT"
                            i <= 16 -> "ISSUER"
                            else -> "ENDORSER"
                        },
                    anchored = i in 7..16,
                    // Every fact carries corroboration (self facts included — that is what
                    // keeps them off the self-only count).
                    edges = listOf(edge("CORROBORATES", "f%02d".format(i % 30 + 1), 0.8)),
                )
            }
        val score = SubjectScorer.score(rows, P)
        assertNear(0.879, score.score, "SAI")
        assertEquals(88, score.display)
        assertEquals("STRONG", score.band)
        assertEquals(0, score.inputs.selfOnlyFactCount)
    }

    @Test
    fun `empty population is UNSUPPORTED zero`() {
        val score = SubjectScorer.score(emptyList(), P)
        assertEquals(0.0, score.score)
        assertEquals(0, score.display)
        assertEquals("UNSUPPORTED", score.band)
        assertEquals(0, score.inputs.factCount)
    }

    /** I-A1: identical rows in any order → identical output. */
    @Test
    fun `deterministic under input shuffle`() {
        val rows = workedExample()
        val a = SubjectScorer.score(rows, P)
        val b = SubjectScorer.score(rows.shuffled(Random(42)), P)
        assertEquals(a, b)
    }

    /** I-A2: independent corroboration (belief + mass + coverage together) raises SAI. */
    @Test
    fun `corroborating a self-only fact never lowers the aggregate`() {
        val baseline = (1..10).map { row("c$it", "f$it", belief = 0.62) }
        val corroborated =
            baseline.filterNot { it.factId == "f1" } +
                listOf(
                    row(
                        "c1",
                        "f1",
                        belief = 0.80,
                        independence = 1.0,
                        mass = 2.0,
                        edges = listOf(edge("CORROBORATES", "f2", 0.8)),
                    ),
                    row(
                        "c1b",
                        "f1",
                        belief = 0.80,
                        independence = 1.0,
                        mass = 2.0,
                        sourceClass = "ENDORSEMENT",
                        attestorKey = "endorser:x",
                        attestorKind = "ENDORSER",
                        edges = listOf(edge("CORROBORATES", "f2", 0.8)),
                    ),
                )
        val before = SubjectScorer.score(baseline, P).score
        val after = SubjectScorer.score(corroborated, P).score
        assertTrue(after > before, "corroboration must raise SAI ($before → $after)")
    }

    /** I-A3: duplicating same-source claims on a fact never changes the score. */
    @Test
    fun `cluster size cannot inflate the aggregate`() {
        val baseline = (1..10).map { row("c$it", "f$it", belief = 0.62) }
        val flooded = baseline + (1..4).map { row("c1-dup$it", "f1", belief = 0.62) }
        val a = SubjectScorer.score(baseline, P)
        val b = SubjectScorer.score(flooded, P)
        assertEquals(a.score, b.score, 1e-9)
        assertEquals(a.components, b.components)
        assertEquals(14, b.inputs.claimCount) // only the input count moves
    }

    /** I-A4: an explained contradiction costs exactly (1−μ) × the unexplained residual. */
    @Test
    fun `explained contradiction keeps the mitigated residual`() {
        fun pair(explained: Boolean) =
            listOf(
                row(
                    "c1",
                    "f1",
                    belief = 0.8,
                    mass = 2.0,
                    sourceClass = "ENDORSEMENT",
                    attestorKey = "endorser:a",
                    attestorKind = "ENDORSER",
                    edges = listOf(edge("CONTRADICTS", "f2", 0.8, explained = explained)),
                ),
                row(
                    "c2",
                    "f2",
                    belief = 0.8,
                    mass = 2.0,
                    sourceClass = "ENDORSEMENT",
                    attestorKey = "endorser:b",
                    attestorKind = "ENDORSER",
                    edges = listOf(edge("CONTRADICTS", "f1", 0.8, explained = explained)),
                ),
            )
        // Invert the saturation to recover the residual mass P = c0·C/(1−C).
        fun residual(explained: Boolean): Double {
            val c = SubjectScorer.score(pair(explained), P).components.contradictionDrag
            return P.aggContradictionMidpoint * c / (1 - c)
        }
        val unexplained = residual(false)
        val explained = residual(true)
        assertNear(0.8, unexplained, "unexplained residual", tol = 1e-6)
        assertNear(
            (1 - P.explanationMitigation) * unexplained,
            explained,
            "explained residual",
            tol = 1e-6,
        )
    }

    @Test
    fun `band boundaries map to the right band`() {
        assertEquals("STRONG", SubjectScorer.band(0.80, P))
        assertEquals("GOOD", SubjectScorer.band(0.7999, P))
        assertEquals("GOOD", SubjectScorer.band(0.65, P))
        assertEquals("MODERATE", SubjectScorer.band(0.6499, P))
        assertEquals("MODERATE", SubjectScorer.band(0.45, P))
        assertEquals("WEAK", SubjectScorer.band(0.4499, P))
        assertEquals("WEAK", SubjectScorer.band(0.25, P))
        assertEquals("UNSUPPORTED", SubjectScorer.band(0.2499, P))
    }

    @Test
    fun `missing signals fall back to the thinnest posture`() {
        val rows =
            listOf(
                row(
                    "c1",
                    "f1",
                    belief = 0.7,
                    sourceClass = "ENDORSEMENT",
                    attestorKey = "endorser:a",
                    attestorKind = "ENDORSER",
                    signalsJson = null,
                )
            )
        val score = SubjectScorer.score(rows, P)
        assertNear(1.0 / 3.0, score.components.evidenceDepth, "D (mass defaults to 1.0)")
        assertNear(0.7, score.components.weightedBelief, "B")
    }
}

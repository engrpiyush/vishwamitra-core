package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The §11.8 worked example ("Asha", six claims) is a mandatory fixture: every published number must
 * reproduce within ±0.005. The fixture tightens ε to 0.001 — the LLD's published values are the
 * ideal fixed points, and the default ε=0.005 stops the damped iteration ~0.005 shy of them
 * (documented as-built note).
 */
private fun params(
    stage3: AppProperties.Stage3 = AppProperties.Stage3(epsilon = 0.001),
    favorabilityThreshold: Double = 0.5,
    asOf: LocalDate = LocalDate.of(2026, 7, 9),
) = ScorerParams(stage3, favorabilityThreshold, asOf)

private fun claimSnap(
    id: String,
    factId: String,
    tierSeed: String = "LOW",
    sourceClass: String = "SELF",
    attestorKey: String = "subject:asha",
    assetId: String = "asset-$id",
    type: String? = "EPISODE",
    basis: String? = "STATED",
    favorability: Double? = 0.8,
    claimedDate: String? = null,
) =
    ClaimSnapshot(
        claimId = id,
        factId = factId,
        type = type,
        tierSeed = tierSeed,
        sourceClass = sourceClass,
        basis = basis,
        favorability = favorability,
        claimedDate = claimedDate,
        attestorKey = attestorKey,
        assetId = assetId,
    )

private fun factSnap(
    id: String,
    exemplar: String,
    kind: String = "EVENT",
    anchored: Boolean = false,
) = FactSnapshot(factId = id, factKind = kind, exemplarClaimId = exemplar, anchored = anchored)

private fun attestor(key: String, prior: Double, trust: Double = prior) =
    AttestorSnapshot(attestorKey = key, trustPrior = prior, trust = trust)

private fun contradicts(
    from: String,
    to: String,
    confidence: Double,
    withContext: Boolean = false,
    ctxRelation: String? = null,
    ctxConfidence: Double? = null,
    explained: Boolean = false,
) =
    EdgeSnapshot(
        fromFactId = from,
        toFactId = to,
        relation = "CONTRADICTS",
        confidence = confidence,
        withContext = withContext,
        ctxRelation = ctxRelation,
        ctxConfidence = ctxConfidence,
        explained = explained,
    )

private fun corroborates(from: String, to: String, confidence: Double) =
    EdgeSnapshot(
        fromFactId = from,
        toFactId = to,
        relation = "CORROBORATES",
        confidence = confidence,
        withContext = false,
        ctxRelation = null,
        ctxConfidence = null,
        explained = false,
    )

/** The §11.8 mini-corpus: c1..c6, F1..F4, the explained F4→F3 contradiction. */
private fun ashaSnapshot(): GraphSnapshot =
    GraphSnapshot(
        claims =
            listOf(
                claimSnap(
                    "c1",
                    "F1",
                    tierSeed = "HIGH",
                    sourceClass = "DOCUMENTARY",
                    attestorKey = "issuer:univ-x",
                    assetId = "a-degree",
                    claimedDate = "2016"
                ),
                claimSnap("c2", "F1", tierSeed = "LOW", sourceClass = "SELF", assetId = "a-self"),
                claimSnap(
                    "c3",
                    "F2",
                    tierSeed = "MEDIUM",
                    sourceClass = "ENDORSEMENT",
                    attestorKey = "endorser:r-mehta",
                    assetId = "a-mgr",
                    type = "SKILL"
                ),
                claimSnap(
                    "c4",
                    "F2",
                    tierSeed = "LOW",
                    sourceClass = "SELF",
                    assetId = "a-self",
                    type = "SKILL"
                ),
                claimSnap(
                    "c5",
                    "F3",
                    tierSeed = "LOW",
                    sourceClass = "SELF",
                    assetId = "a-self",
                    claimedDate = "2019"
                ),
                claimSnap(
                    "c6",
                    "F4",
                    tierSeed = "MEDIUM",
                    sourceClass = "ENDORSEMENT",
                    attestorKey = "endorser:s-rao",
                    assetId = "a-peer",
                    claimedDate = "2019"
                ),
            ),
        facts =
            listOf(
                factSnap("F1", exemplar = "c1", anchored = true),
                factSnap("F2", exemplar = "c3", kind = "TIMELESS"),
                factSnap("F3", exemplar = "c5"),
                factSnap("F4", exemplar = "c6"),
            ),
        attestors =
            listOf(
                attestor("subject:asha", 0.5),
                attestor("issuer:univ-x", 0.8),
                attestor("endorser:r-mehta", 0.6),
                attestor("endorser:s-rao", 0.6),
            ),
        edges =
            listOf(
                // Bare CONTRADICTS 0.72; the with-context re-judge fell below the floor
                // (NEUTRAL) and affirmed relevance — the §11.9 explained pass drops the penalty.
                contradicts(
                    "F4",
                    "F3",
                    confidence = 0.72,
                    withContext = true,
                    ctxRelation = "NEUTRAL",
                    ctxConfidence = 0.36,
                    explained = true
                )
            ),
    )

class ScorerTest {

    private fun scoreOf(outcome: ScoreOutcome, claimId: String) =
        outcome.claims.first { it.claimId == claimId }

    private fun beliefOf(outcome: ScoreOutcome, factId: String) =
        outcome.facts.first { it.factId == factId }

    // ---- the mandatory §11.8 worked example -------------------------------------------

    @Test
    fun `worked example reproduces every published number`() {
        val outcome = Scorer.score(ashaSnapshot(), params())

        // F1: noisy-OR over issuer + subject groups: 1 − 0.15×0.65 = 0.9025.
        assertEquals(0.9025, beliefOf(outcome, "F1").belief, 0.005)
        assertEquals(0.9025, beliefOf(outcome, "F1").beliefBare, 0.005)
        // F2: 1 − 0.40×0.65 = 0.74.
        assertEquals(0.74, beliefOf(outcome, "F2").belief, 0.005)
        // F3 bare: σ(logit(0.35) − 1.2×0.72×rel(0.60)) ≈ 0.31; explained: penalty NEUTRAL-ed
        // by the ctx verdict → back to the 0.35 prior.
        assertEquals(0.31, beliefOf(outcome, "F3").beliefBare, 0.005)
        assertEquals(0.35, beliefOf(outcome, "F3").belief, 0.005)
        // F4: unmoved — rel(B(F3)) = clip(2×0.35−1) = 0; a weak accusation cannot dent it.
        assertEquals(0.60, beliefOf(outcome, "F4").belief, 0.005)

        // Published claim rows: score (scoreBare) and tier movements.
        assertEquals(0.9025, scoreOf(outcome, "c1").score, 0.005)
        assertEquals("HIGH", scoreOf(outcome, "c1").tier)
        assertEquals("HIGH", scoreOf(outcome, "c2").tier) // LOW seed → HIGH scored
        assertEquals("MEDIUM", scoreOf(outcome, "c3").tier)
        assertEquals("MEDIUM", scoreOf(outcome, "c4").tier) // LOW seed → MEDIUM scored
        assertEquals(0.35, scoreOf(outcome, "c5").score, 0.005)
        assertEquals(0.31, scoreOf(outcome, "c5").scoreBare, 0.005)
        assertEquals("LOW", scoreOf(outcome, "c5").tier)
        assertEquals(0.60, scoreOf(outcome, "c6").score, 0.005)

        // The bare conflict term the published table lists for c5: 1.2×0.72×0.2 ≈ 0.17.
        assertEquals(0.17, beliefOf(outcome, "F3").signals.conflict, 0.005)
        assertEquals(0.0, beliefOf(outcome, "F3").signals.support)
        // F2's published independence/evidenceMass: 2 groups / 2 assertions, Σdep = 2.
        assertEquals(1.0, beliefOf(outcome, "F2").signals.independence)
        assertEquals(2.0, beliefOf(outcome, "F2").signals.evidenceMass)

        // Subject trust after step 3: (3×mean(0.90, 0.74, 0.35) + 5×0.5)/8 ≈ 0.56.
        val subjectTrust = outcome.trustUpdates.first { it.attestorKey == "subject:asha" }
        assertEquals(0.56, subjectTrust.trust, 0.005)
        assertEquals(3, subjectTrust.factCount)

        assertTrue(outcome.converged)
        assertEquals(0L, outcome.i2Clamped)
    }

    @Test
    fun `priors follow the tier seeds with the against-interest and inferred modifiers`() {
        val p = params()
        assertEquals(0.85, Scorer.prior(claimSnap("c", "F", tierSeed = "HIGH"), p), 1e-9)
        assertEquals(0.60, Scorer.prior(claimSnap("c", "F", tierSeed = "MEDIUM"), p), 1e-9)
        assertEquals(0.35, Scorer.prior(claimSnap("c", "F", tierSeed = "LOW"), p), 1e-9)
        // β: unfavorable SELF — statement against interest.
        val against = claimSnap("c", "F", tierSeed = "LOW", favorability = 0.2)
        assertEquals(
            Scorer.sigmoid(Scorer.logit(0.35) + 0.4),
            Scorer.prior(against, p),
            1e-9,
        )
        // δ: INFERRED penalty; non-SELF favorability never grants β.
        val inferred =
            claimSnap(
                "c",
                "F",
                tierSeed = "MEDIUM",
                sourceClass = "ENDORSEMENT",
                favorability = 0.2,
                basis = "INFERRED"
            )
        assertEquals(
            Scorer.sigmoid(Scorer.logit(0.60) - 0.5),
            Scorer.prior(inferred, p),
            1e-9,
        )
    }

    // ---- invariants I1–I6 (LLD §13) ----------------------------------------------------

    /** I1 monotonicity: adding a supporter never lowers the supported fact's belief. */
    @Test
    fun `I1 - adding a corroborator never lowers belief`() {
        repeat(5) { seed ->
            val rng = Random(seed)
            val base =
                GraphSnapshot(
                    claims =
                        listOf(
                            claimSnap("c1", "F1", tierSeed = "LOW"),
                            claimSnap(
                                "c2",
                                "F2",
                                tierSeed = "MEDIUM",
                                sourceClass = "ENDORSEMENT",
                                attestorKey = "endorser:e1",
                                assetId = "a-e1"
                            ),
                        ),
                    facts = listOf(factSnap("F1", "c1"), factSnap("F2", "c2")),
                    attestors = listOf(attestor("subject:asha", 0.5), attestor("endorser:e1", 0.6)),
                    edges = emptyList(),
                )
            val without = Scorer.score(base, params())
            val with =
                Scorer.score(
                    base.copy(
                        edges =
                            listOf(corroborates("F2", "F1", confidence = rng.nextDouble(0.55, 1.0)))
                    ),
                    params(),
                )
            assertTrue(
                with.facts.first { it.factId == "F1" }.belief + 1e-9 >=
                    without.facts.first { it.factId == "F1" }.belief,
                "seed $seed: supporter lowered belief",
            )
        }
    }

    /** I2: per-claim score ≥ scoreBare — the explanation can only ever help. */
    @Test
    fun `I2 - explained score never drops below bare score`() {
        val outcome = Scorer.score(ashaSnapshot(), params())
        outcome.claims.forEach {
            assertTrue(it.score + 1e-9 >= it.scoreBare, "${it.claimId} violates I2")
        }
        assertEquals(0L, outcome.i2Clamped)
        // Mitigated-but-still-contradicting variant: μ softens, never inverts.
        val mitigated =
            ashaSnapshot().let { snap ->
                snap.copy(
                    edges =
                        listOf(
                            contradicts(
                                "F4",
                                "F3",
                                0.72,
                                withContext = true,
                                ctxRelation = "CONTRADICTS",
                                ctxConfidence = 0.60,
                                explained = true
                            )
                        )
                )
            }
        Scorer.score(mitigated, params()).claims.forEach {
            assertTrue(it.score + 1e-9 >= it.scoreBare, "${it.claimId} violates I2 (mitigated)")
        }
    }

    /** I3: a duplicate assertion from the same attestor adds exactly zero. */
    @Test
    fun `I3 - same-attestor duplicates add nothing`() {
        fun snapshotWith(duplicates: Int): GraphSnapshot {
            val claims =
                mutableListOf(
                    claimSnap(
                        "c-base",
                        "F1",
                        tierSeed = "MEDIUM",
                        sourceClass = "ENDORSEMENT",
                        attestorKey = "endorser:e1",
                        assetId = "a-e1"
                    )
                )
            repeat(duplicates) { i ->
                claims +=
                    claimSnap(
                        "c-dup-$i",
                        "F1",
                        tierSeed = "LOW",
                        sourceClass = "ENDORSEMENT",
                        attestorKey = "endorser:e1",
                        assetId = "a-e1"
                    )
            }
            return GraphSnapshot(
                claims = claims,
                facts = listOf(factSnap("F1", "c-base")),
                attestors = listOf(attestor("endorser:e1", 0.6)),
                edges = emptyList(),
            )
        }
        val one = Scorer.score(snapshotWith(0), params())
        val three = Scorer.score(snapshotWith(2), params())
        assertEquals(
            one.facts.single().belief,
            three.facts.single().belief,
            1e-9,
        )
    }

    /**
     * I4: an anchored fact moves at most ρ as far as an unanchored twin **per update step** —
     * measured over one iteration, where the plasticity bound is exact. (Over many iterations the
     * mutual-contradiction feedback also lets the free twin *recover* faster, confounding totals.)
     */
    @Test
    fun `I4 - anchor plasticity bounds movement`() {
        fun snapshot(anchored: Boolean) =
            GraphSnapshot(
                claims =
                    listOf(
                        claimSnap(
                            "c1",
                            "F1",
                            tierSeed = "HIGH",
                            sourceClass = "DOCUMENTARY",
                            attestorKey = "issuer:i1",
                            assetId = "a-doc"
                        ),
                        claimSnap(
                            "c2",
                            "F2",
                            tierSeed = "MEDIUM",
                            sourceClass = "ENDORSEMENT",
                            attestorKey = "endorser:e1",
                            assetId = "a-e1"
                        ),
                    ),
                facts = listOf(factSnap("F1", "c1", anchored = anchored), factSnap("F2", "c2")),
                attestors = listOf(attestor("issuer:i1", 0.8), attestor("endorser:e1", 0.6)),
                edges = listOf(contradicts("F2", "F1", confidence = 0.9)),
            )
        val oneStep = params(stage3 = AppProperties.Stage3(maxIterations = 1))
        val anchoredDrop =
            0.85 - Scorer.score(snapshot(true), oneStep).facts.first { it.factId == "F1" }.belief
        val freeDrop =
            0.85 - Scorer.score(snapshot(false), oneStep).facts.first { it.factId == "F1" }.belief
        assertTrue(anchoredDrop > 0 && freeDrop > 0)
        assertTrue(anchoredDrop < freeDrop, "anchored fact must move less than a free one")
    }

    /** I5: bit-identical determinism under input shuffle. */
    @Test
    fun `I5 - scoring is bit-identical under input shuffle`() {
        val snapshot = ashaSnapshot()
        val reference = Scorer.score(snapshot, params())
        repeat(3) { seed ->
            val rng = Random(seed)
            val shuffled =
                GraphSnapshot(
                    claims = snapshot.claims.shuffled(rng),
                    facts = snapshot.facts.shuffled(rng),
                    attestors = snapshot.attestors.shuffled(rng),
                    edges = snapshot.edges.shuffled(rng),
                )
            assertEquals(reference, Scorer.score(shuffled, params()))
        }
    }

    /** I6: the self-praise ceiling and the universal clamps hold. */
    @Test
    fun `I6 - self-praise ceiling and clamps`() {
        // Favorable SELF-only praise with heavy mutual corroboration would inflate without the
        // ceiling; with zero independent support it caps at 0.65.
        val selfPraise =
            GraphSnapshot(
                claims =
                    listOf(
                        claimSnap("c1", "F1", tierSeed = "HIGH", favorability = 0.9),
                        claimSnap(
                            "c2",
                            "F1",
                            tierSeed = "HIGH",
                            favorability = 0.9,
                            assetId = "a-self-2"
                        ),
                    ),
                facts = listOf(factSnap("F1", "c1", kind = "TIMELESS")),
                attestors = listOf(attestor("subject:asha", 0.5)),
                edges = emptyList(),
            )
        val capped = Scorer.score(selfPraise, params())
        assertTrue(capped.facts.single().belief <= 0.65 + 1e-9)

        // An against-interest SELF fact is not "praise" — the ceiling must not apply.
        val againstInterest =
            selfPraise.copy(
                claims =
                    listOf(
                        claimSnap("c1", "F1", tierSeed = "HIGH", favorability = 0.2),
                        claimSnap(
                            "c2",
                            "F1",
                            tierSeed = "HIGH",
                            favorability = 0.9,
                            assetId = "a-self-2"
                        ),
                    )
            )
        assertTrue(Scorer.score(againstInterest, params()).facts.single().belief > 0.65)

        // Universal clamp: nothing leaves [0.02, 0.98] even under absurd inputs.
        val extreme =
            GraphSnapshot(
                claims =
                    listOf(
                        claimSnap(
                            "c1",
                            "F1",
                            tierSeed = "HIGH",
                            sourceClass = "DOCUMENTARY",
                            attestorKey = "issuer:i1",
                            assetId = "a-1"
                        ),
                        claimSnap(
                            "c2",
                            "F1",
                            tierSeed = "HIGH",
                            sourceClass = "ENDORSEMENT",
                            attestorKey = "endorser:e1",
                            assetId = "a-2"
                        ),
                        claimSnap(
                            "c3",
                            "F1",
                            tierSeed = "HIGH",
                            sourceClass = "ENDORSEMENT",
                            attestorKey = "endorser:e2",
                            assetId = "a-3"
                        ),
                        claimSnap(
                            "c4",
                            "F2",
                            tierSeed = "LOW",
                            basis = "INFERRED",
                            favorability = 0.2
                        ),
                    ),
                facts = listOf(factSnap("F1", "c1"), factSnap("F2", "c4")),
                attestors =
                    listOf(
                        attestor("issuer:i1", 0.8),
                        attestor("endorser:e1", 0.6),
                        attestor("endorser:e2", 0.6),
                        attestor("subject:asha", 0.5),
                    ),
                edges =
                    listOf(
                        corroborates("F2", "F1", 1.0),
                        contradicts("F1", "F2", 1.0),
                    ),
            )
        Scorer.score(extreme, params()).facts.forEach {
            assertTrue(it.belief in 0.02..0.98)
            assertTrue(it.beliefBare in 0.02..0.98)
        }
    }

    // ---- structure ----------------------------------------------------------------------

    @Test
    fun `partially dependent endorser groups are lambda-damped`() {
        // Two endorsers voiced on the SAME asset: the second group contributes λ-damped.
        fun snapshot(sharedAsset: Boolean) =
            GraphSnapshot(
                claims =
                    listOf(
                        claimSnap(
                            "c1",
                            "F1",
                            tierSeed = "MEDIUM",
                            sourceClass = "ENDORSEMENT",
                            attestorKey = "endorser:e1",
                            assetId = "a-call"
                        ),
                        claimSnap(
                            "c2",
                            "F1",
                            tierSeed = "MEDIUM",
                            sourceClass = "ENDORSEMENT",
                            attestorKey = "endorser:e2",
                            assetId = if (sharedAsset) "a-call" else "a-other"
                        ),
                    ),
                facts = listOf(factSnap("F1", "c1")),
                attestors = listOf(attestor("endorser:e1", 0.6), attestor("endorser:e2", 0.6)),
                edges = emptyList(),
            )
        val shared = Scorer.score(snapshot(true), params()).facts.single().belief
        val independent = Scorer.score(snapshot(false), params()).facts.single().belief
        // Independent: 1−0.4² = 0.84; shared asset: 1−0.4×(1−0.6×0.4) = 0.696.
        assertEquals(0.84, independent, 0.005)
        assertEquals(0.696, shared, 0.005)
        assertTrue(shared < independent)
    }

    @Test
    fun `volatile supporters decay by the half-life and the recency signal publishes`() {
        // A SKILL fact (half-life 5y) supported by a 10-year-old fact: rec = 0.5² = 0.25.
        val snapshot =
            GraphSnapshot(
                claims =
                    listOf(
                        claimSnap(
                            "c1",
                            "F1",
                            tierSeed = "LOW",
                            type = "SKILL",
                            claimedDate = "2016"
                        ),
                        claimSnap(
                            "c2",
                            "F2",
                            tierSeed = "MEDIUM",
                            sourceClass = "ENDORSEMENT",
                            attestorKey = "endorser:e1",
                            assetId = "a-e1",
                            claimedDate = "2016-06-01"
                        ),
                    ),
                facts = listOf(factSnap("F1", "c1", kind = "TIMELESS"), factSnap("F2", "c2")),
                attestors = listOf(attestor("subject:asha", 0.5), attestor("endorser:e1", 0.6)),
                edges = listOf(corroborates("F2", "F1", confidence = 1.0)),
            )
        val outcome = Scorer.score(snapshot, params(asOf = LocalDate.of(2026, 7, 9)))
        val f1 = outcome.facts.first { it.factId == "F1" }
        // Support term: α_corr × conf × rel(0.60) × rec(10y/5y half-life) = 0.8×1×0.2×0.25.
        assertEquals(0.04, f1.signals.support, 0.005)
        // The fact's own recency signal: its member is 10 years old → 0.25.
        assertEquals(0.25, f1.signals.recency, 0.005)
        // A non-volatile fact publishes 1.0.
        assertEquals(1.0, outcome.facts.first { it.factId == "F2" }.signals.recency)
    }

    @Test
    fun `non-convergence still writes best-estimate beliefs and flags the run`() {
        // Zero damping + a tight epsilon + mutual contradiction between two believed facts
        // oscillates; the scorer must cap iterations, flag converged=false and still score.
        val snapshot =
            GraphSnapshot(
                claims =
                    listOf(
                        claimSnap(
                            "c1",
                            "F1",
                            tierSeed = "HIGH",
                            sourceClass = "ENDORSEMENT",
                            attestorKey = "endorser:e1",
                            assetId = "a-1"
                        ),
                        claimSnap(
                            "c2",
                            "F2",
                            tierSeed = "HIGH",
                            sourceClass = "ENDORSEMENT",
                            attestorKey = "endorser:e2",
                            assetId = "a-2"
                        ),
                    ),
                facts = listOf(factSnap("F1", "c1"), factSnap("F2", "c2")),
                attestors = listOf(attestor("endorser:e1", 0.6), attestor("endorser:e2", 0.6)),
                edges =
                    listOf(
                        contradicts("F1", "F2", confidence = 1.0),
                    ),
            )
        val outcome =
            Scorer.score(
                snapshot,
                params(
                    stage3 =
                        AppProperties.Stage3(
                            damping = 1.0,
                            epsilon = 1e-9,
                            maxIterations = 7,
                            contradictionWeight = 6.0,
                        )
                ),
            )
        assertFalse(outcome.converged)
        assertEquals(7, outcome.iterations)
        outcome.facts.forEach { assertTrue(it.belief in 0.02..0.98) }
    }

    @Test
    fun `incremental re-score after deleting the contradiction returns the prior`() {
        // The queue-dismiss path: same snapshot minus the edge — no matching, no judging.
        val outcome = Scorer.score(ashaSnapshot().copy(edges = emptyList()), params())
        assertEquals(0.35, outcome.facts.first { it.factId == "F3" }.beliefBare, 0.005)
        assertEquals(0.35, outcome.facts.first { it.factId == "F3" }.belief, 0.005)
        assertEquals(abs(outcome.i2Clamped).toLong(), 0L)
    }
}

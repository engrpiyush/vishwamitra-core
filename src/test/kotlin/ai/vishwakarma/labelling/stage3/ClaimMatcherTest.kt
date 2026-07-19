package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VA-14 acceptance over the pure §11.5 logic: the blocking union, every cascade rung, the
 * human-asserted bypass, exemplar memoization, the dual-context flag, EXHAUSTIVE mode, and
 * determinism (same inputs ⇒ identical queue, ranks included).
 */
class ClaimMatcherTest {

    private val props = AppProperties.Stage3() // §8.2 defaults: floor 0.60, τ_high 0.93, window 5y

    private fun claim(
        id: String,
        type: String? = "SKILL",
        text: String = "text $id",
        date: String? = null,
        asset: String? = "a1",
        explained: Boolean = false,
        exemplar: String? = null,
    ) =
        ClaimToMatch(
            claimId = id,
            type = type,
            text = text,
            claimedDate = date,
            assetId = asset,
            explained = explained,
            exemplarClaimId = exemplar,
        )

    private fun pair(x: String, y: String) = ClaimPair.of(x, y)

    // ---- blocking union -----------------------------------------------------------

    @Test
    fun `candidate union merges arms and attributes every source to the deduped pair`() {
        val claims = listOf(claim("c1"), claim("c2"), claim("c3"))
        val union =
            ClaimMatcher.candidates(
                claims,
                knn = listOf(ScoredPair(pair("c1", "c2"), 0.8)),
                coMention = listOf(pair("c2", "c1"), pair("c2", "c3")),
                humanAsserted = listOf(pair("c1", "c2")),
                props = props,
            )
        assertEquals(
            setOf(BlockSource.KNN, BlockSource.CO_MENTION, BlockSource.HUMAN),
            union[pair("c1", "c2")],
        )
        assertEquals(setOf(BlockSource.CO_MENTION), union[pair("c2", "c3")])
        assertEquals(2, union.size)
    }

    @Test
    fun `structural arm blocks same-type episodes dated within the window only`() {
        val claims =
            listOf(
                claim("c1", type = "EPISODE", date = "2019-03-01"),
                claim("c2", type = "EPISODE", date = "2021-11-20"),
                claim("c3", type = "EPISODE", date = "2010-01-01"),
                claim("c4", type = "EPISODE"), // undated — never structurally blocked
                claim("c5", type = "SKILL", date = "2020-01-01"), // not an EPISODE
            )
        val union = ClaimMatcher.candidates(claims, emptyList(), emptyList(), emptyList(), props)
        assertEquals(setOf(pair("c1", "c2")), union.keys) // 2019↔2021 within 5y; 2010 is not
        assertEquals(setOf(BlockSource.STRUCTURAL), union[pair("c1", "c2")])
    }

    @Test
    fun `union ignores pairs naming unknown claims and self-pairs`() {
        val union =
            ClaimMatcher.candidates(
                listOf(claim("c1"), claim("c2")),
                knn = listOf(ScoredPair(pair("c1", "ghost"), 0.9)),
                coMention = listOf(ClaimPair("c1", "c1")),
                humanAsserted = listOf(pair("c1", "c2")),
                props = props,
            )
        assertEquals(setOf(pair("c1", "c2")), union.keys)
    }

    // ---- the cascade rungs ---------------------------------------------------------

    @Test
    fun `rung 1 discards low-sim pairs unless human-asserted or entity-sharing`() {
        val claims = listOf(claim("c1"), claim("c2"), claim("c3"), claim("c4"))
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.STRUCTURAL), // low sim, no bypass → discard
                pair("c1", "c3") to setOf(BlockSource.CO_MENTION), // shared entity → survives
                pair("c1", "c4") to setOf(BlockSource.HUMAN), // human-asserted → survives
            )
        val sims = mapOf(pair("c1", "c2") to 0.2, pair("c1", "c3") to 0.2, pair("c1", "c4") to 0.2)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props)
        assertEquals(1L, outcome.counters.pairsDiscarded)
        assertEquals(
            setOf(pair("c1", "c3"), pair("c1", "c4")),
            outcome.queue.map { it.pair }.toSet()
        )
    }

    @Test
    fun `rung 2 auto-repeats same-source near-identical text without the judge`() {
        val claims =
            listOf(
                claim("c1", text = "Led the payments migration.", asset = "a1"),
                claim("c2", text = "led  the payments migration", asset = "a1"),
                claim("c3", text = "Led the payments migration.", asset = "OTHER"),
            )
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.KNN),
                pair("c1", "c3") to setOf(BlockSource.KNN),
            )
        val sims = mapOf(pair("c1", "c2") to 0.7, pair("c1", "c3") to 0.7)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props)
        assertEquals(
            listOf(AutoRepeatEdge(pair("c1", "c2"), rung = 2, sim = 0.7)),
            outcome.autoRepeats
        )
        // The cross-source twin is real corroboration territory — it queues for the judge.
        assertEquals(listOf(pair("c1", "c3")), outcome.queue.map { it.pair })
    }

    @Test
    fun `rung 3 auto-repeats high-sim same-type date-compatible pairs only`() {
        val claims =
            listOf(
                claim("c1", date = "2019-01-01", asset = "a1"),
                claim("c2", date = "2019-06-30", asset = "a2"), // same year → compatible
                claim("c3", date = "2023-01-01", asset = "a3"), // different year → judge
                claim("c4", type = "EPISODE", asset = "a4"), // different type → judge
            )
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.KNN),
                pair("c1", "c3") to setOf(BlockSource.KNN),
                pair("c1", "c4") to setOf(BlockSource.KNN),
            )
        val sims =
            mapOf(pair("c1", "c2") to 0.95, pair("c1", "c3") to 0.95, pair("c1", "c4") to 0.95)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props)
        assertEquals(
            listOf(AutoRepeatEdge(pair("c1", "c2"), rung = 3, sim = 0.95)),
            outcome.autoRepeats
        )
        assertEquals(
            setOf(pair("c1", "c3"), pair("c1", "c4")),
            outcome.queue.map { it.pair }.toSet(),
        )
    }

    @Test
    fun `rung 4 discards identity-value pairs at low sim even when entity-sharing`() {
        val claims = listOf(claim("c1", type = "IDENTITY"), claim("c2", type = "VALUE"))
        // CO_MENTION carries it past rung 1; rung 4's type heuristic still kills it.
        val candidates = mapOf(pair("c1", "c2") to setOf(BlockSource.CO_MENTION))
        val outcome =
            ClaimMatcher.cascade(claims, candidates, mapOf(pair("c1", "c2") to 0.3), props)
        assertEquals(1L, outcome.counters.pairsDiscarded)
        assertTrue(outcome.queue.isEmpty())
    }

    @Test
    fun `rung 4 discards distant episodes unless they share a discriminative entity`() {
        val claims =
            listOf(
                claim("c1", type = "EPISODE", date = "2010-01-01"),
                claim("c2", type = "EPISODE", date = "2020-01-01"),
                claim("c3", type = "EPISODE", date = "2020-06-01"),
            )
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.KNN), // 10y apart, no shared entity → out
                pair("c1", "c3") to setOf(BlockSource.KNN, BlockSource.CO_MENTION), // shared → in
            )
        val sims = mapOf(pair("c1", "c2") to 0.7, pair("c1", "c3") to 0.7)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props)
        assertEquals(1L, outcome.counters.pairsDiscarded)
        assertEquals(listOf(pair("c1", "c3")), outcome.queue.map { it.pair })
    }

    @Test
    fun `human-asserted pairs bypass every prune and rank ahead of higher-sim pairs`() {
        val claims = listOf(claim("c1"), claim("c2"), claim("c3"), claim("c4", type = "EPISODE"))
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.HUMAN), // the acceptance case: sim 0.1
                pair("c3", "c4") to setOf(BlockSource.KNN),
            )
        val sims = mapOf(pair("c1", "c2") to 0.1, pair("c3", "c4") to 0.9)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props)
        assertEquals(2, outcome.queue.size)
        val first = outcome.queue[0]
        assertEquals(pair("c1", "c2"), first.pair)
        assertEquals(0, first.rank)
        assertTrue(first.humanAsserted)
        assertEquals(pair("c3", "c4"), outcome.queue[1].pair)
        assertEquals(1, outcome.queue[1].rank)
    }

    @Test
    fun `rung 5 substitutes exemplars so a claim meeting a fact queues one pair`() {
        // c1..c3 assert fact F (exemplar c1); c4 is the newcomer paired against all members.
        val claims =
            listOf(
                claim("c1", exemplar = "c1"),
                claim("c2", exemplar = "c1"),
                claim("c3", exemplar = "c1"),
                claim("c4"),
            )
        val candidates =
            mapOf(
                pair("c4", "c1") to setOf(BlockSource.KNN),
                pair("c4", "c2") to setOf(BlockSource.KNN),
                pair("c4", "c3") to setOf(BlockSource.KNN),
                pair("c2", "c3") to setOf(BlockSource.KNN), // in-fact pair → collapses entirely
            )
        val sims =
            mapOf(
                pair("c4", "c1") to 0.7,
                pair("c4", "c2") to 0.8,
                pair("c4", "c3") to 0.75,
                pair("c2", "c3") to 0.9,
            )
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props)
        assertEquals(listOf(pair("c1", "c4")), outcome.queue.map { it.pair })
        assertEquals(0.8, outcome.queue.single().blockScore) // best of the merged member pairs
        assertEquals(1L, outcome.counters.pairsAutoResolved) // the (c2,c3) same-fact collapse
        assertEquals(1L, outcome.counters.pairsQueued)
    }

    @Test
    fun `explained claims queue with the dual-context flag`() {
        val claims = listOf(claim("c1", explained = true), claim("c2"), claim("c3"))
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.KNN),
                pair("c2", "c3") to setOf(BlockSource.KNN),
            )
        val sims = mapOf(pair("c1", "c2") to 0.7, pair("c2", "c3") to 0.7)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props)
        assertEquals(true, outcome.queue.first { it.pair == pair("c1", "c2") }.withContext)
        assertEquals(false, outcome.queue.first { it.pair == pair("c2", "c3") }.withContext)
    }

    @Test
    fun `an auto-resolved pair never also queues via exemplar convergence`() {
        // (c1,c2) auto-resolves as a same-source duplicate; (c1,c3) substitutes c3's exemplar
        // (c2) and converges on that very pair — rung 5 must not re-queue what rung 2 settled.
        val claims =
            listOf(
                claim("c1", text = "same words", asset = "a1"),
                claim("c2", text = "Same words", asset = "a1"),
                claim("c3", exemplar = "c2"),
            )
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.KNN),
                pair("c1", "c3") to setOf(BlockSource.KNN),
            )
        val sims = mapOf(pair("c1", "c2") to 0.99, pair("c1", "c3") to 0.7)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props)
        assertEquals(listOf(AutoRepeatEdge(pair("c1", "c2"), 2, 0.99)), outcome.autoRepeats)
        assertTrue(outcome.queue.isEmpty())
    }

    // ---- EXHAUSTIVE mode -------------------------------------------------------------

    @Test
    fun `exhaustive mode queues every same-type pair and skips the discard rungs`() {
        val exhaustive = props.copy(matchingMode = "EXHAUSTIVE")
        val claims =
            listOf(
                claim("c1", type = "EPISODE", date = "2000-01-01"),
                claim("c2", type = "EPISODE", date = "2020-01-01"), // 20y apart — rung 4 skipped
                claim("c3", type = "SKILL"),
                claim("c4", type = "SKILL"),
                claim("c5", type = "IDENTITY"),
            )
        val candidates =
            ClaimMatcher.candidates(claims, emptyList(), emptyList(), emptyList(), exhaustive)
        // Same-type pairs only: (c1,c2), (c3,c4) — no cross-type pair generation.
        assertEquals(setOf(pair("c1", "c2"), pair("c3", "c4")), candidates.keys)
        val sims = mapOf(pair("c1", "c2") to 0.05, pair("c3", "c4") to 0.05)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, exhaustive)
        assertEquals(0L, outcome.counters.pairsDiscarded) // rungs 1 and 4 are off
        assertEquals(2L, outcome.counters.pairsQueued)
    }

    // ---- determinism -------------------------------------------------------------------

    @Test
    fun `same graph and config produce an identical outcome regardless of input order`() {
        val claims =
            listOf(
                claim("c1", explained = true),
                claim("c2", type = "EPISODE", date = "2019-01-01"),
                claim("c3", type = "EPISODE", date = "2020-01-01"),
                claim("c4"),
            )
        val knn =
            listOf(
                ScoredPair(pair("c1", "c4"), 0.8),
                ScoredPair(pair("c1", "c2"), 0.65),
                ScoredPair(pair("c2", "c3"), 0.65),
            )
        val coMention = listOf(pair("c1", "c4"), pair("c3", "c4"))
        val human = listOf(pair("c2", "c4"))
        val sims =
            mapOf(
                pair("c1", "c4") to 0.8,
                pair("c1", "c2") to 0.65,
                pair("c2", "c3") to 0.65,
                pair("c3", "c4") to 0.3,
                pair("c2", "c4") to 0.1,
            )
        fun run(k: List<ScoredPair>, c: List<ClaimPair>, h: List<ClaimPair>): MatchOutcome =
            ClaimMatcher.cascade(
                claims,
                ClaimMatcher.candidates(claims, k, c, h, props),
                sims,
                props,
            )
        val a = run(knn, coMention, human)
        val b = run(knn.reversed(), coMention.reversed(), human.reversed())
        assertEquals(a, b)
        // Ranks are contiguous from 0 in queue order — the VA-15 judgeCursor contract.
        assertEquals(a.queue.indices.toList(), a.queue.map { it.rank })
    }

    // ---- VA-77 B3: the entity-IDF gate on the co-mention arm --------------------------

    @Test
    fun `rung 1 gates hub-only co-mention pairs below the floor and counts them apart`() {
        val claims = listOf(claim("c1"), claim("c2"), claim("c3"), claim("c4"))
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.CO_MENTION), // hub link, low sim → gated
                pair("c1", "c3") to setOf(BlockSource.CO_MENTION), // discriminative → bypass holds
                pair("c1", "c4") to setOf(BlockSource.CO_MENTION), // hub link but above floor
            )
        val sims = mapOf(pair("c1", "c2") to 0.2, pair("c1", "c3") to 0.2, pair("c1", "c4") to 0.7)
        val shares =
            mapOf(pair("c1", "c2") to 0.5, pair("c1", "c3") to 0.1, pair("c1", "c4") to 0.5)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, props, shares)
        assertEquals(
            setOf(pair("c1", "c3"), pair("c1", "c4")),
            outcome.queue.map { it.pair }.toSet()
        )
        assertEquals(1L, outcome.counters.pairsIdfGated)
        assertEquals(0L, outcome.counters.pairsDiscarded)
    }

    // ---- VA-77 B2: the per-claim candidate cap ----------------------------------------

    @Test
    fun `per-claim cap keeps top-N by sim, union across endpoints, exempt lanes untouched`() {
        val capped = AppProperties.Stage3(judgeCandidatesPerClaim = 1)
        val claims = listOf(claim("c1"), claim("c2"), claim("c3"), claim("c4"), claim("c5"))
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.KNN),
                pair("c1", "c3") to setOf(BlockSource.KNN),
                pair("c1", "c4") to setOf(BlockSource.KNN),
                pair("c3", "c4") to setOf(BlockSource.KNN), // loses both endpoints' slots
                pair("c1", "c5") to setOf(BlockSource.HUMAN), // quota lane: never ranked
                pair("c2", "c5") to setOf(BlockSource.STRUCTURAL), // quota lane: never ranked
            )
        val sims =
            mapOf(
                pair("c1", "c2") to 0.90,
                pair("c1", "c3") to 0.80,
                pair("c1", "c4") to 0.70,
                pair("c3", "c4") to 0.65,
                pair("c1", "c5") to 0.10,
                pair("c2", "c5") to 0.65,
            )
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, capped)
        // c1 keeps (c1,c2); (c1,c3)/(c1,c4) ride c3's/c4's own top-1 — the union keeps a
        // pair while EITHER endpoint wants it. (c3,c4) is second on both sides → capped.
        assertEquals(
            setOf(
                pair("c1", "c2"),
                pair("c1", "c3"),
                pair("c1", "c4"),
                pair("c1", "c5"),
                pair("c2", "c5"),
            ),
            outcome.queue.map { it.pair }.toSet(),
        )
        assertEquals(1L, outcome.counters.pairsCapped)
        // The human-asserted pair still ranks first (the queue-order contract is untouched).
        assertEquals(pair("c1", "c5"), outcome.queue.first().pair)
    }

    @Test
    fun `cap of zero leaves the queue uncapped`() {
        val uncapped = AppProperties.Stage3(judgeCandidatesPerClaim = 0)
        val claims = listOf(claim("c1"), claim("c2"), claim("c3"))
        val candidates =
            mapOf(
                pair("c1", "c2") to setOf(BlockSource.KNN),
                pair("c1", "c3") to setOf(BlockSource.KNN),
            )
        val sims = mapOf(pair("c1", "c2") to 0.8, pair("c1", "c3") to 0.7)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, uncapped)
        assertEquals(2L, outcome.counters.pairsQueued)
        assertEquals(0L, outcome.counters.pairsCapped)
    }
}

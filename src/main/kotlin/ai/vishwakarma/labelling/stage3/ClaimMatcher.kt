package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties

/**
 * A claim as the MATCH phase sees it (LLD §11.5) — metadata only, vectors stay graph-side (kNN and
 * pairwise similarity run as Cypher; see [Stage3GraphRepository.claimKnnPairs] /
 * [Stage3GraphRepository.pairSimilarities]).
 */
data class ClaimToMatch(
    val claimId: String,
    val type: String?,
    val text: String,
    /** ISO yyyy-MM-dd (string-typed in the graph). */
    val claimedDate: String?,
    val assetId: String?,
    /** Has an `:EXPLAINS` edge — queues the pair with the §11.9 dual-evaluation flag. */
    val explained: Boolean,
    /**
     * The exemplar of the `:Fact` this claim `:ASSERTS`, when clustering has run — rung 5
     * substitutes it so a claim meeting an n-member fact yields ONE queued pair, not n. Null until
     * VA-16 writes `Fact.exemplarClaimId` (first runs: rung 5 is a no-op by construction).
     */
    val exemplarClaimId: String?,
)

/**
 * Unordered same-subject claim pair — always held (low, high) by claimId so dedupe is structural.
 */
data class ClaimPair(val a: String, val b: String) {
    companion object {
        fun of(x: String, y: String): ClaimPair = if (x <= y) ClaimPair(x, y) else ClaimPair(y, x)
    }
}

/** Which §11.5 blocking arm produced a candidate (a pair can carry several). */
enum class BlockSource {
    KNN,
    CO_MENTION,
    STRUCTURAL,
    HUMAN,
    /** EXHAUSTIVE mode's all-same-type-pairs generator (the §13 blocking-recall oracle). */
    EXHAUSTIVE,
}

/** One kNN blocking hit ([Stage3GraphRepository.claimKnnPairs]) — carries the cosine similarity. */
data class ScoredPair(val pair: ClaimPair, val sim: Double)

/** A rung-2/3 auto-resolution: `REPEATS {method: AUTO}` written without ever calling the judge. */
data class AutoRepeatEdge(val pair: ClaimPair, val rung: Int, val sim: Double)

/** One persisted judge-queue entry — VA-15 consumes these in [rank] order via its judgeCursor. */
data class JudgeQueueEntry(
    val pair: ClaimPair,
    /** 0-based consumption order: human-asserted first, then blockScore desc, then pair id. */
    val rank: Int,
    /** The pair's embedding cosine — §15 #5's prioritization key under a judge budget. */
    val blockScore: Double,
    /** Sorted [BlockSource] names — the funnel's per-arm attribution, kept for the eval page. */
    val sources: List<String>,
    /** CITES / corroboratingClaimIds pairs: always judged, never pruned (§11.5). */
    val humanAsserted: Boolean,
    /** §11.9 dual-evaluation flag: judge bare AND with the explanation appended (VA-15). */
    val withContext: Boolean,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "a" to pair.a,
            "b" to pair.b,
            "rank" to rank,
            "blockScore" to blockScore,
            "sources" to sources,
            "humanAsserted" to humanAsserted,
            "withContext" to withContext,
        )
}

/**
 * The funnel numbers (§11.5's diagram made countable) — mapped onto run counters by the service.
 */
data class MatchCounters(
    val pairsKnn: Long,
    val pairsCoMention: Long,
    val pairsStructural: Long,
    val pairsHumanAsserted: Long,
    /** Deduped blocking-union size — the cascade's input. */
    val pairsCandidate: Long,
    /** Rungs 2/3 auto-REPEATS plus rung-5 same-fact collapses — resolved with zero LLM calls. */
    val pairsAutoResolved: Long,
    /** Rungs 1 and 4 — never judged. */
    val pairsDiscarded: Long,
    val pairsQueued: Long,
)

/** What MATCH persists ([Stage3GraphRepository.applyMatchOutcome]) plus the run-counter deltas. */
data class MatchOutcome(
    val autoRepeats: List<AutoRepeatEdge>,
    val queue: List<JudgeQueueEntry>,
    val counters: MatchCounters,
)

/**
 * The §11.5 blocking union + precision cascade, as a pure function of graph reads — deterministic
 * by construction (sorted inputs, structural pair dedupe, explicit rank assignment), which is the
 * VA-14 acceptance bar: same graph + config ⇒ identical queue, ordering included.
 *
 * PRUNED mode unions the four blocking arms; EXHAUSTIVE (the §13 blocking-recall oracle) generates
 * every same-type pair + all human-asserted ones and skips the two discard rungs (1 and 4) — the
 * auto-resolve rungs (2, 3, 5) stay active in both modes, since an auto-REPEATS is a resolution,
 * not a prune.
 */
object ClaimMatcher {

    /**
     * Assemble the candidate union (per-arm attribution included). The structural date-window arm
     * is computed here from claim metadata — EPISODEs of the same type whose dates fall within
     * `episode-window-years` (§11.5's `window(type)`).
     */
    fun candidates(
        claims: List<ClaimToMatch>,
        knn: List<ScoredPair>,
        coMention: List<ClaimPair>,
        humanAsserted: List<ClaimPair>,
        props: AppProperties.Stage3,
    ): Map<ClaimPair, Set<BlockSource>> {
        val ids = claims.map { it.claimId }.toSet()
        val union = sortedMapOf<ClaimPair, MutableSet<BlockSource>>(PAIR_ORDER)
        fun add(pair: ClaimPair, source: BlockSource) {
            if (pair.a == pair.b || pair.a !in ids || pair.b !in ids) return
            union.getOrPut(pair) { mutableSetOf() } += source
        }
        if (props.exhaustiveMatching) {
            val byType =
                claims.filter { it.type != null }.sortedBy { it.claimId }.groupBy { it.type }
            byType.values.forEach { group ->
                for (i in group.indices) for (j in i + 1 until group.size) {
                    add(ClaimPair.of(group[i].claimId, group[j].claimId), BlockSource.EXHAUSTIVE)
                }
            }
        } else {
            knn.forEach { add(it.pair, BlockSource.KNN) }
            coMention.forEach { add(it, BlockSource.CO_MENTION) }
            structuralPairs(claims, props).forEach { add(it, BlockSource.STRUCTURAL) }
        }
        humanAsserted.forEach { add(it, BlockSource.HUMAN) }
        return union
    }

    /**
     * The §11.5 structural date block: same-type EPISODE pairs whose `claimedDate`s fall within the
     * window — recall for episodic claims phrased too differently for kNN and naming no shared
     * entity ("shipped the migration" / "the 2019 payments cutover").
     */
    private fun structuralPairs(
        claims: List<ClaimToMatch>,
        props: AppProperties.Stage3,
    ): List<ClaimPair> {
        val episodes =
            claims.filter { it.type == "EPISODE" && it.year() != null }.sortedBy { it.claimId }
        val out = mutableListOf<ClaimPair>()
        for (i in episodes.indices) for (j in i + 1 until episodes.size) {
            val a = episodes[i]
            val b = episodes[j]
            if (yearsApart(a, b)!! <= props.episodeWindowYears)
                out += ClaimPair.of(a.claimId, b.claimId)
        }
        return out
    }

    /**
     * Run the precision cascade (§11.5's rung table, cheapest test first) over the candidate union
     * and produce the auto-REPEATS edges, the ranked judge queue, and the funnel counters. [sims]
     * must cover every candidate pair (kNN scores + [Stage3GraphRepository.pairSimilarities] for
     * the rest); a pair a stale index genuinely cannot score is treated as sim 0.0 — it survives
     * only via the human-asserted / shared-entity bypasses, which is the conservative direction.
     */
    fun cascade(
        claims: List<ClaimToMatch>,
        candidates: Map<ClaimPair, Set<BlockSource>>,
        sims: Map<ClaimPair, Double>,
        props: AppProperties.Stage3,
    ): MatchOutcome {
        val byId = claims.associateBy { it.claimId }
        val pruned = !props.exhaustiveMatching
        val autoRepeats = mutableListOf<AutoRepeatEdge>()
        val queued = sortedMapOf<ClaimPair, QueuedPair>(PAIR_ORDER)
        var discarded = 0L
        var sameFactCollapsed = 0L

        val ordered =
            candidates.entries.sortedWith(
                compareByDescending<Map.Entry<ClaimPair, Set<BlockSource>>> { sims[it.key] ?: 0.0 }
                    .thenBy { it.key.a }
                    .thenBy { it.key.b }
            )
        for ((pair, sources) in ordered) {
            val a = byId.getValue(pair.a)
            val b = byId.getValue(pair.b)
            val sim = sims[pair] ?: 0.0
            val human = BlockSource.HUMAN in sources
            val sharedEntity = BlockSource.CO_MENTION in sources

            // Rung 1 — similarity floor. kNN hits arrive pre-floored; this prunes the low-sim tail
            // of the structural arm (and any floor-straddling co-mention pair loses only when it
            // shares no discriminative entity, which co-mention membership rules out).
            if (pruned && !human && !sharedEntity && sim < props.simFloor) {
                discarded++
                continue
            }
            // Rung 2 — same source + near-identical normalized text: a duplicate, not evidence.
            if (a.assetId != null && a.assetId == b.assetId && nearIdentical(a.text, b.text)) {
                autoRepeats += AutoRepeatEdge(pair, rung = 2, sim = sim)
                continue
            }
            // Rung 3 — τ_high paraphrase of the same type with compatible dates.
            if (sim >= props.simAutoRepeat && a.type == b.type && datesCompatible(a, b)) {
                autoRepeats += AutoRepeatEdge(pair, rung = 3, sim = sim)
                continue
            }
            // Rung 4 — type/date incompatibility heuristics (§11.5): IDENTITY×VALUE at low sim;
            // EPISODEs further apart than the window with no shared discriminative entity.
            if (pruned && !human) {
                val types = setOf(a.type, b.type)
                val identityValueAtLowSim =
                    types == setOf("IDENTITY", "VALUE") && sim < props.simFloor
                val distantEpisodes =
                    a.type == "EPISODE" &&
                        b.type == "EPISODE" &&
                        !sharedEntity &&
                        (yearsApart(a, b) ?: 0L) > props.episodeWindowYears
                if (identityValueAtLowSim || distantEpisodes) {
                    discarded++
                    continue
                }
            }
            // Rung 5 — exemplar memoization: judge against the fact's exemplar, not every member.
            val effective =
                ClaimPair.of(a.exemplarClaimId ?: a.claimId, b.exemplarClaimId ?: b.claimId)
            if (effective.a == effective.b) {
                // Both ends assert the same fact — structurally already REPEATS-equivalent.
                sameFactCollapsed++
                continue
            }
            val effA = byId[effective.a] ?: a
            val effB = byId[effective.b] ?: b
            val existing = queued[effective]
            queued[effective] =
                QueuedPair(
                    blockScore = maxOf(existing?.blockScore ?: 0.0, sim),
                    sources = (existing?.sources ?: emptySet()) + sources,
                    humanAsserted = (existing?.humanAsserted ?: false) || human,
                    withContext = effA.explained || effB.explained,
                )
        }

        // A pair auto-resolved this run never also queues (an exemplar substitution can converge
        // on a pair rung 2/3 already settled).
        autoRepeats.forEach { queued.remove(it.pair) }

        val queue =
            queued.entries
                .sortedWith(
                    compareByDescending<Map.Entry<ClaimPair, QueuedPair>> { it.value.humanAsserted }
                        .thenByDescending { it.value.blockScore }
                        .thenBy { it.key.a }
                        .thenBy { it.key.b }
                )
                .mapIndexed { rank, (pair, q) ->
                    JudgeQueueEntry(
                        pair = pair,
                        rank = rank,
                        blockScore = q.blockScore,
                        sources = q.sources.map { it.name }.sorted(),
                        humanAsserted = q.humanAsserted,
                        withContext = q.withContext,
                    )
                }
        val counters =
            MatchCounters(
                pairsKnn = candidates.count { BlockSource.KNN in it.value }.toLong(),
                pairsCoMention = candidates.count { BlockSource.CO_MENTION in it.value }.toLong(),
                pairsStructural = candidates.count { BlockSource.STRUCTURAL in it.value }.toLong(),
                pairsHumanAsserted = candidates.count { BlockSource.HUMAN in it.value }.toLong(),
                pairsCandidate = candidates.size.toLong(),
                pairsAutoResolved = autoRepeats.size + sameFactCollapsed,
                pairsDiscarded = discarded,
                pairsQueued = queue.size.toLong(),
            )
        return MatchOutcome(autoRepeats = autoRepeats, queue = queue, counters = counters)
    }

    private data class QueuedPair(
        val blockScore: Double,
        val sources: Set<BlockSource>,
        val humanAsserted: Boolean,
        val withContext: Boolean,
    )

    private val PAIR_ORDER: Comparator<ClaimPair> = compareBy({ it.a }, { it.b })

    /** Rung 2's "near-identical": equality after the §11.3 surface normalization. */
    private fun nearIdentical(x: String, y: String): Boolean =
        normalizeSurface(x) == normalizeSurface(y)

    /**
     * Rung 3's date compatibility, precision-tolerant: a REPEATS candidate is date-compatible when
     * either side is undated or both fall in the same calendar year ("2019" vs "2019-06-01" repeat;
     * 2019 vs 2023 do not — that pair goes to the judge).
     */
    private fun datesCompatible(a: ClaimToMatch, b: ClaimToMatch): Boolean {
        val ya = a.year() ?: return true
        val yb = b.year() ?: return true
        return ya == yb
    }

    private fun yearsApart(a: ClaimToMatch, b: ClaimToMatch): Long? {
        val ya = a.year() ?: return null
        val yb = b.year() ?: return null
        return kotlin.math.abs(ya - yb).toLong()
    }

    /** ISO dates compare by year here — tolerant of yyyy / yyyy-MM / yyyy-MM-dd precision. */
    private fun ClaimToMatch.year(): Int? = claimedDate?.take(4)?.toIntOrNull()
}

package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties

/** A claim as ASSEMBLE sees it (LLD §11.7) — exemplar priority, kind patterns, dates. */
data class ClaimToAssemble(
    val claimId: String,
    val type: String?,
    val text: String,
    /** ISO yyyy / yyyy-MM / yyyy-MM-dd (string-typed in the graph; compares lexically). */
    val claimedDate: String?,
    val sourceClass: String?,
    /** Entity types this claim MENTIONS — the STATE-slot pattern input. */
    val mentionTypes: List<String>,
)

/**
 * One JUDGED pair record read back off the `JUDGE_QUEUED` edge — the claim-level verdict ASSEMBLE
 * lifts to fact level (§11.7). Bare fields drive edge existence; ctx fields ride along for the
 * §11.9 explained scoring pass.
 */
data class JudgedPairRecord(
    val pair: ClaimPair,
    val relation: JudgeRelation,
    val confidence: Double,
    val votesJson: String?,
    val rationale: String?,
    val temporalNote: String?,
    val ctxJudged: Boolean,
    val ctxRelation: JudgeRelation?,
    val ctxConfidence: Double?,
    val ctxExplanationRelevant: Boolean?,
    val judgeModel: String?,
    val promptHash: String?,
    /** Canonical names both claims mention — `viaEntities` on the lifted edge. */
    val sharedEntities: List<String>,
)

/** One `:Fact` to persist: the scoring unit (§9.2). */
data class AssembledFact(
    /** Deterministic: `fact:<lowest member claimId>` — stable across re-assembly (I5). */
    val factId: String,
    val exemplarClaimId: String,
    /** The exemplar's text — the fact's display label. */
    val label: String,
    /** STATE / EVENT / TIMELESS (§11.7). */
    val factKind: String,
    /** STATE facts: which exclusive slot (EMPLOYER, …) — the timeline lane key (as-built). */
    val slot: String?,
    val validFrom: String?,
    val validTo: String?,
    /** YEAR / MONTH / DAY / NONE — the coarsest precision among the member dates used. */
    val datePrecision: String,
    /** Any member is DOCUMENTARY-sourced (§11.7) — the §11.8 anchor-plasticity flag. */
    val anchored: Boolean,
    val memberClaimIds: List<String>,
)

/** One lifted Fact↔Fact edge (CORROBORATES or CONTRADICTS, §9.3). */
data class FactEdge(
    val fromFactId: String,
    val toFactId: String,
    val relation: String,
    val confidence: Double,
    val votesJson: String?,
    val rationale: String?,
    val temporalNote: String?,
    val judgeModel: String?,
    val promptHash: String?,
    /** A withContext verdict variant exists for a contributing pair (§11.9). */
    val withContext: Boolean,
    val ctxRelation: String?,
    val ctxConfidence: Double?,
    /** CONTRADICTS only: relevance-affirmed by the ctx judge — leaves the human queue. */
    val explained: Boolean,
    /** CONTRADICTS only: the §11.7 gate's overlap result (false for both-unknown keeps). */
    val temporalOverlap: Boolean?,
    /** CONTRADICTS only: v1 alias of [confidence] (the §9.3 property, kept simple as-built). */
    val severity: Double?,
    /** CONTRADICTS only: PROPOSED at assembly; the §11.10 queue actions move it. */
    val reviewStatus: String?,
    val viaEntities: List<String>,
    /** Contributing claim pairs, "a↔b" — the ticket's explainability record. */
    val contributingPairs: List<String>,
)

/** One SUCCEEDS timeline edge: consecutive same-slot STATE facts (§11.7 — never a penalty). */
data class SucceedsEdge(
    val fromFactId: String,
    val toFactId: String,
    val slot: String,
    /** Only when both boundary dates carry DAY precision — anything else is a guess. */
    val gapDays: Long?,
)

data class AssembleCounters(
    val facts: Long,
    val factsState: Long,
    val factsEvent: Long,
    val factsTimeless: Long,
    val factCorroborates: Long,
    val factContradicts: Long,
    /** CONTRADICTS verdicts the §11.7 temporal gate dropped (sequences, not conflicts). */
    val contradictionsGated: Long,
    val succeedsEdges: Long,
    /** Judged pairs whose ends clustered into the same fact — nothing to lift. */
    val sameFactPairsDropped: Long,
)

data class AssembleOutcome(
    val facts: List<AssembledFact>,
    val edges: List<FactEdge>,
    val succeeds: List<SucceedsEdge>,
    val counters: AssembleCounters,
)

/**
 * The §11.7 assembly, as a pure function of graph reads — deterministic by construction (sorted
 * members, min-root union-find, explicit tie-breaks), which is invariant I5's groundwork: same
 * claims + edges + config ⇒ bit-identical facts, edges and ordering.
 *
 * As-built pattern notes (the LLD's "type × entities match a state-slot-type" made concrete over
 * the Stage 2 taxonomy, which has no employment claim types):
 * - EMPLOYER: IDENTITY mentioning an ORG; or an EPISODE whose only discriminative mentions are ORGs
 *   (a pure-affiliation episode — "worked at Infosys, 2016"). An EPISODE also naming a
 *   PROJECT/SKILL/CREDENTIAL stays an EVENT ("led the payments migration").
 * - EDUCATION_ENROLLMENT: IDENTITY mentioning an INSTITUTION (ongoing enrollment). A dated
 *   completion ("completed B.E. at University X, 2016") is EPISODE ⇒ EVENT — the worked example's
 *   F1.
 * - RESIDENCE: IDENTITY mentioning a PLACE. Precedence EMPLOYER > EDUCATION_ENROLLMENT > RESIDENCE
 *   when several patterns fire. ROLE has no derivable pattern in the v1 taxonomy and never fires.
 */
object FactAssembler {

    fun assemble(
        claims: List<ClaimToAssemble>,
        repeats: List<ClaimPair>,
        judged: List<JudgedPairRecord>,
        props: AppProperties.Stage3,
    ): AssembleOutcome {
        val byId = claims.associateBy { it.claimId }

        // ---- clustering: min-root union-find over REPEATS (§11.7) ------------------
        val parent = claims.map { it.claimId }.associateWithTo(mutableMapOf()) { it }
        fun find(x: String): String {
            var root = x
            while (parent.getValue(root) != root) root = parent.getValue(root)
            var cur = x
            while (cur != root) {
                val next = parent.getValue(cur)
                parent[cur] = root
                cur = next
            }
            return root
        }
        repeats
            .filter { it.a in parent && it.b in parent }
            .forEach { (a, b) ->
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
            }
        val components =
            claims.groupBy { find(it.claimId) }.toSortedMap() // singletons by construction

        val facts =
            components.map { (root, members) ->
                val sorted = members.sortedBy { it.claimId }
                val exemplar =
                    sorted.minWithOrNull(
                        compareBy<ClaimToAssemble> { sourcePriority(it.sourceClass) }
                            .thenByDescending { it.text.length }
                            .thenBy { it.claimId }
                    )!!
                val kindAndSlot = kindAndSlot(exemplar, sorted, props)
                val dates = sorted.mapNotNull { it.claimedDate }.filter { it.isNotBlank() }
                AssembledFact(
                    factId = "fact:$root",
                    exemplarClaimId = exemplar.claimId,
                    label = exemplar.text,
                    factKind = kindAndSlot.first,
                    slot = kindAndSlot.second,
                    validFrom = dates.minOrNull(),
                    validTo = dates.maxOrNull(),
                    datePrecision = precisionOf(dates),
                    anchored = sorted.any { it.sourceClass == "DOCUMENTARY" },
                    memberClaimIds = sorted.map { it.claimId },
                )
            }
        val factByClaim = facts.flatMap { f -> f.memberClaimIds.map { it to f } }.toMap()

        // ---- edge lifting with the temporal gate (§11.7) ---------------------------
        var gated = 0L
        var sameFact = 0L
        data class LiftKey(val from: String, val to: String, val relation: JudgeRelation)
        val contributions = sortedMapOf<String, MutableList<JudgedPairRecord>>()
        val liftKeys = mutableMapOf<String, LiftKey>()
        judged
            .filter {
                it.relation == JudgeRelation.CORROBORATES ||
                    it.relation == JudgeRelation.CONTRADICTS
            }
            .sortedWith(compareBy({ it.pair.a }, { it.pair.b }))
            .forEach { record ->
                val fa = factByClaim[record.pair.a] ?: return@forEach
                val fb = factByClaim[record.pair.b] ?: return@forEach
                if (fa.factId == fb.factId) {
                    sameFact++
                    return@forEach
                }
                val (from, to) = if (fa.factId <= fb.factId) fa to fb else fb to fa
                val key = "${from.factId}|${to.factId}|${record.relation}"
                contributions.getOrPut(key) { mutableListOf() } += record
                liftKeys[key] = LiftKey(from.factId, to.factId, record.relation)
            }

        val factById = facts.associateBy { it.factId }
        val edges =
            contributions.mapNotNull { (key, records) ->
                val lift = liftKeys.getValue(key)
                val from = factById.getValue(lift.from)
                val to = factById.getValue(lift.to)
                val best =
                    records.maxWithOrNull(
                        compareBy<JudgedPairRecord> { it.confidence }
                            .thenBy { it.pair.a }
                            .thenBy { it.pair.b }
                    )!!
                if (lift.relation == JudgeRelation.CONTRADICTS) {
                    val overlap = intervalsOverlap(from, to)
                    val bothUnknown = from.datePrecision == "NONE" && to.datePrecision == "NONE"
                    val keep =
                        overlap == true ||
                            (bothUnknown && records.any { assertsSameSpan(it.temporalNote) })
                    if (!keep) {
                        gated++
                        return@mapNotNull null
                    }
                }
                val bestCtx =
                    records
                        .filter { it.ctxJudged }
                        .maxWithOrNull(
                            compareBy<JudgedPairRecord> { it.confidence }
                                .thenBy { it.pair.a }
                                .thenBy { it.pair.b }
                        )
                val contradicts = lift.relation == JudgeRelation.CONTRADICTS
                FactEdge(
                    fromFactId = lift.from,
                    toFactId = lift.to,
                    relation = lift.relation.name,
                    confidence = records.maxOf { it.confidence },
                    votesJson = best.votesJson,
                    rationale = best.rationale,
                    temporalNote = best.temporalNote,
                    judgeModel = best.judgeModel,
                    promptHash = best.promptHash,
                    withContext = bestCtx != null,
                    ctxRelation = bestCtx?.ctxRelation?.name,
                    ctxConfidence = bestCtx?.ctxConfidence,
                    explained = contradicts && records.any { it.ctxExplanationRelevant == true },
                    temporalOverlap = if (contradicts) intervalsOverlap(from, to) == true else null,
                    severity = if (contradicts) records.maxOf { it.confidence } else null,
                    reviewStatus = if (contradicts) "PROPOSED" else null,
                    viaEntities = records.flatMap { it.sharedEntities }.distinct().sorted(),
                    contributingPairs = records.map { "${it.pair.a}↔${it.pair.b}" }.sorted(),
                )
            }

        // ---- SUCCEEDS: consecutive same-slot STATE facts, disjoint intervals (§11.7) ---
        val succeeds =
            facts
                .filter { it.factKind == "STATE" && it.slot != null && it.validFrom != null }
                .groupBy { it.slot!! }
                .toSortedMap()
                .flatMap { (slot, lane) ->
                    lane
                        .sortedWith(compareBy({ it.validFrom }, { it.factId }))
                        .zipWithNext()
                        .filter { (earlier, later) -> intervalsOverlap(earlier, later) == false }
                        .map { (earlier, later) ->
                            SucceedsEdge(
                                fromFactId = earlier.factId,
                                toFactId = later.factId,
                                slot = slot,
                                gapDays = gapDays(earlier.validTo, later.validFrom),
                            )
                        }
                }

        return AssembleOutcome(
            facts = facts,
            edges = edges,
            succeeds = succeeds,
            counters =
                AssembleCounters(
                    facts = facts.size.toLong(),
                    factsState = facts.count { it.factKind == "STATE" }.toLong(),
                    factsEvent = facts.count { it.factKind == "EVENT" }.toLong(),
                    factsTimeless = facts.count { it.factKind == "TIMELESS" }.toLong(),
                    factCorroborates = edges.count { it.relation == "CORROBORATES" }.toLong(),
                    factContradicts = edges.count { it.relation == "CONTRADICTS" }.toLong(),
                    contradictionsGated = gated,
                    succeedsEdges = succeeds.size.toLong(),
                    sameFactPairsDropped = sameFact,
                ),
        )
    }

    /** Exemplar priority (§11.7): documentary > endorsement > public > self; unknown last. */
    private fun sourcePriority(sourceClass: String?): Int =
        when (sourceClass) {
            "DOCUMENTARY" -> 0
            "ENDORSEMENT" -> 1
            "EVENT_CAPTURE" -> 2
            "PUBLIC_PROFILE" -> 3
            "SELF" -> 4
            else -> 5
        }

    /** Fact kind + STATE slot per the class-KDoc pattern table, on the exemplar + member union. */
    private fun kindAndSlot(
        exemplar: ClaimToAssemble,
        members: List<ClaimToAssemble>,
        props: AppProperties.Stage3,
    ): Pair<String, String?> {
        val mentionTypes = members.flatMap { it.mentionTypes }.toSet()
        val slots = props.stateSlotTypes.map { it.uppercase() }.toSet()
        val slot =
            when {
                "EMPLOYER" in slots &&
                    "ORG" in mentionTypes &&
                    (exemplar.type == "IDENTITY" ||
                        (exemplar.type == "EPISODE" &&
                            mentionTypes.none {
                                it == "PROJECT" || it == "SKILL" || it == "CREDENTIAL"
                            })) -> "EMPLOYER"
                "EDUCATION_ENROLLMENT" in slots &&
                    exemplar.type == "IDENTITY" &&
                    "INSTITUTION" in mentionTypes -> "EDUCATION_ENROLLMENT"
                "RESIDENCE" in slots && exemplar.type == "IDENTITY" && "PLACE" in mentionTypes ->
                    "RESIDENCE"
                else -> null
            }
        if (slot != null) return "STATE" to slot
        val dated = members.any { !it.claimedDate.isNullOrBlank() }
        return if (exemplar.type == "EPISODE" && dated) "EVENT" to null else "TIMELESS" to null
    }

    private fun precisionOf(dates: List<String>): String {
        if (dates.isEmpty()) return "NONE"
        return when (dates.minOf { it.length }) {
            in 0..4 -> "YEAR"
            in 5..7 -> "MONTH"
            else -> "DAY"
        }
    }

    /**
     * Interval overlap at the coarsest shared precision (ISO prefixes compare lexically after
     * truncation). Returns null when either side is undated — the gate treats one-sided unknowns as
     * non-overlap (ambiguity never escalates into a penalty, §11.6 posture).
     *
     * EVENT–EVENT pairs compare at YEAR granularity regardless of stored precision: episodes
     * conflict at episode scale (the §11.5 window's year reasoning; the worked example's F3/F4 are
     * "both EVENT 2019"), and two day-dated accounts of one migration must not slip the gate as
     * "disjoint". STATE pairs keep full precision — a within-year job change is a sequence.
     */
    internal fun intervalsOverlap(a: AssembledFact, b: AssembledFact): Boolean? {
        val aFrom = a.validFrom ?: return null
        val aTo = a.validTo ?: aFrom
        val bFrom = b.validFrom ?: return null
        val bTo = b.validTo ?: bFrom
        val bothEvents = a.factKind == "EVENT" && b.factKind == "EVENT"
        val precision =
            if (bothEvents) 4 else minOf(aFrom.length, aTo.length, bFrom.length, bTo.length)
        fun cut(s: String) = s.take(precision)
        val aBeforeB = cut(aTo) < cut(bFrom)
        val bBeforeA = cut(bTo) < cut(aFrom)
        return !(aBeforeB || bBeforeA)
    }

    /**
     * The gate's both-undated escape hatch: the judge's temporalNote must positively assert a
     * same-span conflict. As-built heuristic: a note that speaks of the "same" span/period/time —
     * the prompt instructs exactly that phrasing when CONTRADICTS hinges on time.
     */
    internal fun assertsSameSpan(temporalNote: String?): Boolean =
        temporalNote?.contains("same", ignoreCase = true) == true

    /** Day-precision gap between STATE facts; anything coarser than DAY is a guess → null. */
    private fun gapDays(earlierTo: String?, laterFrom: String?): Long? {
        if (earlierTo?.length != 10 || laterFrom?.length != 10) return null
        return runCatching {
                java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.parse(earlierTo),
                    java.time.LocalDate.parse(laterFrom),
                )
            }
            .getOrNull()
    }
}

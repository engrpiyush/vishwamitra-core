package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

// ---- the graph snapshot the scorer reads (assembled by Stage3GraphRepository) ----------

/** One claim's scoring inputs. `extractionConfidence` is deliberately absent (Stage 2 §7.1). */
data class ClaimSnapshot(
    val claimId: String,
    val factId: String,
    val type: String?,
    val tierSeed: String?,
    val sourceClass: String?,
    /** STATED / INFERRED — δ applies to INFERRED. */
    val basis: String?,
    val favorability: Double?,
    val claimedDate: String?,
    val attestorKey: String?,
    val assetId: String?,
)

data class FactSnapshot(
    val factId: String,
    val factKind: String,
    val exemplarClaimId: String,
    val anchored: Boolean,
)

data class AttestorSnapshot(val attestorKey: String, val trustPrior: Double, val trust: Double)

/** One lifted fact edge with both verdict variants (§11.9's dual-evaluation inputs). */
data class EdgeSnapshot(
    val fromFactId: String,
    val toFactId: String,
    val relation: String,
    val confidence: Double,
    val withContext: Boolean,
    val ctxRelation: String?,
    val ctxConfidence: Double?,
    val explained: Boolean,
)

data class GraphSnapshot(
    val claims: List<ClaimSnapshot>,
    val facts: List<FactSnapshot>,
    val attestors: List<AttestorSnapshot>,
    val edges: List<EdgeSnapshot>,
)

// ---- outputs ---------------------------------------------------------------------------

data class FactSignals(
    val support: Double,
    val conflict: Double,
    /** Distinct dependence groups ÷ assertions. */
    val independence: Double,
    /** Volatile kinds: weighted evidence age factor; everything else publishes 1.0. */
    val recency: Double,
    /** Σ_groups dep(group) + Σ effective supporter weights. */
    val evidenceMass: Double,
)

data class FactScore(
    val factId: String,
    val belief: Double,
    val beliefBare: Double,
    val signals: FactSignals,
)

data class ClaimScore(
    val claimId: String,
    val factId: String,
    val prior: Double,
    val score: Double,
    val scoreBare: Double,
    /** Refreshed tier from the published score (`tier-high`/`tier-medium` bands). */
    val tier: String,
)

data class AttestorTrustUpdate(val attestorKey: String, val trust: Double, val factCount: Int)

data class ScoreOutcome(
    val claims: List<ClaimScore>,
    val facts: List<FactScore>,
    val trustUpdates: List<AttestorTrustUpdate>,
    val converged: Boolean,
    val iterations: Int,
    /** Claims whose explained score had to be lifted to `scoreBare` (I2 enforced; 0 = healthy). */
    val i2Clamped: Long,
)

/**
 * The §11.8 trust-propagation fixed point — pure Kotlin, zero I/O, deterministic given a snapshot
 * + params (invariant I5): iteration order is sorted, ties break by id, and "now" is the [asOf]
 *   parameter, never the wall clock.
 *
 * Structure per pass: claim priors (step 0) → per-fact noisy-OR over dependence groups (step 1) →
 * damped log-odds evidence accumulation to convergence (steps 2+4) → policy caps (step 5) → claim
 * scores + signals (step 6). The §11.9 dual pass runs the whole thing twice: bare (withContext
 * verdicts ignored, `explained` forced false) → `scoreBare`; explained (ctx CONTRADICTS verdicts
 * substituted, μ-mitigation on affirmed relevance) → `score`.
 *
 * As-built notes (LLD §11.8 refinements the worked example pins down):
 * - **Trust is static within a run**: step 1's `T/T0` rescale reads the graph-persisted trust
 *   (accrued from previous runs — the cross-subject §6-row-3 effect), and step 3's shrinkage update
 *   runs **once at convergence** and persists. Intra-run trust feedback would push F1/F2 off the
 *   §11.8 published numbers.
 * - **CORROBORATES scores identically in both passes** (bare verdict only): a subject-authored
 *   explanation must never add corroboration mass (§11.9 invariant), so ctx variants only ever act
 *   on CONTRADICTS — neutralizing (ctx ≠ CONTRADICTS) or mitigating (μ on affirmed relevance), with
 *   the effective confidence capped at the bare value.
 * - **I2 is enforced**: per-claim `score` is lifted to `scoreBare` if the fixed point ever lands
 *   below it (possible only through second-order rel() feedback); [ScoreOutcome.i2Clamped] counts
 *   the lifts so a systematic violation is visible instead of silent.
 */
object Scorer {

    fun score(snapshot: GraphSnapshot, params: ScorerParams): ScoreOutcome {
        val bare = fixedPoint(snapshot, params, explainedPass = false)
        val explained = fixedPoint(snapshot, params, explainedPass = true)

        var clamped = 0L
        val factScores =
            snapshot.facts
                .sortedBy { it.factId }
                .map { fact ->
                    val b = explained.belief.getValue(fact.factId)
                    val bBare = bare.belief.getValue(fact.factId)
                    FactScore(
                        factId = fact.factId,
                        belief = b,
                        beliefBare = bBare,
                        // Signals expose the BARE evidence landscape (the worked example lists
                        // c5's conflict as the un-mitigated 0.17): score-vs-scoreBare carries the
                        // sidecar recovery, the terms show what it recovered from.
                        signals = bare.signals.getValue(fact.factId),
                    )
                }
        val factById = factScores.associateBy { it.factId }
        val claimScores =
            snapshot.claims
                .sortedBy { it.claimId }
                .map { claim ->
                    val fact = factById.getValue(claim.factId)
                    val raw = fact.belief
                    val scoreBare = fact.beliefBare
                    val published =
                        if (raw < scoreBare) {
                            clamped++
                            scoreBare
                        } else raw
                    ClaimScore(
                        claimId = claim.claimId,
                        factId = claim.factId,
                        prior = prior(claim, params),
                        score = published,
                        scoreBare = scoreBare,
                        tier =
                            when {
                                published >= params.stage3.tierHigh -> "HIGH"
                                published >= params.stage3.tierMedium -> "MEDIUM"
                                else -> "LOW"
                            },
                    )
                }

        // Step 3 once at convergence, over the published (explained) beliefs.
        val trustUpdates =
            snapshot.attestors
                .sortedBy { it.attestorKey }
                .mapNotNull { attestor ->
                    val factIds =
                        snapshot.claims
                            .filter { it.attestorKey == attestor.attestorKey }
                            .map { it.factId }
                            .distinct()
                    if (factIds.isEmpty()) return@mapNotNull null
                    val n = factIds.size
                    val meanB = factIds.map { explained.belief.getValue(it) }.average()
                    val m = params.stage3.trustShrinkage
                    AttestorTrustUpdate(
                        attestorKey = attestor.attestorKey,
                        trust = (n * meanB + m * attestor.trustPrior) / (n + m),
                        factCount = n,
                    )
                }

        return ScoreOutcome(
            claims = claimScores,
            facts = factScores,
            trustUpdates = trustUpdates,
            converged = bare.converged && explained.converged,
            iterations = maxOf(bare.iterations, explained.iterations),
            i2Clamped = clamped,
        )
    }

    // ---- one pass -----------------------------------------------------------------

    private class PassResult(
        val belief: Map<String, Double>,
        val signals: Map<String, FactSignals>,
        val converged: Boolean,
        val iterations: Int,
    )

    private fun fixedPoint(
        snapshot: GraphSnapshot,
        params: ScorerParams,
        explainedPass: Boolean,
    ): PassResult {
        val s3 = params.stage3
        val factIds = snapshot.facts.map { it.factId }.sorted()
        val factById = snapshot.facts.associateBy { it.factId }
        val claimsByFact = snapshot.claims.groupBy { it.factId }
        val trustByKey = snapshot.attestors.associateBy { it.attestorKey }
        val claimTypeById = snapshot.claims.associate { it.claimId to it.type }

        // Step 0 + step 1 (static within the run — see class KDoc).
        data class Group(val key: String, val strength: Double, val assetIds: Set<String>)
        val groupsByFact =
            factIds.associateWith { factId ->
                val members = claimsByFact[factId].orEmpty()
                members
                    .groupBy { dependenceKey(it) }
                    .toSortedMap()
                    .map { (key, claims) ->
                        Group(
                            key = key,
                            strength =
                                claims.maxOf { c ->
                                    val attestor = c.attestorKey?.let { trustByKey[it] }
                                    val scale =
                                        if (attestor != null && attestor.trustPrior > 0.0)
                                            (attestor.trust / attestor.trustPrior).coerceIn(
                                                0.5,
                                                1.5
                                            )
                                        else 1.0
                                    prior(c, params) * scale
                                },
                            assetIds = claims.mapNotNull { it.assetId }.toSet(),
                        )
                    }
                    .sortedWith(compareByDescending<Group> { it.strength }.thenBy { it.key })
            }
        val depByFact =
            groupsByFact.mapValues { (_, groups) ->
                val seenAssets = mutableSetOf<String>()
                groups.map { g ->
                    // Partial dependence (§11.8): a group whose assets all rode in with an
                    // earlier, stronger group (two endorsers on one call) is λ-damped.
                    val dep =
                        if (g.assetIds.isNotEmpty() && seenAssets.containsAll(g.assetIds))
                            s3.dependenceDamping
                        else 1.0
                    seenAssets += g.assetIds
                    dep
                }
            }
        val b0 =
            factIds.associateWith { factId ->
                val groups = groupsByFact.getValue(factId)
                val deps = depByFact.getValue(factId)
                if (groups.isEmpty()) 0.5 // a fact with no assertions cannot exist post-ASSEMBLE
                else
                    1.0 -
                        groups
                            .mapIndexed { i, g -> 1.0 - (g.strength.coerceIn(0.0, 1.0) * deps[i]) }
                            .fold(1.0) { acc, term -> acc * term }
            }
        val strongestGroupKey =
            factIds.associateWith { groupsByFact.getValue(it).firstOrNull()?.key ?: it }

        // Edge activation for this pass (§11.9).
        data class ActiveEdge(val other: String, val conf: Double, val mitigation: Double)
        val supporters = factIds.associateWith { mutableListOf<ActiveEdge>() }
        val contradictors = factIds.associateWith { mutableListOf<ActiveEdge>() }
        snapshot.edges
            .sortedWith(compareBy({ it.fromFactId }, { it.toFactId }, { it.relation }))
            .forEach { edge ->
                if (edge.fromFactId !in factById || edge.toFactId !in factById) return@forEach
                when (edge.relation) {
                    "CORROBORATES" -> {
                        supporters.getValue(edge.fromFactId) +=
                            ActiveEdge(edge.toFactId, edge.confidence, 1.0)
                        supporters.getValue(edge.toFactId) +=
                            ActiveEdge(edge.fromFactId, edge.confidence, 1.0)
                    }
                    "CONTRADICTS" -> {
                        val conf: Double
                        val mitigation: Double
                        if (explainedPass && edge.withContext) {
                            if (edge.ctxRelation != "CONTRADICTS") return@forEach // neutralized
                            conf = minOf(edge.confidence, edge.ctxConfidence ?: edge.confidence)
                            mitigation =
                                1.0 - s3.explanationMitigation * (if (edge.explained) 1.0 else 0.0)
                        } else {
                            conf = edge.confidence
                            mitigation = 1.0 // bare pass: explained forced false
                        }
                        contradictors.getValue(edge.fromFactId) +=
                            ActiveEdge(edge.toFactId, conf, mitigation)
                        contradictors.getValue(edge.toFactId) +=
                            ActiveEdge(edge.fromFactId, conf, mitigation)
                    }
                }
            }

        // Steps 2 + 4: damped log-odds iteration.
        var belief = b0.toMutableMap()
        var s = factIds.associateWithTo(mutableMapOf()) { logit(b0.getValue(it)) }
        var converged = false
        var iterations = 0
        val support = factIds.associateWithTo(mutableMapOf()) { 0.0 }
        val conflict = factIds.associateWithTo(mutableMapOf()) { 0.0 }
        val supporterMass = factIds.associateWithTo(mutableMapOf()) { 0.0 }
        while (iterations < s3.maxIterations) {
            iterations++
            var maxDelta = 0.0
            val nextS = mutableMapOf<String, Double>()
            val nextB = mutableMapOf<String, Double>()
            factIds.forEach { factId ->
                val fact = factById.getValue(factId)
                val halfLife = volatileHalfLife(fact, claimTypeById, params)
                val ranked =
                    supporters
                        .getValue(factId)
                        .map { edge ->
                            val rec =
                                if (halfLife != null)
                                    ageFactor(
                                        newestDate(edge.other, claimsByFact),
                                        halfLife,
                                        params
                                    )
                                else 1.0
                            edge to
                                s3.corroborationWeight *
                                    edge.conf *
                                    rel(belief.getValue(edge.other)) *
                                    rec
                        }
                        .sortedWith(
                            compareByDescending<Pair<ActiveEdge, Double>> { it.second }
                                .thenBy { it.first.other }
                        )
                var supportSum = 0.0
                var supporterWeights = 0.0
                val rankWithinGroup = mutableMapOf<String, Int>()
                ranked.forEach { (edge, term) ->
                    val group = strongestGroupKey.getValue(edge.other)
                    val rank = rankWithinGroup.getOrDefault(group, 0)
                    rankWithinGroup[group] = rank + 1
                    val weighted = term * s3.dependenceDamping.pow(rank)
                    supportSum += weighted
                    supporterWeights +=
                        s3.corroborationWeight * edge.conf * s3.dependenceDamping.pow(rank)
                }
                var conflictSum = 0.0
                contradictors.getValue(factId).forEach { edge ->
                    conflictSum +=
                        s3.contradictionWeight *
                            edge.conf *
                            rel(belief.getValue(edge.other)) *
                            edge.mitigation
                }
                var sNew = logit(b0.getValue(factId)) + supportSum - conflictSum
                val sPrev = s.getValue(factId)
                if (fact.anchored) sNew = sPrev + s3.anchorPlasticity * (sNew - sPrev)
                val sDamped = (1 - s3.damping) * sPrev + s3.damping * sNew
                val bNew = sigmoid(sDamped)
                maxDelta = maxOf(maxDelta, kotlin.math.abs(bNew - belief.getValue(factId)))
                nextS[factId] = sDamped
                nextB[factId] = bNew
                support[factId] = supportSum
                conflict[factId] = conflictSum
                supporterMass[factId] = supporterWeights
            }
            s = nextS
            belief = nextB
            if (maxDelta < s3.epsilon) {
                converged = true
                break
            }
        }

        // Step 5: policy caps, then the universal clamp.
        val capped =
            factIds.associateWith { factId ->
                val members = claimsByFact[factId].orEmpty()
                val allSelf = members.isNotEmpty() && members.all { it.sourceClass == "SELF" }
                val againstInterest =
                    members.any {
                        it.favorability != null && it.favorability < params.favorabilityThreshold
                    }
                val hasSupport = supporters.getValue(factId).isNotEmpty()
                var b = belief.getValue(factId)
                if (allSelf && !againstInterest && !hasSupport) b = minOf(b, s3.selfPraiseCeiling)
                b.coerceIn(0.02, 0.98)
            }

        // Step 6 signal assembly.
        val signals =
            factIds.associateWith { factId ->
                val fact = factById.getValue(factId)
                val groups = groupsByFact.getValue(factId)
                val deps = depByFact.getValue(factId)
                val members = claimsByFact[factId].orEmpty()
                val halfLife = volatileHalfLife(fact, claimTypeById, params)
                FactSignals(
                    support = support.getValue(factId),
                    conflict = conflict.getValue(factId),
                    independence =
                        if (members.isEmpty()) 0.0 else groups.size.toDouble() / members.size,
                    recency =
                        if (halfLife == null) 1.0
                        else
                            members
                                .map { ageFactor(it.claimedDate, halfLife, params) }
                                .ifEmpty { listOf(1.0) }
                                .average(),
                    evidenceMass = deps.sum() + supporterMass.getValue(factId),
                )
            }
        return PassResult(capped, signals, converged, iterations)
    }

    // ---- pieces --------------------------------------------------------------------

    /** Step 0 (§11.8): tier-seed base in log-odds, β against interest, δ for INFERRED. */
    internal fun prior(claim: ClaimSnapshot, params: ScorerParams): Double {
        val base =
            when (claim.tierSeed) {
                "HIGH" -> 0.85
                "MEDIUM" -> 0.60
                else -> 0.35
            }
        var s = logit(base)
        if (
            claim.sourceClass == "SELF" &&
                claim.favorability != null &&
                claim.favorability < params.favorabilityThreshold
        )
            s += params.stage3.againstInterestBonus
        if (claim.basis == "INFERRED") s += params.stage3.inferredPenalty
        return sigmoid(s)
    }

    /** The §11.8 dependence key: all SELF assertions collapse to one group; else the attestor. */
    private fun dependenceKey(claim: ClaimSnapshot): String =
        if (claim.sourceClass == "SELF") "self" else claim.attestorKey ?: "unattested"

    /** `rel(B) = clip(2B − 1, 0, 1)` — a voice counts only insofar as it is itself believed. */
    internal fun rel(b: Double): Double = (2 * b - 1).coerceIn(0.0, 1.0)

    private fun volatileHalfLife(
        fact: FactSnapshot,
        claimTypeById: Map<String, String?>,
        params: ScorerParams,
    ): Double? =
        claimTypeById[fact.exemplarClaimId]?.let { params.stage3.volatileHalfLifeYears[it] }

    private fun newestDate(
        factId: String,
        claimsByFact: Map<String, List<ClaimSnapshot>>,
    ): String? = claimsByFact[factId].orEmpty().mapNotNull { it.claimedDate }.maxOrNull()

    /** `0.5^(Δyears/halfLife)`; undated evidence does not decay. */
    private fun ageFactor(date: String?, halfLife: Double, params: ScorerParams): Double {
        val year = date?.take(4)?.toIntOrNull() ?: return 1.0
        val deltaYears = (params.asOf.year - year).coerceAtLeast(0)
        return 0.5.pow(deltaYears / halfLife)
    }

    internal fun logit(p: Double): Double {
        val clamped = p.coerceIn(1e-9, 1 - 1e-9)
        return ln(clamped / (1 - clamped))
    }

    internal fun sigmoid(s: Double): Double = 1.0 / (1.0 + exp(-s))
}

/**
 * Everything the scorer may read beyond the snapshot — all §8.2 values plus the Stage 2
 * favorability threshold (the "statement against interest" line) and the [asOf] clock (parameter,
 * not wall time, so scoring is reproducible — I5).
 */
data class ScorerParams(
    val stage3: AppProperties.Stage3,
    val favorabilityThreshold: Double,
    val asOf: LocalDate,
)

package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.serialization.Json
import kotlin.math.roundToInt

// ---- outputs (Stage 3.5 LLD §3 — the Subject Authenticity Index) -----------------------

/** The five components and their multiplicative dampers — the dashboard's "how it derived". */
data class SubjectScoreComponents(
    /**
     * B — evidence-weighted fact belief (`Σ w_f·b_f / Σ w_f`, `w_f = independence × sat(mass)`).
     */
    val weightedBelief: Double,
    /** D — mean mass saturation; the thin-evidence sag. */
    val evidenceDepth: Double,
    /** I — 1 − self-only fact share. */
    val independentCoverage: Double,
    /** V — attestor count / kind / documentary-coverage mix. */
    val sourceDiversity: Double,
    /** C — saturated residual contradiction mass (explained edges keep the (1−μ) residual). */
    val contradictionDrag: Double,
    /** Φ_D = 1 − λ_D·(1−D). */
    val depthFactor: Double,
    /** Φ_I = 1 − λ_I·(1−I). */
    val coverageFactor: Double,
    /** Φ_V = 1 − λ_V·(1−V). */
    val diversityFactor: Double,
    /** Φ_C = 1 − λ_C·C. */
    val contradictionFactor: Double,
)

/** The raw counts behind the components — the dashboard tiles and the PDF appendix. */
data class SubjectScoreInputs(
    val factCount: Int,
    val claimCount: Int,
    val selfOnlyFactCount: Int,
    /** Distinct attestors with kind ≠ SUBJECT. */
    val independentAttestorCount: Int,
    /** Distinct attestor kinds present (SUBJECT / ISSUER / ENDORSER). */
    val attestorKindCount: Int,
    /** kind → distinct attestor count. */
    val attestorsByKind: Map<String, Int>,
    /** Fraction of facts anchored or carrying a DOCUMENTARY claim. */
    val documentaryFactFraction: Double,
    /** Deduped CORROBORATES edges. */
    val corroborationCount: Int,
    /** Deduped CONTRADICTS edges (all review states). */
    val contradictionCount: Int,
    val explainedContradictionCount: Int,
    val confirmedContradictionCount: Int,
    val proposedContradictionCount: Int,
    val meanEvidenceMass: Double,
    val medianEvidenceMass: Double,
    val anchoredFactCount: Int,
)

data class SubjectScore(
    /** SAI in [0,1]. */
    val score: Double,
    /** `round(100·score)` — the headline number. */
    val display: Int,
    /** STRONG / GOOD / MODERATE / WEAK / UNSUPPORTED. */
    val band: String,
    val components: SubjectScoreComponents,
    val inputs: SubjectScoreInputs,
)

// ---- internal fact aggregation (the testable seam) --------------------------------------

/** One fact folded out of its member claim rows. */
internal data class FactAggregate(
    val factId: String,
    val label: String,
    val slot: String?,
    val claimCount: Int,
    val belief: Double,
    val independence: Double,
    val evidenceMass: Double,
    val selfOnly: Boolean,
    val documentary: Boolean,
)

/** One deduped judged edge (unordered fact pair). */
internal data class EdgeAggregate(
    val pairKey: String,
    val relation: String,
    val fromFactId: String,
    val toFactId: String,
    val confidence: Double,
    val explained: Boolean,
    val reviewStatus: String?,
    val rationale: String?,
    val viaEntities: List<String>,
)

/**
 * The Stage 3.5 LLD §3 Subject Authenticity Index — the subject-level aggregate, deliberately
 * STRICTER than the per-claim vector: it aggregates over facts (cluster size cannot inflate it),
 * weights by independence × evidence-mass saturation rather than the ceiling-clipped scores, and
 * sags on thin evidence even with zero contradictions.
 *
 * Pure and deterministic like [Scorer] (invariant I-A1): zero I/O, sorted iteration, no clock
 * (recency is already inside the beliefs). Input is the §21 A.3 read — one
 * `scoresReadback(subjectId)` round-trip carries every number this needs.
 */
object SubjectScorer {

    fun score(rows: List<ScoredClaimView>, params: AppProperties.Stage3): SubjectScore {
        val facts = factAggregates(rows)
        if (facts.isEmpty())
            return SubjectScore(
                score = 0.0,
                display = 0,
                band = "UNSUPPORTED",
                // No evidence at all: components zero, dampers neutral (nothing to damp).
                components = SubjectScoreComponents(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0),
                inputs = emptyInputs(),
            )

        val n = facts.size
        fun sat(mass: Double): Double {
            val m = mass.coerceAtLeast(0.0)
            return m / (m + params.aggMassMidpoint)
        }

        // B — evidence-weighted belief; w_f = max(independence, 0.05) × sat(mass).
        val weights = facts.map { maxOf(it.independence, 0.05) * sat(it.evidenceMass) }
        val totalW = weights.sum()
        val weightedBelief =
            if (totalW > 0.0) facts.mapIndexed { i, f -> weights[i] * f.belief }.sum() / totalW
            else facts.sumOf { it.belief } / n

        // D — mean saturation (the sag that fires even with zero contradictions).
        val evidenceDepth = facts.sumOf { sat(it.evidenceMass) } / n

        // I — self-only coverage penalty.
        val selfOnlyCount = facts.count { it.selfOnly }
        val independentCoverage = 1.0 - selfOnlyCount.toDouble() / n

        // V — source diversity: independent attestors, kind spread, documentary coverage.
        val attestors =
            rows
                .mapNotNull { row -> row.attestorKey?.let { it to (row.attestorKind ?: "?") } }
                .distinct()
        val attestorsByKind =
            attestors.groupBy({ it.second }, { it.first }).mapValues { it.value.distinct().size }
        val independentAttestors = attestors.count { it.second != "SUBJECT" }
        val kindCount = attestorsByKind.keys.size
        val documentaryFraction = facts.count { it.documentary }.toDouble() / n
        val sourceDiversity =
            (params.aggDiversityAttestorShare *
                    minOf(1.0, independentAttestors.toDouble() / params.aggAttestorTarget) +
                    params.aggDiversityKindShare * ((kindCount - 1).coerceIn(0, 2) / 2.0) +
                    params.aggDiversityDocShare *
                        minOf(1.0, documentaryFraction / params.aggDocCoverageTarget))
                .coerceIn(0.0, 1.0)

        // C — residual contradiction mass, explained edges keep exactly the §11.9 (1−μ) residual.
        val edges = edgeAggregates(rows)
        val contradictions = edges.filter { it.relation == "CONTRADICTS" }
        val residual =
            contradictions.sumOf {
                it.confidence * (if (it.explained) 1.0 - params.explanationMitigation else 1.0)
            }
        val contradictionDrag = residual / (residual + params.aggContradictionMidpoint)

        val depthFactor = 1.0 - params.aggDepthWeight * (1.0 - evidenceDepth)
        val coverageFactor = 1.0 - params.aggSelfOnlyPenalty * (1.0 - independentCoverage)
        val diversityFactor = 1.0 - params.aggDiversityWeight * (1.0 - sourceDiversity)
        val contradictionFactor = 1.0 - params.aggContradictionWeight * contradictionDrag

        val sai =
            (weightedBelief * depthFactor * coverageFactor * diversityFactor * contradictionFactor)
                .coerceIn(0.0, 1.0)

        val masses = facts.map { it.evidenceMass }.sorted()
        val median = if (n % 2 == 1) masses[n / 2] else (masses[n / 2 - 1] + masses[n / 2]) / 2.0

        return SubjectScore(
            score = sai,
            display = (sai * 100).roundToInt(),
            band = band(sai, params),
            components =
                SubjectScoreComponents(
                    weightedBelief = weightedBelief,
                    evidenceDepth = evidenceDepth,
                    independentCoverage = independentCoverage,
                    sourceDiversity = sourceDiversity,
                    contradictionDrag = contradictionDrag,
                    depthFactor = depthFactor,
                    coverageFactor = coverageFactor,
                    diversityFactor = diversityFactor,
                    contradictionFactor = contradictionFactor,
                ),
            inputs =
                SubjectScoreInputs(
                    factCount = n,
                    claimCount = rows.size,
                    selfOnlyFactCount = selfOnlyCount,
                    independentAttestorCount = independentAttestors,
                    attestorKindCount = kindCount,
                    attestorsByKind = attestorsByKind.toSortedMap(),
                    documentaryFactFraction = documentaryFraction,
                    corroborationCount = edges.count { it.relation == "CORROBORATES" },
                    contradictionCount = contradictions.size,
                    explainedContradictionCount = contradictions.count { it.explained },
                    confirmedContradictionCount =
                        contradictions.count { it.reviewStatus == "CONFIRMED" },
                    proposedContradictionCount =
                        contradictions.count { it.reviewStatus == "PROPOSED" && !it.explained },
                    meanEvidenceMass = masses.sum() / n,
                    medianEvidenceMass = median,
                    anchoredFactCount =
                        rows.filter { it.anchored }.map { it.factId }.distinct().size,
                ),
        )
    }

    internal fun band(sai: Double, params: AppProperties.Stage3): String =
        when {
            sai >= params.aggBandStrong -> "STRONG"
            sai >= params.aggBandGood -> "GOOD"
            sai >= params.aggBandModerate -> "MODERATE"
            sai >= params.aggBandWeak -> "WEAK"
            else -> "UNSUPPORTED"
        }

    /**
     * Fold claim rows into per-fact aggregates. Fact-level values (belief, the §3.2 fact signals)
     * are replicated on every member row — read once per fact; missing signals fall back to the
     * thinnest posture (independence 1.0, mass 1.0 = one lone assertion).
     */
    internal fun factAggregates(rows: List<ScoredClaimView>): List<FactAggregate> =
        rows
            .groupBy { it.factId }
            .toSortedMap()
            .map { (factId, members) ->
                val signals = parseSignals(members.firstNotNullOfOrNull { it.signalsJson })
                val corroborated =
                    members.any { row -> row.edges.any { it["relation"] == "CORROBORATES" } }
                FactAggregate(
                    factId = factId,
                    label = members.first().factLabel,
                    slot = members.firstNotNullOfOrNull { it.slot },
                    claimCount = members.size,
                    belief =
                        members.firstNotNullOfOrNull { it.belief }
                            ?: members.firstNotNullOfOrNull { it.beliefBare }
                            ?: members.first().score,
                    independence = signals["independence"] ?: 1.0,
                    evidenceMass = signals["evidenceMass"] ?: 1.0,
                    selfOnly = members.all { it.sourceClass == "SELF" } && !corroborated,
                    documentary =
                        members.any { it.anchored } ||
                            members.any { it.sourceClass == "DOCUMENTARY" },
                )
            }

    /** Dedupe the per-fact edge lists into one record per unordered fact pair + relation. */
    internal fun edgeAggregates(rows: List<ScoredClaimView>): List<EdgeAggregate> =
        rows
            .flatMap { row ->
                row.edges.mapNotNull { edge ->
                    val relation = edge["relation"] as? String ?: return@mapNotNull null
                    val other = edge["otherFactId"] as? String ?: return@mapNotNull null
                    val pair = listOf(row.factId, other).sorted().joinToString("↔")
                    EdgeAggregate(
                        pairKey = pair,
                        relation = relation,
                        fromFactId = row.factId,
                        toFactId = other,
                        confidence = (edge["confidence"] as? Number)?.toDouble() ?: 0.0,
                        explained = edge["explained"] as? Boolean ?: false,
                        reviewStatus = edge["reviewStatus"] as? String,
                        rationale = edge["rationale"] as? String,
                        viaEntities =
                            (edge["viaEntities"] as? List<*>)?.mapNotNull { it as? String }
                                ?: emptyList(),
                    )
                }
            }
            .distinctBy { it.pairKey to it.relation }
            .sortedWith(compareBy({ it.pairKey }, { it.relation }))

    private fun parseSignals(json: String?): Map<String, Double> {
        val raw =
            json?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }
                ?: return emptyMap()
        return raw.entries
            .mapNotNull { (k, v) -> (v as? Number)?.let { k.toString() to it.toDouble() } }
            .toMap()
    }

    private fun emptyInputs() =
        SubjectScoreInputs(
            factCount = 0,
            claimCount = 0,
            selfOnlyFactCount = 0,
            independentAttestorCount = 0,
            attestorKindCount = 0,
            attestorsByKind = emptyMap(),
            documentaryFactFraction = 0.0,
            corroborationCount = 0,
            contradictionCount = 0,
            explainedContradictionCount = 0,
            confirmedContradictionCount = 0,
            proposedContradictionCount = 0,
            meanEvidenceMass = 0.0,
            medianEvidenceMass = 0.0,
            anchoredFactCount = 0,
        )
}

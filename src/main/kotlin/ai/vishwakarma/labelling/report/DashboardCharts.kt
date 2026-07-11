package ai.vishwakarma.labelling.report

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.service.DashboardData
import java.util.Locale

/** Fixed categorical slot order for the source-mix donut — color follows the class, never rank. */
private val SOURCE_CLASS_SLOTS =
    listOf("SELF", "ENDORSEMENT", "DOCUMENTARY", "PUBLIC_PROFILE", "EVENT_CAPTURE")

/** Fixed slots for the attestor-kind donut. */
private val ATTESTOR_KIND_SLOTS = listOf("SUBJECT", "ISSUER", "ENDORSER")

/** One SAI component row: value bar + the Φ damper it produces (null for B, the base). */
data class ComponentRowView(
    val code: String,
    val name: String,
    val hint: String,
    val value: Double,
    val factor: Double?,
) {
    val valueText: String
        get() = String.format(Locale.ROOT, "%.2f", value)

    val percent: Int
        get() = (value.coerceIn(0.0, 1.0) * 100).toInt()

    val factorText: String?
        get() = factor?.let { String.format(Locale.ROOT, "×%.3f", it) }
}

/** Every chart + legend the dashboard page and the PDF share (Stage 3.5 §6/§7, D8). */
data class DashboardChartSet(
    val gaugeSvg: String,
    val tierSvg: String,
    val edgesSvg: String,
    val sourceDonutSvg: String,
    val attestorDonutSvg: String,
    val sourceLegend: List<VizSlice>,
    val attestorLegend: List<VizSlice>,
    val histogramSvg: String,
    val scatterSvg: String,
    val stripSvg: String,
    val components: List<ComponentRowView>,
    val bandPaint: String,
)

/** Builds the shared chart set from [DashboardData] — the palette decides web vs print paints. */
object DashboardCharts {

    fun build(
        data: DashboardData,
        s3: AppProperties.Stage3,
        p: VizPalette,
    ): DashboardChartSet {
        val agg = data.aggregate
        return DashboardChartSet(
            gaugeSvg = ChartSvg.gauge(agg.display, agg.band, p),
            tierSvg =
                ChartSvg.barChart(
                    data.factTierCounts.map { (tier, count) -> VizBar(tier, count, p.tier(tier)) },
                    p,
                ),
            edgesSvg =
                ChartSvg.barChart(
                    listOf(
                        VizBar("corroborates", data.edgeSummary.corroborations, p.good),
                        VizBar("explained", data.edgeSummary.contradictionsExplained, p.warn),
                        VizBar("confirmed", data.edgeSummary.contradictionsConfirmed, p.serious),
                        VizBar("in queue", data.edgeSummary.contradictionsProposed, p.critical),
                    ),
                    p,
                ),
            sourceDonutSvg =
                ChartSvg.donut(slices(data.sourceClassCounts, SOURCE_CLASS_SLOTS, p), "claims", p),
            attestorDonutSvg =
                ChartSvg.donut(
                    slices(data.attestorKindCounts, ATTESTOR_KIND_SLOTS, p),
                    "attestors",
                    p,
                ),
            sourceLegend = slices(data.sourceClassCounts, SOURCE_CLASS_SLOTS, p),
            attestorLegend = slices(data.attestorKindCounts, ATTESTOR_KIND_SLOTS, p),
            histogramSvg =
                ChartSvg.histogram(
                    data.claimScoreHistogram,
                    listOf(s3.tierMedium to "MEDIUM", s3.tierHigh to "HIGH"),
                    p,
                ),
            scatterSvg =
                ChartSvg.scatter(
                    data.factPoints.map {
                        VizPoint(
                            x = it.saturation,
                            y = it.belief,
                            paint = p.tier(it.tier),
                            factId = it.factId,
                            title = it.label,
                        )
                    },
                    xLabel = "evidence saturation sat(mass)",
                    yLabel = "belief",
                    p = p,
                ),
            stripSvg = ChartSvg.timelineStrip(data.timeline, s3.tierHigh, s3.tierMedium, p),
            components =
                listOf(
                    ComponentRowView(
                        "B",
                        "Weighted belief",
                        "evidence-weighted fact belief",
                        agg.components.weightedBelief,
                        null,
                    ),
                    ComponentRowView(
                        "D",
                        "Evidence depth",
                        "mean mass saturation — thin evidence sags",
                        agg.components.evidenceDepth,
                        agg.components.depthFactor,
                    ),
                    ComponentRowView(
                        "I",
                        "Independent coverage",
                        "1 − self-only fact share",
                        agg.components.independentCoverage,
                        agg.components.coverageFactor,
                    ),
                    ComponentRowView(
                        "V",
                        "Source diversity",
                        "attestors · kinds · documentary coverage",
                        agg.components.sourceDiversity,
                        agg.components.diversityFactor,
                    ),
                    ComponentRowView(
                        "C",
                        "Contradiction drag",
                        "surviving conflict residue (lower is better)",
                        agg.components.contradictionDrag,
                        agg.components.contradictionFactor,
                    ),
                ),
            bandPaint = p.band(agg.band),
        )
    }

    private fun slices(
        counts: Map<String, Int>,
        domain: List<String>,
        p: VizPalette,
    ): List<VizSlice> =
        counts.entries
            .map { (label, count) ->
                val slot = domain.indexOf(label)
                VizSlice(
                    label = label.lowercase(Locale.ROOT).replace('_', ' '),
                    value = count,
                    paint = if (slot >= 0) p.series[slot % p.series.size] else p.muted,
                )
            }
            .sortedByDescending { it.value }
}

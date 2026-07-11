package ai.vishwakarma.labelling.report

import ai.vishwakarma.labelling.stage3.TimelineFact
import ai.vishwakarma.labelling.stage3.TimelineView
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Document

/**
 * Every builder must emit well-formed XML with escaped labels — openhtmltopdf/Batik reject less.
 */
private fun parse(svg: String): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())

private fun count(doc: Document, tag: String): Int = doc.getElementsByTagName(tag).length

private val P = VizPalette.PRINT

class ChartSvgTest {

    @Test
    fun `gauge geometry at the extremes`() {
        val zero = parse(ChartSvg.gauge(0, "UNSUPPORTED", P))
        assertEquals(1, count(zero, "path")) // track only — no fill arc at 0
        val half = parse(ChartSvg.gauge(50, "MODERATE", P))
        assertEquals(2, count(half, "path"))
        val full = parse(ChartSvg.gauge(100, "STRONG", P))
        assertEquals(2, count(full, "path"))
        assertTrue(ChartSvg.gauge(88, "STRONG", P).contains(">88</text>"))
    }

    @Test
    fun `bar chart draws one mark per non-zero bar and escapes labels`() {
        val svg =
            ChartSvg.barChart(
                listOf(
                    VizBar("corroborates", 12, P.good),
                    VizBar("<b>&\"weird\"", 3, P.warn),
                    VizBar("empty", 0, P.critical),
                ),
                P,
            )
        val doc = parse(svg) // raw '<b>' would fail the parse
        assertEquals(2, count(doc, "path"))
        assertEquals(6, count(doc, "text")) // label + value per row
        assertTrue(!svg.contains("<b>"))
    }

    @Test
    fun `donut sums the center total and a single slice becomes a full circle`() {
        val two =
            parse(
                ChartSvg.donut(
                    listOf(VizSlice("self", 3, P.series[0]), VizSlice("doc", 1, P.series[2])),
                    "claims",
                    P,
                )
            )
        assertEquals(2, count(two, "path"))
        assertTrue(
            ChartSvg.donut(listOf(VizSlice("a", 4, P.series[0])), "claims", P).let {
                parse(it)
                it.contains("<circle")
            }
        )
        assertTrue(ChartSvg.donut(emptyList(), "claims", P).contains("no data yet"))
    }

    @Test
    fun `histogram draws a column per non-zero bucket`() {
        val counts = List(20) { i -> if (i >= 17) 5 else 0 }
        val doc = parse(ChartSvg.histogram(counts, listOf(0.45 to "MEDIUM", 0.75 to "HIGH"), P))
        assertEquals(3, count(doc, "path"))
        assertTrue(ChartSvg.histogram(List(20) { 0 }, emptyList(), P).contains("no data yet"))
    }

    @Test
    fun `scatter carries data-fact hooks and titles`() {
        val svg =
            ChartSvg.scatter(
                points =
                    listOf(
                        VizPoint(0.3, 0.9, P.tierHigh, factId = "f1", title = "A & B <fact>"),
                        VizPoint(0.9, 0.5, P.tierMedium),
                    ),
                xLabel = "evidence saturation",
                yLabel = "belief",
                p = P,
            )
        val doc = parse(svg)
        assertEquals(2, count(doc, "circle"))
        assertEquals(1, count(doc, "title"))
        assertTrue(svg.contains("""data-fact="f1""""))
        assertTrue(!svg.contains("<fact>"))
    }

    @Test
    fun `timeline strip lanes and events render`() {
        fun fact(id: String, kind: String, slot: String?, from: String?, to: String?) =
            TimelineFact(
                factId = id,
                label = "fact $id",
                factKind = kind,
                slot = slot,
                validFrom = from,
                validTo = to,
                datePrecision = "YEAR",
                belief = 0.9,
                beliefBare = 0.9,
                anchored = false,
                memberClaimIds = listOf("c-$id"),
                conflictEdgeIds = emptyList(),
            )
        val view =
            TimelineView(
                state =
                    listOf(
                        fact("s1", "STATE", "EMPLOYER", "2019", "2021"),
                        fact("s2", "STATE", "ROLE", "2020-03", null),
                    ),
                events = listOf(fact("e1", "EVENT", null, "2022-06-01", null)),
                succeeds = emptyList(),
                undated = emptyList(),
            )
        val doc = parse(ChartSvg.timelineStrip(view, 0.75, 0.45, P))
        assertEquals(2, count(doc, "rect"))
        assertTrue(count(doc, "path") >= 1) // the event diamond
        assertTrue(
            ChartSvg.timelineStrip(
                    TimelineView(emptyList(), emptyList(), emptyList(), emptyList()),
                    0.75,
                    0.45,
                    P,
                )
                .contains("no data yet")
        )
    }
}

package ai.vishwakarma.labelling.report

import ai.vishwakarma.labelling.stage3.TimelineFact
import ai.vishwakarma.labelling.stage3.TimelineView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Paint roles for the server-rendered charts (Stage 3.5 LLD §6, D8). Two instances:
 * - [WEB] paints via CSS custom properties (`var(--viz-…)`) so the dashboard follows the app theme
 *   at runtime — the template's style block defines the tokens for dark (default) and light,
 *   mirroring app.css.
 * - [PRINT] paints with literal light-mode hexes for openhtmltopdf (no `var()` support there).
 *
 * The categorical slots are the dataviz reference palette (validated: CVD-safe order, ≥3:1 on the
 * app surfaces; the light aqua/yellow contrast WARN is relieved by the HTML legends that always
 * accompany a donut). Text and grid ride `currentColor` on the web so both themes read.
 */
class VizPalette
private constructor(
    /** Categorical slots, fixed order — never cycled, never reordered per chart. */
    val series: List<String>,
    /** De-emphasis for "other/unknown" tails — not a categorical identity. */
    val muted: String,
    val tierHigh: String,
    val tierMedium: String,
    val tierLow: String,
    /** Single-series magnitude hue (histogram). */
    val accent: String,
    val good: String,
    val warn: String,
    val serious: String,
    val critical: String,
    /** Chart surface — the 2px gaps and marker rings are drawn in this. */
    val surface: String,
    /** Unfilled meter/donut track. */
    val track: String,
    /** Text ink ("currentColor" on the web; literal ink in print). */
    val ink: String,
) {
    fun tier(tier: String): String =
        when (tier) {
            "HIGH" -> tierHigh
            "MEDIUM" -> tierMedium
            else -> tierLow
        }

    fun band(band: String): String =
        when (band) {
            "STRONG" -> good
            "GOOD" -> accent
            "MODERATE" -> warn
            "WEAK" -> serious
            else -> critical
        }

    companion object {
        /** CSS-token paints; tokens are defined in the dashboard template's style block. */
        val WEB =
            VizPalette(
                series = (1..5).map { "var(--viz-s$it)" },
                muted = "var(--viz-muted)",
                tierHigh = "var(--viz-tier-high)",
                tierMedium = "var(--viz-tier-medium)",
                tierLow = "var(--viz-tier-low)",
                accent = "var(--viz-accent)",
                good = "var(--viz-good)",
                warn = "var(--viz-warn)",
                serious = "var(--viz-serious)",
                critical = "var(--viz-critical)",
                surface = "var(--surface)",
                track = "var(--border)",
                ink = "currentColor",
            )

        /** Literal light-mode paints for the PDF (keep in sync with profile-pdf.html swatches). */
        val PRINT =
            VizPalette(
                series = listOf("#2a78d6", "#1baf7a", "#eda100", "#008300", "#4a3aa7"),
                muted = "#8a94a1",
                tierHigh = "#16a34a",
                tierMedium = "#3b82f6",
                tierLow = "#6b7280",
                accent = "#0d9488",
                good = "#16a34a",
                warn = "#b45309",
                serious = "#c2410c",
                critical = "#b3261e",
                surface = "#ffffff",
                track = "#dce2e9",
                ink = "#1a2330",
            )
    }
}

// ---- chart inputs ------------------------------------------------------------------------

/** One horizontal bar (label · value · paint). */
data class VizBar(val label: String, val value: Int, val paint: String)

/** One part-to-whole slice. */
data class VizSlice(val label: String, val value: Int, val paint: String)

/** One scatter point in unit space (x, y ∈ [0,1]). */
data class VizPoint(
    val x: Double,
    val y: Double,
    val paint: String,
    /** Rides as `data-fact` for the web popover; ignored by the PDF. */
    val factId: String? = null,
    val title: String? = null,
)

/**
 * Pure SVG chart builders shared verbatim by the dashboard page and the PDF template (D8):
 * deterministic strings, XML-escaped labels, no scripts — interactivity is layered on by
 * stage3-dashboard.js (popovers only). Mark specs follow the dataviz method: thin marks with 4px
 * rounded data-ends, 2px surface gaps/rings, solid hairline grid, selective direct labels.
 */
object ChartSvg {

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun f(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    // ---- the SAI meter (arc form of the single-ratio meter; hero number in the center) ----

    /** 220×132 arc meter: track + band-colored fill for display/100, hero number centered. */
    fun gauge(display: Int, band: String, p: VizPalette): String {
        val cx = 110.0
        val cy = 112.0
        val r = 86.0
        fun point(fraction: Double): Pair<Double, Double> {
            val angle = PI * (1 - fraction) // 180° sweep, left → right
            return cx + r * cos(angle) to cy - r * sin(angle)
        }
        val fraction = (display.coerceIn(0, 100)) / 100.0
        val (sx, sy) = point(0.0)
        val (ex, ey) = point(1.0)
        val (fx, fy) = point(fraction)
        val fill = p.band(band)
        val sb = StringBuilder()
        sb.append(
            """<svg width="220" height="132" viewBox="0 0 220 132" role="img" aria-label="Subject Authenticity Index $display of 100, band ${esc(band)}" xmlns="http://www.w3.org/2000/svg">"""
        )
        sb.append(
            """<path d="M ${f(sx)} ${f(sy)} A ${f(r)} ${f(r)} 0 0 1 ${f(ex)} ${f(ey)}" fill="none" style="stroke:${p.track}" stroke-width="12" stroke-linecap="round"/>"""
        )
        if (fraction > 0.005)
            sb.append(
                """<path d="M ${f(sx)} ${f(sy)} A ${f(r)} ${f(r)} 0 0 1 ${f(fx)} ${f(fy)}" fill="none" style="stroke:$fill" stroke-width="12" stroke-linecap="round"/>"""
            )
        sb.append(
            """<text x="110" y="98" text-anchor="middle" font-size="48" font-weight="600" style="fill:${p.ink}">$display</text>"""
        )
        sb.append(
            """<text x="110" y="120" text-anchor="middle" font-size="12" style="fill:${p.ink}" fill-opacity="0.6">/ 100</text>"""
        )
        sb.append("</svg>")
        return sb.toString()
    }

    // ---- horizontal bars (magnitude by category; status splits) ---------------------------

    /** Horizontal bar chart: labels left, 4px rounded data-end, value at the tip. */
    fun barChart(bars: List<VizBar>, p: VizPalette, width: Int = 420): String {
        if (bars.isEmpty()) return empty(width)
        val rowH = 30
        val barH = 16
        val labelW = 120
        val valueW = 36
        val plotW = width - labelW - valueW
        val height = bars.size * rowH + 8
        val maxV = max(1, bars.maxOf { it.value })
        val sb = StringBuilder()
        sb.append(
            """<svg width="$width" height="$height" viewBox="0 0 $width $height" role="img" xmlns="http://www.w3.org/2000/svg">"""
        )
        bars.forEachIndexed { i, bar ->
            val y = i * rowH + 8
            val w = plotW * bar.value / maxV.toDouble()
            sb.append(
                """<text x="${labelW - 8}" y="${y + barH - 3}" text-anchor="end" font-size="12" style="fill:${p.ink}" fill-opacity="0.75">${esc(bar.label)}</text>"""
            )
            sb.append(roundedBar(labelW.toDouble(), y.toDouble(), w, barH.toDouble(), bar.paint))
            sb.append(
                """<text x="${f(labelW + w + 6)}" y="${y + barH - 3}" font-size="12" font-weight="600" style="fill:${p.ink}">${bar.value}</text>"""
            )
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** A left-anchored bar with a 4px rounded data-end and square baseline. */
    private fun roundedBar(x: Double, y: Double, w: Double, h: Double, paint: String): String {
        if (w <= 0.5) return ""
        val r = minOf(4.0, w)
        return """<path d="M ${f(x)} ${f(y)} h ${f(w - r)} a ${f(r)} ${f(r)} 0 0 1 ${f(r)} ${f(r)} v ${f(h - 2 * r)} a ${f(r)} ${f(r)} 0 0 1 ${f(-r)} ${f(r)} h ${f(-(w - r))} Z" style="fill:$paint"/>"""
    }

    // ---- donut (part-to-whole at a glance, ≤ 6 slices; legend lives in the HTML) ----------

    /** 160×160 donut with 2px surface gaps and a centered total. */
    fun donut(slices: List<VizSlice>, centerLabel: String, p: VizPalette): String {
        val total = slices.sumOf { it.value }
        if (total == 0 || slices.isEmpty()) return empty(160)
        val cx = 80.0
        val cy = 80.0
        val r = 62.0
        val stroke = 22.0
        val sb = StringBuilder()
        sb.append(
            """<svg width="160" height="160" viewBox="0 0 160 160" role="img" xmlns="http://www.w3.org/2000/svg">"""
        )
        var start = -PI / 2
        slices
            .filter { it.value > 0 }
            .forEach { slice ->
                val sweep = 2 * PI * slice.value / total
                val end = start + sweep
                val sx = cx + r * cos(start)
                val sy = cy + r * sin(start)
                val ex = cx + r * cos(end)
                val ey = cy + r * sin(end)
                val large = if (sweep > PI) 1 else 0
                if (sweep >= 2 * PI - 1e-6) {
                    sb.append(
                        """<circle cx="${f(cx)}" cy="${f(cy)}" r="${f(r)}" fill="none" style="stroke:${slice.paint}" stroke-width="${f(stroke)}"/>"""
                    )
                } else {
                    sb.append(
                        """<path d="M ${f(sx)} ${f(sy)} A ${f(r)} ${f(r)} 0 $large 1 ${f(ex)} ${f(ey)}" fill="none" style="stroke:${slice.paint}" stroke-width="${f(stroke)}"/>"""
                    )
                    // The 2px surface gap between touching slices.
                    sb.append(
                        """<line x1="${f(cx)}" y1="${f(cy)}" x2="${f(cx + (r + stroke) * cos(start))}" y2="${f(cy + (r + stroke) * sin(start))}" style="stroke:${p.surface}" stroke-width="2"/>"""
                    )
                }
                start = end
            }
        sb.append(
            """<text x="80" y="78" text-anchor="middle" font-size="22" font-weight="600" style="fill:${p.ink}">$total</text>"""
        )
        sb.append(
            """<text x="80" y="96" text-anchor="middle" font-size="10" style="fill:${p.ink}" fill-opacity="0.6">${esc(centerLabel)}</text>"""
        )
        sb.append("</svg>")
        return sb.toString()
    }

    // ---- histogram (single-series magnitude — one hue, no legend) --------------------------

    /**
     * Claim-score histogram: [counts] buckets covering [0,1], columns in the accent hue, solid
     * hairline threshold rules labeled with the tier band they open.
     */
    fun histogram(
        counts: List<Int>,
        thresholds: List<Pair<Double, String>>,
        p: VizPalette,
        width: Int = 460,
    ): String {
        if (counts.isEmpty() || counts.sum() == 0) return empty(width)
        val height = 150
        val padL = 28
        val padB = 20
        val padT = 16
        val plotW = width - padL - 8
        val plotH = height - padT - padB
        val maxV = max(1, counts.max())
        val colW = plotW / counts.size.toDouble()
        val sb = StringBuilder()
        sb.append(
            """<svg width="$width" height="$height" viewBox="0 0 $width $height" role="img" xmlns="http://www.w3.org/2000/svg">"""
        )
        // Baseline + y ticks (0 and max) — recessive hairlines, text tokens.
        sb.append(
            """<line x1="$padL" y1="${height - padB}" x2="${width - 8}" y2="${height - padB}" style="stroke:${p.ink}" stroke-opacity="0.25" stroke-width="1"/>"""
        )
        sb.append(
            """<text x="${padL - 4}" y="${padT + 4}" text-anchor="end" font-size="10" style="fill:${p.ink}" fill-opacity="0.6">$maxV</text>"""
        )
        sb.append(
            """<text x="${padL - 4}" y="${height - padB + 4}" text-anchor="end" font-size="10" style="fill:${p.ink}" fill-opacity="0.6">0</text>"""
        )
        counts.forEachIndexed { i, count ->
            if (count > 0) {
                val h = plotH * count / maxV.toDouble()
                val x = padL + i * colW + 1 // the surface gap between columns
                val y = height - padB - h
                val w = colW - 2
                val r = minOf(4.0, h, w / 2)
                sb.append(
                    """<path d="M ${f(x)} ${f(y + r)} a ${f(r)} ${f(r)} 0 0 1 ${f(r)} ${f(-r)} h ${f(w - 2 * r)} a ${f(r)} ${f(r)} 0 0 1 ${f(r)} ${f(r)} v ${f(h - r)} h ${f(-w)} Z" style="fill:${p.accent}"/>"""
                )
            }
        }
        // X ticks at 0 / 0.5 / 1 and the tier-threshold rules.
        listOf(0.0, 0.5, 1.0).forEach { t ->
            sb.append(
                """<text x="${f(padL + plotW * t)}" y="${height - 6}" text-anchor="middle" font-size="10" style="fill:${p.ink}" fill-opacity="0.6">${f(t)}</text>"""
            )
        }
        thresholds.forEach { (value, label) ->
            val x = padL + plotW * value
            sb.append(
                """<line x1="${f(x)}" y1="$padT" x2="${f(x)}" y2="${height - padB}" style="stroke:${p.ink}" stroke-opacity="0.35" stroke-width="1"/>"""
            )
            sb.append(
                """<text x="${f(x + 3)}" y="${padT + 8}" font-size="9" style="fill:${p.ink}" fill-opacity="0.6">${esc(label)}</text>"""
            )
        }
        sb.append("</svg>")
        return sb.toString()
    }

    // ---- scatter (which facts are thin: evidence saturation × belief) ----------------------

    /** Unit-space scatter with ≥8px markers and 2px surface rings; tier colors + HTML legend. */
    fun scatter(
        points: List<VizPoint>,
        xLabel: String,
        yLabel: String,
        p: VizPalette,
        width: Int = 420,
    ): String {
        val height = 240
        val padL = 34
        val padB = 28
        val padT = 10
        val padR = 10
        val plotW = width - padL - padR
        val plotH = height - padT - padB
        fun px(x: Double) = padL + plotW * x.coerceIn(0.0, 1.0)
        fun py(y: Double) = padT + plotH * (1 - y.coerceIn(0.0, 1.0))
        val sb = StringBuilder()
        sb.append(
            """<svg width="$width" height="$height" viewBox="0 0 $width $height" role="img" xmlns="http://www.w3.org/2000/svg">"""
        )
        // Axes + quarter gridlines (solid hairline, recessive).
        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { t ->
            sb.append(
                """<line x1="${f(px(t))}" y1="$padT" x2="${f(px(t))}" y2="${padT + plotH}" style="stroke:${p.ink}" stroke-opacity="0.08" stroke-width="1"/>"""
            )
            sb.append(
                """<line x1="$padL" y1="${f(py(t))}" x2="${padL + plotW}" y2="${f(py(t))}" style="stroke:${p.ink}" stroke-opacity="0.08" stroke-width="1"/>"""
            )
        }
        listOf(0.0, 0.5, 1.0).forEach { t ->
            sb.append(
                """<text x="${f(px(t))}" y="${height - 14}" text-anchor="middle" font-size="10" style="fill:${p.ink}" fill-opacity="0.6">${f(t)}</text>"""
            )
            sb.append(
                """<text x="${padL - 6}" y="${f(py(t) + 3)}" text-anchor="end" font-size="10" style="fill:${p.ink}" fill-opacity="0.6">${f(t)}</text>"""
            )
        }
        sb.append(
            """<text x="${padL + plotW / 2}" y="${height - 2}" text-anchor="middle" font-size="10" style="fill:${p.ink}" fill-opacity="0.7">${esc(xLabel)}</text>"""
        )
        sb.append(
            """<text x="10" y="${padT + plotH / 2}" font-size="10" style="fill:${p.ink}" fill-opacity="0.7" transform="rotate(-90 10 ${padT + plotH / 2})" text-anchor="middle">${esc(yLabel)}</text>"""
        )
        points.forEach { pt ->
            val attrs = pt.factId?.let { """ data-fact="${esc(it)}" cursor="pointer"""" } ?: ""
            sb.append(
                """<circle cx="${f(px(pt.x))}" cy="${f(py(pt.y))}" r="5" style="fill:${pt.paint};stroke:${p.surface}" stroke-width="2"$attrs>"""
            )
            pt.title?.let { sb.append("<title>${esc(it)}</title>") }
            sb.append("</circle>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    // ---- compact timeline strip (continuity with the full VA-24 view; link out for detail) --

    /** Slot lanes + event diamonds, tier-colored, year ticks — non-interactive. */
    fun timelineStrip(
        view: TimelineView,
        tierHigh: Double,
        tierMedium: Double,
        p: VizPalette,
        width: Int = 860,
    ): String {
        fun ms(s: String?): Long? =
            s?.let { runCatching { java.time.LocalDate.parse(padDate(it)) }.getOrNull() }
                ?.toEpochDay()
                ?.times(86_400_000L)
        val state = view.state.filter { ms(it.validFrom) != null }
        val events = view.events.filter { ms(it.validFrom) != null }
        if (state.isEmpty() && events.isEmpty()) return empty(width)
        fun endMs(fact: TimelineFact): Long =
            ms(fact.validTo)?.plus(365L * 86_400_000L / 2) ?: System.currentTimeMillis()
        val min =
            (state.mapNotNull { ms(it.validFrom) } + events.mapNotNull { ms(it.validFrom) })
                .min()
                .toDouble()
        val maxRaw =
            (state.map { endMs(it) } + events.mapNotNull { ms(it.validFrom) }).max().toDouble()
        val pad = ((maxRaw - min) * 0.03).coerceAtLeast(3.0 * 86_400_000)
        val lo = min - pad
        val hi = maxRaw + pad
        val slots = state.map { (it.slot ?: "other").lowercase(Locale.ROOT) }.distinct().sorted()
        val laneH = 22
        val axisH = 22
        val padL = 92
        val eventsLane = events.isNotEmpty()
        val height = slots.size * laneH + (if (eventsLane) laneH else 0) + axisH
        fun x(t: Long): Double = padL + (width - padL - 16) * (t.toDouble() - lo) / (hi - lo)
        fun tierPaint(belief: Double?): String =
            when {
                belief == null -> p.tierLow
                belief >= tierHigh -> p.tierHigh
                belief >= tierMedium -> p.tierMedium
                else -> p.tierLow
            }
        val sb = StringBuilder()
        sb.append(
            """<svg width="$width" height="$height" viewBox="0 0 $width $height" role="img" xmlns="http://www.w3.org/2000/svg">"""
        )
        val y0 = java.time.Instant.ofEpochMilli(lo.toLong()).atZone(java.time.ZoneOffset.UTC).year
        val y1 = java.time.Instant.ofEpochMilli(hi.toLong()).atZone(java.time.ZoneOffset.UTC).year
        val step = max(1, (y1 - y0) / 10)
        var year = y0
        while (year <= y1 + 1) {
            val t = java.time.LocalDate.of(year, 1, 1).toEpochDay() * 86_400_000L
            if (t.toDouble() in lo..hi) {
                sb.append(
                    """<line x1="${f(x(t))}" y1="0" x2="${f(x(t))}" y2="${height - axisH}" style="stroke:${p.ink}" stroke-opacity="0.08" stroke-width="1"/>"""
                )
                sb.append(
                    """<text x="${f(x(t))}" y="${height - 8}" text-anchor="middle" font-size="10" style="fill:${p.ink}" fill-opacity="0.6">$year</text>"""
                )
            }
            year += step
        }
        slots.forEachIndexed { i, slot ->
            val top = i * laneH
            sb.append(
                """<text x="6" y="${top + laneH / 2 + 4}" font-size="10" font-weight="600" style="fill:${p.ink}" fill-opacity="0.7">${esc(slot)}</text>"""
            )
            state
                .filter { (it.slot ?: "other").lowercase(Locale.ROOT) == slot }
                .forEach { fact ->
                    val s = ms(fact.validFrom) ?: return@forEach
                    val w = (x(endMs(fact)) - x(s)).coerceAtLeast(3.0)
                    sb.append(
                        """<rect x="${f(x(s))}" y="${top + 5}" width="${f(w)}" height="11" rx="3" style="fill:${tierPaint(fact.belief)}" fill-opacity="0.8">"""
                    )
                    sb.append("<title>${esc(fact.label)}</title></rect>")
                }
        }
        if (eventsLane) {
            val top = slots.size * laneH
            sb.append(
                """<text x="6" y="${top + laneH / 2 + 4}" font-size="10" font-weight="600" style="fill:${p.ink}" fill-opacity="0.7">events</text>"""
            )
            events.forEach { fact ->
                val cx = x(ms(fact.validFrom) ?: return@forEach)
                val cy = top + laneH / 2.0
                sb.append(
                    """<path d="M ${f(cx)} ${f(cy - 5)} L ${f(cx + 5)} ${f(cy)} L ${f(cx)} ${f(cy + 5)} L ${f(cx - 5)} ${f(cy)} Z" style="fill:${tierPaint(fact.belief)}" fill-opacity="0.9"><title>${esc(fact.label)}</title></path>"""
                )
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** ISO year / year-month values pad to a full date so LocalDate can parse them. */
    private fun padDate(s: String): String =
        when (s.length) {
            4 -> "$s-01-01"
            7 -> "$s-01"
            else -> s
        }

    private fun empty(width: Int): String =
        """<svg width="$width" height="40" viewBox="0 0 $width 40" role="img" xmlns="http://www.w3.org/2000/svg"><text x="8" y="24" font-size="12" fill="currentColor" fill-opacity="0.5">no data yet</text></svg>"""
}

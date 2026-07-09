/* =============================================================================
   Stage 3 — timeline renderer (VA-24, LLD §12 bullet 4 / §11.7).

   Pure vanilla SVG over window.STAGE3_TIMELINE (the §10 timeline read inlined
   by the template) — self-hosted posture, no chart dependency. One lane per
   STATE slot (bars validFrom→validTo; open-ended bars run to today and fade;
   YEAR-precision bars draw dashed), SUCCEEDS successions as connectors inside
   a lane (the gap stays visible), EVENT facts as diamonds on their own lane,
   and a red ⚠ on any fact carrying a surviving CONTRADICTS edge, linking to
   its queue card. Click a bar/diamond for the fact popover.
   ========================================================================== */
(function () {
    "use strict";

    var host = document.getElementById("stage3Timeline");
    var data = window.STAGE3_TIMELINE;
    if (!host || !data) return;
    var state = data.state || [];
    var events = data.events || [];
    var succeeds = data.succeeds || [];
    if (!state.length && !events.length) return;

    var TIER_HIGH = parseFloat(host.dataset.tierHigh || "0.75");
    var TIER_MEDIUM = parseFloat(host.dataset.tierMedium || "0.5");
    var QUEUE_URL = host.dataset.queueUrl || "#";
    var SCORES_URL = host.dataset.scoresUrl || "#";
    var LANE_ORDER = ["employer", "role", "education", "residence"];
    var SVG_NS = "http://www.w3.org/2000/svg";

    function el(name, attrs, parent) {
        var node = document.createElementNS(SVG_NS, name);
        Object.keys(attrs || {}).forEach(function (k) { node.setAttribute(k, attrs[k]); });
        if (parent) parent.appendChild(node);
        return node;
    }

    function tierColor(belief) {
        if (belief == null) return "#6b7280";
        if (belief >= TIER_HIGH) return "#16a34a";
        if (belief >= TIER_MEDIUM) return "#3b82f6";
        return "#6b7280";
    }

    function parse(s) { // ISO year / year-month / full date → ms (null-safe)
        if (!s) return null;
        var d = new Date(s);
        return isNaN(d.getTime()) ? null : d.getTime();
    }

    // A fact's visual end: validTo extended by its precision (a YEAR bar ending "2021"
    // covers 2021), open-ended (null validTo) runs to today.
    function barEnd(f) {
        if (!f.validTo) return Date.now();
        var d = new Date(f.validTo);
        if (isNaN(d.getTime())) return Date.now();
        if (f.datePrecision === "YEAR") d.setFullYear(d.getFullYear() + 1);
        else if (f.datePrecision === "MONTH") d.setMonth(d.getMonth() + 1);
        else d.setDate(d.getDate() + 1);
        return d.getTime();
    }

    function intervalText(f) {
        if (!f.validFrom && !f.validTo) return "undated";
        var t = (f.validFrom || "…") + " → " + (f.validTo || "present");
        if (f.datePrecision && f.datePrecision !== "NONE")
            t += " · " + f.datePrecision.toLowerCase() + " precision";
        return t;
    }

    // ---- layout -----------------------------------------------------------

    var datedState = state.filter(function (f) { return parse(f.validFrom) != null; });
    var datedEvents = events.filter(function (f) { return parse(f.validFrom) != null; });

    var laneNames = [];
    datedState.forEach(function (f) {
        var slot = (f.slot || "other").toLowerCase();
        if (laneNames.indexOf(slot) < 0) laneNames.push(slot);
    });
    laneNames.sort(function (a, b) {
        var ia = LANE_ORDER.indexOf(a), ib = LANE_ORDER.indexOf(b);
        if (ia < 0) ia = LANE_ORDER.length;
        if (ib < 0) ib = LANE_ORDER.length;
        return ia !== ib ? ia - ib : a.localeCompare(b);
    });

    var min = Infinity, max = -Infinity;
    datedState.concat(datedEvents).forEach(function (f) {
        var s = parse(f.validFrom);
        if (s != null && s < min) min = s;
        var e = f.factKind === "EVENT" ? s : barEnd(f);
        if (e != null && e > max) max = e;
    });
    if (!isFinite(min) || !isFinite(max)) return;
    var pad = Math.max((max - min) * 0.03, 90 * 86400000 * 0.03);
    min -= pad; max += pad;

    var M_LEFT = 110, M_RIGHT = 24, ROW_H = 26, BAR_H = 14, LANE_GAP = 10, AXIS_H = 28;

    // Greedy sub-row packing per lane, so overlapping intervals (the anachronism case)
    // stack instead of hiding each other.
    var lanes = laneNames.map(function (name) {
        var facts = datedState
            .filter(function (f) { return (f.slot || "other").toLowerCase() === name; })
            .sort(function (a, b) { return parse(a.validFrom) - parse(b.validFrom); });
        var rows = [];
        facts.forEach(function (f) {
            var s = parse(f.validFrom), e = barEnd(f);
            var row = 0;
            while (rows[row] != null && s < rows[row]) row++;
            rows[row] = e;
            f._row = row;
        });
        return { name: name, facts: facts, rows: Math.max(rows.length, 1) };
    });

    var eventsLane = datedEvents.length > 0;
    var lanesH = lanes.reduce(function (h, l) { return h + l.rows * ROW_H + LANE_GAP; }, 0);
    var height = lanesH + (eventsLane ? ROW_H + LANE_GAP : 0) + AXIS_H;
    var width = Math.max(host.clientWidth || 900, 640);

    function x(t) { return M_LEFT + ((t - min) / (max - min)) * (width - M_LEFT - M_RIGHT); }

    // ---- render -----------------------------------------------------------

    var popover = null;
    function closePopover() { if (popover) { popover.remove(); popover = null; } }
    document.addEventListener("click", function (e) {
        if (popover && !e.target.closest(".tl-popover") && !e.target.closest("[data-fact]"))
            closePopover();
    });

    function showPopover(f, evt) {
        closePopover();
        popover = document.createElement("div");
        popover.className = "tl-popover";
        var belief = f.belief != null ? f.belief.toFixed(2) : "—";
        var conflict = (f.conflictEdgeIds || []).length
            ? '<a style="color:#dc2626;" href="' + QUEUE_URL + "#edge-" +
              encodeURIComponent(f.conflictEdgeIds[0]) + '">&#9888; open the conflict card</a> · '
            : "";
        popover.innerHTML =
            "<strong></strong><br/>" +
            '<span class="muted"></span><br/>' +
            '<span class="muted">belief ' + belief +
            " · " + (f.memberClaimIds || []).length + " claim(s)</span><br/>" +
            conflict + '<a href="' + SCORES_URL + '">scored table &rarr;</a>';
        popover.querySelector("strong").textContent = f.label || f.factId;
        popover.querySelector("span").textContent =
            (f.factKind || "").toLowerCase() + (f.slot ? " · " + f.slot.toLowerCase() : "") +
            " · " + intervalText(f);
        document.body.appendChild(popover);
        var px = Math.min(evt.pageX + 12, window.scrollX + document.documentElement.clientWidth -
            popover.offsetWidth - 16);
        popover.style.left = px + "px";
        popover.style.top = (evt.pageY + 12) + "px";
    }

    var svg = el("svg", { viewBox: "0 0 " + width + " " + height, role: "img" }, null);
    host.appendChild(svg);

    // Fade for open-ended bars.
    var defs = el("defs", {}, svg);
    ["#16a34a", "#3b82f6", "#6b7280"].forEach(function (c, i) {
        var g = el("linearGradient", { id: "tlfade" + i, x1: 0, x2: 1, y1: 0, y2: 0 }, defs);
        el("stop", { offset: "70%", "stop-color": c, "stop-opacity": 0.75 }, g);
        el("stop", { offset: "100%", "stop-color": c, "stop-opacity": 0.1 }, g);
    });
    function fillFor(f) {
        var c = tierColor(f.belief);
        if (f.validTo) return c;
        return "url(#tlfade" + ["#16a34a", "#3b82f6", "#6b7280"].indexOf(c) + ")";
    }

    // Year gridlines + axis labels.
    var y0 = new Date(min).getFullYear(), y1 = new Date(max).getFullYear();
    var step = Math.max(1, Math.ceil((y1 - y0) / 12));
    for (var y = y0; y <= y1 + 1; y += step) {
        var t = Date.UTC(y, 0, 1);
        if (t < min || t > max) continue;
        el("line", { x1: x(t), x2: x(t), y1: 0, y2: height - AXIS_H,
            stroke: "currentColor", "stroke-opacity": 0.12 }, svg);
        var lbl = el("text", { x: x(t), y: height - 8, "text-anchor": "middle",
            "font-size": 11, fill: "currentColor", "fill-opacity": 0.6 }, svg);
        lbl.textContent = y;
    }

    var factsById = {};
    datedState.forEach(function (f) { factsById[f.factId] = f; });

    var yCursor = 0;
    lanes.forEach(function (lane) {
        var laneTop = yCursor;
        var label = el("text", { x: 8, y: laneTop + ROW_H / 2 + 4, "font-size": 12,
            "font-weight": 600, fill: "currentColor", "fill-opacity": 0.75 }, svg);
        label.textContent = lane.name;
        el("line", { x1: 0, x2: width, y1: laneTop - LANE_GAP / 2, y2: laneTop - LANE_GAP / 2,
            stroke: "currentColor", "stroke-opacity": 0.08 }, svg);

        lane.facts.forEach(function (f) {
            var s = parse(f.validFrom), e = barEnd(f);
            var yBar = laneTop + f._row * ROW_H + (ROW_H - BAR_H) / 2;
            var w = Math.max(x(e) - x(s), 4);
            var bar = el("rect", { x: x(s), y: yBar, width: w, height: BAR_H, rx: 3,
                fill: fillFor(f), stroke: tierColor(f.belief),
                "stroke-dasharray": f.datePrecision === "YEAR" ? "4 3" : "none",
                "stroke-width": 1.2, cursor: "pointer", "data-fact": f.factId }, svg);
            bar.addEventListener("click", function (evt) { showPopover(f, evt); });
            var t = el("title", {}, bar);
            t.textContent = f.label + " — " + intervalText(f);
            f._geom = { x1: x(s), x2: x(s) + w, y: yBar + BAR_H / 2 };

            if ((f.conflictEdgeIds || []).length) {
                var a = el("a", { href: QUEUE_URL + "#edge-" +
                    encodeURIComponent(f.conflictEdgeIds[0]) }, svg);
                var warn = el("text", { x: x(s) - 2, y: yBar + BAR_H - 2, "font-size": 13,
                    fill: "#dc2626", "text-anchor": "end", cursor: "pointer" }, a);
                warn.textContent = "⚠";
                el("title", {}, warn).textContent =
                    "anachronism candidate — a surviving contradiction touches this fact";
            }
        });
        yCursor += lane.rows * ROW_H + LANE_GAP;
    });

    // SUCCEEDS connectors (same lane; the gap between bars stays visible by construction).
    succeeds.forEach(function (link) {
        var from = factsById[link.fromFactId], to = factsById[link.toFactId];
        if (!from || !to || !from._geom || !to._geom) return;
        var line = el("line", { x1: from._geom.x2, y1: from._geom.y, x2: to._geom.x1,
            y2: to._geom.y, stroke: "currentColor", "stroke-opacity": 0.5,
            "stroke-width": 1.5, "stroke-dasharray": "3 3" }, svg);
        el("title", {}, line).textContent = "SUCCEEDS" +
            (link.gapDays != null ? " · gap " + link.gapDays + "d" : "");
        el("circle", { cx: to._geom.x1, cy: to._geom.y, r: 2.5, fill: "currentColor",
            "fill-opacity": 0.6 }, svg);
    });

    // EVENT diamonds.
    if (eventsLane) {
        var evTop = yCursor;
        var evLabel = el("text", { x: 8, y: evTop + ROW_H / 2 + 4, "font-size": 12,
            "font-weight": 600, fill: "currentColor", "fill-opacity": 0.75 }, svg);
        evLabel.textContent = "events";
        el("line", { x1: 0, x2: width, y1: evTop - LANE_GAP / 2, y2: evTop - LANE_GAP / 2,
            stroke: "currentColor", "stroke-opacity": 0.08 }, svg);
        datedEvents.forEach(function (f) {
            var cx = x(parse(f.validFrom)), cy = evTop + ROW_H / 2, r = 6;
            var d = el("path", { d: "M" + cx + " " + (cy - r) + " L" + (cx + r) + " " + cy +
                " L" + cx + " " + (cy + r) + " L" + (cx - r) + " " + cy + " Z",
                fill: tierColor(f.belief), "fill-opacity": 0.85, cursor: "pointer",
                "data-fact": f.factId }, svg);
            d.addEventListener("click", function (evt) { showPopover(f, evt); });
            el("title", {}, d).textContent = f.label + " — " + intervalText(f);
            if ((f.conflictEdgeIds || []).length) {
                var a = el("a", { href: QUEUE_URL + "#edge-" +
                    encodeURIComponent(f.conflictEdgeIds[0]) }, svg);
                var warn = el("text", { x: cx + r + 2, y: cy + 4, "font-size": 13,
                    fill: "#dc2626", cursor: "pointer" }, a);
                warn.textContent = "⚠";
            }
        });
    }
})();

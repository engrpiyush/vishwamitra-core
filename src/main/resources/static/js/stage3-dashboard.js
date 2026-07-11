/* =============================================================================
   Stage 3.5 — authenticity dashboard popovers.

   The charts are server-rendered SVG (report/ChartSvg.kt, decision D8) — this
   script only layers the fact popover onto the evidence-vs-belief scatter:
   click a [data-fact] dot for the fact's numbers, with a link to the scored
   table. Data rides window.STAGE3_DASHBOARD (inlined by the template), the
   popover element reuses the timeline's .tl-popover styling.
   ========================================================================== */
(function () {
    "use strict";

    var data = window.STAGE3_DASHBOARD;
    if (!data || !data.facts) return;

    var popover = null;
    function closePopover() {
        if (popover) {
            popover.remove();
            popover = null;
        }
    }
    document.addEventListener("click", function (e) {
        var dot = e.target.closest("[data-fact]");
        if (!dot) {
            if (popover && !e.target.closest(".tl-popover")) closePopover();
            return;
        }
        var fact = data.facts[dot.getAttribute("data-fact")];
        if (!fact) return;
        closePopover();
        popover = document.createElement("div");
        popover.className = "tl-popover";
        popover.innerHTML =
            "<strong></strong><br/>" +
            '<span class="muted js-line1"></span><br/>' +
            '<span class="muted js-line2"></span><br/>' +
            '<a href="' + (data.scoresUrl || "#") + '">scored table &rarr;</a>';
        popover.querySelector("strong").textContent = fact.label || fact.factId;
        popover.querySelector(".js-line1").textContent =
            (fact.slot ? fact.slot.toLowerCase() + " · " : "") +
            fact.tier.toLowerCase() + " · belief " + fact.belief.toFixed(2);
        popover.querySelector(".js-line2").textContent =
            "evidence mass " + fact.evidenceMass.toFixed(1) +
            " · " + fact.claimCount + " claim(s)" +
            (fact.selfOnly ? " · self-only" : "");
        document.body.appendChild(popover);
        var px = Math.min(
            e.pageX + 12,
            window.scrollX + document.documentElement.clientWidth - popover.offsetWidth - 16
        );
        popover.style.left = px + "px";
        popover.style.top = e.pageY + 12 + "px";
    });
})();

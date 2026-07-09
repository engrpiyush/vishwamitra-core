// VA-22 scored-claims table (LLD §12 bullet 2): DataTables (search / sort / paginate) over the
// server-rendered rows, highest score first. Clicking a row expands a child row holding that
// claim's read-only "why this score" panel, pulled from the hidden #scorePanels bank — the
// review-decide.js pattern without the editor wiring. stateSave restores search/page position.
(function () {
    function ready(fn) {
        if (document.readyState !== "loading") fn();
        else document.addEventListener("DOMContentLoaded", fn);
    }

    ready(function () {
        var $ = window.jQuery;
        if (!$ || !$.fn || !$.fn.DataTable) return;
        var $table = $("#scoresTable");
        if (!$table.length) return;

        var table = $table.DataTable({
            stateSave: true,
            pageLength: 25,
            lengthMenu: [10, 25, 50, 100],
            order: [[0, "desc"]], // score — highest first
        });

        function panelHtml(claimId) {
            var el = document.querySelector(
                '#scorePanels .score-panel[data-claim-id="' + claimId + '"]'
            );
            return el ? el.innerHTML : "";
        }

        function collapseAll() {
            table.rows(".shown").every(function () {
                this.child.hide();
                $(this.node()).removeClass("shown");
            });
        }

        $("#scoresTable tbody").on("click", "td", function (e) {
            if ($(e.target).closest(".score-panel-wrap").length) return;
            var tr = $(this).closest("tr");
            var row = table.row(tr);
            if (!row.node()) return;
            var claimId = tr.attr("data-claim-id");
            if (!claimId) return;
            if (row.child.isShown()) {
                row.child.hide();
                tr.removeClass("shown");
            } else {
                collapseAll();
                row.child('<div class="score-panel-wrap">' + panelHtml(claimId) + "</div>").show();
                tr.addClass("shown");
            }
        });

        // Click anywhere outside the table (and its DataTables chrome) collapses open panels.
        document.addEventListener("click", function (e) {
            if (e.target.closest("#scoresTable")) return;
            if (e.target.closest(".dataTables_wrapper")) return;
            collapseAll();
        });
    });
})();

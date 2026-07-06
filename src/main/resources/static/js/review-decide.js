// §12.6 Decide page: DataTables (search / sort / paginate) over all claims, approve-by-default.
// Clicking a row expands a child row holding that claim's sidecar/reject editor (a server-rendered
// form pulled from the hidden #claimEditors bank); clicking outside the table collapses it. The
// editor's decision is derived on submit: reject → CONTESTED, else a non-empty justification →
// SIDECARED, else APPROVED. Save is a plain POST-redirect-GET; DataTables stateSave restores the
// search/page position after the reload.
(function () {
    function ready(fn) {
        if (document.readyState !== "loading") fn();
        else document.addEventListener("DOMContentLoaded", fn);
    }

    ready(function () {
        var $ = window.jQuery;
        if (!$ || !$.fn || !$.fn.DataTable) return;
        var $table = $("#claimsTable");
        if (!$table.length) return;

        var table = $table.DataTable({
            stateSave: true,
            pageLength: 25,
            lengthMenu: [10, 25, 50, 100],
            order: [[0, "asc"]], // favorability ascending — least favorable first
            columnDefs: [{ orderable: false, targets: 5 }],
        });

        // The editor HTML for a claim id, from the hidden bank (server-rendered form incl. CSRF).
        function editorHtml(claimId) {
            var el = document.querySelector(
                '#claimEditors .claim-editor[data-claim-id="' + claimId + '"]'
            );
            return el ? el.innerHTML : "";
        }

        function collapseAll() {
            table.rows(".shown").every(function () {
                this.child.hide();
                $(this.node()).removeClass("shown");
            });
        }

        // Derive the decision and disable the sidecar box while "reject" is checked.
        function wireEditor(node) {
            var form = node.querySelector("form");
            if (!form) return;
            var reject = form.querySelector(".js-reject");
            var just = form.querySelector(".js-justification");
            var decision = form.querySelector(".js-decision");

            function sync() {
                if (just) just.disabled = !!(reject && reject.checked);
            }
            if (reject) reject.addEventListener("change", sync);
            sync();

            form.addEventListener("submit", function () {
                var d = "APPROVED";
                if (reject && reject.checked) d = "CONTESTED";
                else if (just && just.value.trim() !== "") d = "SIDECARED";
                if (decision) decision.value = d;
                if (just) just.disabled = false; // disabled fields don't post; re-enable before submit
            });
        }

        // Expand / collapse a row on click — but never when the click is inside an open editor.
        $("#claimsTable tbody").on("click", "td", function (e) {
            if ($(e.target).closest(".claim-editor-wrap").length) return;
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
                row.child('<div class="claim-editor-wrap">' + editorHtml(claimId) + "</div>").show();
                tr.addClass("shown");
                wireEditor(row.child()[0]);
            }
        });

        // Click anywhere outside the table (and its DataTables chrome) collapses open editors.
        document.addEventListener("click", function (e) {
            if (e.target.closest("#claimsTable")) return;
            if (e.target.closest(".dataTables_wrapper")) return;
            collapseAll();
        });
    });
})();

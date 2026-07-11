// §12.6 Decide page: DataTables (search / sort / paginate) over all claims, approve-by-default.
// Clicking a row expands a child row holding that claim's sidecar/reject editor (a server-rendered
// form pulled from the hidden #claimEditors bank); clicking outside the table collapses it. The
// editor's decision is derived on submit: reject → CONTESTED, else a non-empty justification →
// SIDECARED, else APPROVED. Save goes through POST /api/stage2/claims/{id}/review as JSON — the
// row badge and the editor bank update in place and the editor stays open, so several claims can
// be sidecared back-to-back without losing the table's page/filter state. The form keeps its MVC
// action as the no-JS fallback (plain POST-redirect-GET; stateSave restores the position).
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

        function csrfHeaders() {
            var meta = document.querySelector('meta[name="csrf-token"]');
            return meta ? { "X-CSRF-TOKEN": meta.getAttribute("content") } : {};
        }

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

        function badgeFor(decision) {
            if (decision === "CONTESTED") return { text: "rejected", cls: "badge--danger" };
            if (decision === "SIDECARED") return { text: "sidecar", cls: "badge--info" };
            return { text: "approved", cls: "badge--muted" };
        }

        // Flip the row's Status badge in place and let DataTables re-read the cell (search cache).
        function updateBadge(tr, decision) {
            var badge = tr.find("td").eq(5).find(".badge");
            if (!badge.length) return;
            var b = badgeFor(decision);
            badge
                .removeClass("badge--danger badge--info badge--muted")
                .addClass(b.cls)
                .text(b.text);
            table.cell(tr.get(0), 5).invalidate();
        }

        // The open editor is an innerHTML COPY of the bank entry — write the saved values back as
        // attributes/text (not live properties), so the next expand re-renders them.
        function syncBank(claimId, decision, justification, rejected) {
            var bank = document.querySelector(
                '#claimEditors .claim-editor[data-claim-id="' + claimId + '"]'
            );
            if (!bank) return;
            var just = bank.querySelector(".js-justification");
            if (just) just.textContent = justification;
            var reject = bank.querySelector(".js-reject");
            if (reject) {
                if (rejected) reject.setAttribute("checked", "checked");
                else reject.removeAttribute("checked");
            }
            var dec = bank.querySelector(".js-decision");
            if (dec) dec.setAttribute("value", decision);
        }

        function note(form, text, isError) {
            var el = form.querySelector(".js-save-note");
            if (!el) return;
            el.textContent = text;
            el.classList.toggle("js-save-note--err", !!isError);
            el.classList.toggle("js-save-note--ok", !isError);
        }

        function saveReview(form, claimId, tr) {
            var reject = form.querySelector(".js-reject");
            var just = form.querySelector(".js-justification");
            var rejected = !!(reject && reject.checked);
            var justification = just ? just.value.trim() : "";
            var decision = "APPROVED";
            if (rejected) decision = "CONTESTED";
            else if (justification !== "") decision = "SIDECARED";

            var button = form.querySelector('button[type="submit"]');
            if (button) button.disabled = true;
            note(form, "Saving…", false);
            fetch("/api/stage2/claims/" + encodeURIComponent(claimId) + "/review", {
                method: "POST",
                headers: Object.assign({ "Content-Type": "application/json" }, csrfHeaders()),
                body: JSON.stringify({
                    decision: decision,
                    justification: justification !== "" ? justification : null,
                }),
            })
                .then(function (r) {
                    return r
                        .json()
                        .catch(function () { return {}; })
                        .then(function (body) {
                            if (!r.ok)
                                throw new Error(body.error || "Save failed (" + r.status + ")");
                        });
                })
                .then(function () {
                    updateBadge(tr, decision);
                    syncBank(claimId, decision, justification, rejected);
                    note(form, "Saved ✓", false);
                    if (button) button.disabled = false;
                })
                .catch(function (err) {
                    note(form, err.message, true);
                    if (button) button.disabled = false;
                });
        }

        // Derive the decision and disable the sidecar box while "reject" is checked.
        function wireEditor(node, claimId, tr) {
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

            form.addEventListener("submit", function (e) {
                var d = "APPROVED";
                if (reject && reject.checked) d = "CONTESTED";
                else if (just && just.value.trim() !== "") d = "SIDECARED";
                if (decision) decision.value = d;
                if (just) just.disabled = false; // disabled fields don't post; re-enable before submit
                if (!window.fetch) return; // ancient browser: fall back to the MVC POST
                e.preventDefault();
                saveReview(form, claimId, tr);
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
                wireEditor(row.child()[0], claimId, tr);
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

/* =============================================================================
   Stage 4 — auto-poll loop (Project Neo Stage 4).

   The page-side orchestrator, mirroring stage3-poll.js: while the subject's
   run is in an advancing state, POST /api/stage4/runs/{id}/poll. Each call
   performs ONE bounded phase step server-side (select, plan chunk, generate
   batch, judge batch), so keeping the page open drives the run to REVIEW_WAIT
   — there is no server-side scheduler, and the operator export (never the
   poll loop) completes the run.

   Same deliberate choices as Stage 3:
   - Chained setTimeout, never setInterval: the poll request IS the worker
     (a generate/judge batch can take seconds of LLM time), and overlapping
     ticks would re-read the same batch and double-draft it.
   - Counters refresh in place between phase changes (data-counter hooks);
     a full reload happens only when the status itself changes. Parked and
     terminal states (REVIEW_WAIT / DONE / FAILED / SUPERSEDED) render with
     data-active="false" and never spin.

   CSRF mirrors stage3-poll.js: the <meta> exists only in prod (dev disables
   CSRF, so the header is simply omitted).
   ========================================================================== */
(function () {
    "use strict";

    /* Dialogs (REVIEW_WAIT / DONE panels): the Re-run chooser. Wired before the
       auto-poll early-returns — a page with a parked run still needs the dialog. */
    document.addEventListener("click", function (e) {
        var open = e.target.closest("[data-rerun-open]");
        if (open) {
            var dlg = open.parentElement.querySelector("dialog");
            if (dlg) dlg.showModal();
            return;
        }
        var close = e.target.closest("[data-dialog-close]");
        if (close) {
            var d = close.closest("dialog");
            if (d) d.close();
        }
    });

    var panel = document.getElementById("stage4Run");
    if (!panel) return;
    if (panel.dataset.active !== "true") return;

    var runId = panel.dataset.runId;
    var rendered = panel.dataset.status || "";
    var GAP_MS = 1500;

    function csrfHeaders() {
        var meta = document.querySelector('meta[name="csrf-token"]');
        return meta ? { "X-CSRF-TOKEN": meta.getAttribute("content") } : {};
    }

    function refreshCounters(run) {
        var counters = run.counters || {};
        Object.keys(counters).forEach(function (key) {
            panel.querySelectorAll('[data-counter="' + key + '"]').forEach(function (el) {
                el.textContent = counters[key];
            });
        });
    }

    function tick() {
        fetch("/api/stage4/runs/" + encodeURIComponent(runId) + "/poll", {
            method: "POST",
            headers: csrfHeaders(),
        })
            .then(function (r) {
                if (!r.ok) throw new Error("poll " + r.status);
                return r.json();
            })
            .then(function (run) {
                if (run.status !== rendered) {
                    // New phase / parked / terminal: re-render server-side. The fresh
                    // page decides whether polling continues (data-active).
                    location.reload();
                    return;
                }
                refreshCounters(run);
                setTimeout(tick, GAP_MS);
            })
            .catch(function () { setTimeout(tick, GAP_MS); /* transient; retry */ });
    }

    tick();
})();

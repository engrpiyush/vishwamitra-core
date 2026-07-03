/* =============================================================================
   Stage 2 — auto-poll loop (Project Neo Stage 2).

   The page-side orchestrator: while any Stage 2 job for the subject is active,
   POST /api/stage2/subjects/{id}/poll-all every few seconds. Each call advances
   every non-terminal job server-side (transcription check → claim extraction),
   so keeping the page open drives the whole run to completion — there is no
   server-side scheduler. When the returned job states differ from what the
   page rendered, reload to show them; once nothing is active, stop.

   CSRF mirrors intake-upload.js: the <meta> exists only in prod (dev disables
   CSRF, so the header is simply omitted).
   ========================================================================== */
(function () {
    "use strict";

    var panel = document.getElementById("stage2Jobs");
    if (!panel) return;
    if (parseInt(panel.dataset.activeCount || "0", 10) === 0) return;

    var subjectId = panel.dataset.subjectId;
    var rendered = panel.dataset.statuses || "";
    var INTERVAL_MS = 5000;

    function csrfHeaders() {
        var meta = document.querySelector('meta[name="csrf-token"]');
        return meta ? { "X-CSRF-TOKEN": meta.getAttribute("content") } : {};
    }

    var timer = null;

    function tick() {
        fetch("/api/stage2/subjects/" + encodeURIComponent(subjectId) + "/poll-all", {
            method: "POST",
            headers: csrfHeaders(),
        })
            .then(function (r) {
                if (!r.ok) throw new Error("poll-all " + r.status);
                return r.json();
            })
            .then(function (jobs) {
                var now = jobs
                    .map(function (j) { return j.id + ":" + j.status; })
                    .sort()
                    .join(",");
                if (now !== rendered) {
                    clearInterval(timer);
                    location.reload();
                    return;
                }
                var anyActive = jobs.some(function (j) {
                    return j.status !== "COMPLETED" && j.status !== "FAILED";
                });
                if (!anyActive) clearInterval(timer);
            })
            .catch(function () { /* transient (network blip); retry next tick */ });
    }

    timer = setInterval(tick, INTERVAL_MS);
    tick();
})();

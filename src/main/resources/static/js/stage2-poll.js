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

    /* Dialogs (COMPLETED rows): the Re-run chooser and the §12.4 speaker-roles editor. Wired before
       the auto-poll early-returns — a page with only terminal jobs still needs the dialogs to work. */
    document.addEventListener("click", function (e) {
        // Id-targeted opener (speaker-roles editor: data-dialog-open="speakers-<jobId>").
        var openById = e.target.closest("[data-dialog-open]");
        if (openById) {
            var target = document.getElementById(openById.dataset.dialogOpen);
            if (target) {
                // Always open the §12.4 gate on its selection view, never a stale speaker preview.
                var gmain = target.querySelector(".gate-main");
                if (gmain) {
                    gmain.style.display = "";
                    target.querySelectorAll(".gate-speaker").forEach(function (el) {
                        el.style.display = "none";
                    });
                }
                target.showModal();
            }
            return;
        }

        // §12.4 selection gate: swap the dialog to a speaker's full transcript, and back.
        var speakerView = e.target.closest("[data-speaker-view]");
        if (speakerView) {
            var dlg = speakerView.closest("dialog");
            var view = document.getElementById(speakerView.dataset.speakerView);
            if (dlg && view) {
                var main = dlg.querySelector(".gate-main");
                if (main) main.style.display = "none";
                view.style.display = "";
            }
            return;
        }
        var speakerBack = e.target.closest("[data-speaker-back]");
        if (speakerBack) {
            var backDlg = speakerBack.closest("dialog");
            if (backDlg) {
                backDlg.querySelectorAll(".gate-speaker").forEach(function (el) {
                    el.style.display = "none";
                });
                var backMain = backDlg.querySelector(".gate-main");
                if (backMain) backMain.style.display = "";
            }
            return;
        }
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
                // AWAITING_SPEAKER_SELECTION waits on the operator (§12.4), so it isn't "active" —
                // stop polling and let the reload above surface the Choose-speaker gate.
                var anyActive = jobs.some(function (j) {
                    return (
                        j.status !== "COMPLETED" &&
                        j.status !== "FAILED" &&
                        j.status !== "AWAITING_SPEAKER_SELECTION"
                    );
                });
                if (!anyActive) clearInterval(timer);
            })
            .catch(function () { /* transient (network blip); retry next tick */ });
    }

    timer = setInterval(tick, INTERVAL_MS);
    tick();
})();

/* =============================================================================
   Subject window switch (VA-40) — countdown + the open-page poll driver.

   While a transition is in flight (data-poll="true"), POST /provisioning/poll
   every 15s (a cold start is ~30 min — no need to hammer); the payload carries
   the friendly view token ONLY, and the page reloads when it changes. The
   countdown ticks locally off data-ends-at.
   ========================================================================== */
(function () {
    "use strict";

    function csrfHeaders() {
        var meta = document.querySelector('meta[name="csrf-token"]');
        return meta ? { "X-CSRF-TOKEN": meta.getAttribute("content") } : {};
    }

    function initCountdown() {
        var el = document.getElementById("windowCountdown");
        if (!el) return;
        var endsAt = Date.parse(el.getAttribute("data-ends-at") || "");
        if (isNaN(endsAt)) return;

        function tick() {
            var left = endsAt - Date.now();
            if (left <= 0) {
                el.textContent = "wrapping up…";
                clearInterval(timer);
                window.setTimeout(function () { window.location.reload(); }, 20000);
                return;
            }
            var mins = Math.floor(left / 60000);
            var days = Math.floor(mins / 1440);
            var hours = Math.floor((mins % 1440) / 60);
            el.textContent =
                (days > 0 ? days + "d " : "") + hours + "h " + (mins % 60) + "m";
        }
        var timer = setInterval(tick, 30000);
        tick();
    }

    function initPoll() {
        var panel = document.getElementById("provisioningSwitch");
        if (!panel || panel.getAttribute("data-poll") !== "true") return;
        var rendered = panel.getAttribute("data-view") || "";

        function tick() {
            fetch("/provisioning/poll", { method: "POST", headers: csrfHeaders() })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (body) {
                    if (body && body.view && body.view !== rendered) {
                        window.location.reload();
                    }
                })
                .catch(function () { /* transient — retry next tick */ });
        }
        setInterval(tick, 15000);
        tick();
    }

    function init() { initCountdown(); initPoll(); }
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();

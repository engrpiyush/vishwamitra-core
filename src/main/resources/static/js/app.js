/* =============================================================================
   Vishwamitra console — client interactions (presentation only).
   No data is fetched or mutated here; everything is UX feedback + formatting.
   ========================================================================== */
(function () {
    "use strict";

    /* ---- Theme toggle ---------------------------------------------------- */
    function currentTheme() {
        return document.documentElement.getAttribute("data-theme") || "dark";
    }
    function setTheme(t) {
        document.documentElement.setAttribute("data-theme", t);
        try { localStorage.setItem("theme", t); } catch (e) { /* ignore */ }
    }
    document.addEventListener("click", function (e) {
        var toggle = e.target.closest("[data-theme-toggle]");
        if (toggle) {
            setTheme(currentTheme() === "dark" ? "light" : "dark");
            return;
        }
        var burger = e.target.closest("[data-nav-toggle]");
        if (burger) {
            var links = document.getElementById("navLinks");
            if (links) links.classList.toggle("open");
        }
    });

    /* ---- Clickable table rows (data-href) -------------------------------- */
    document.addEventListener("click", function (e) {
        if (e.target.closest("a, button, input, select, textarea, form, label")) return;
        var row = e.target.closest(".clickable[data-href]");
        if (row) window.location = row.getAttribute("data-href");
    });

    /* ---- Top progress bar ------------------------------------------------ */
    var bar = null;
    var barTimer = null;
    function ensureBar() {
        if (!bar) bar = document.getElementById("progress-bar");
        if (!bar) {
            bar = document.createElement("div");
            bar.id = "progress-bar";
            document.body.appendChild(bar);
        }
        return bar;
    }
    function startProgress() {
        var b = ensureBar();
        if (!b) return;
        clearTimeout(barTimer);
        b.classList.add("active");
        b.style.width = "0";
        // force reflow so the transition runs from 0
        void b.offsetWidth;
        b.style.width = "75%";
    }
    function finishProgress() {
        var b = ensureBar();
        if (!b) return;
        b.style.width = "100%";
        barTimer = setTimeout(function () {
            b.classList.remove("active");
            b.style.width = "0";
        }, 250);
    }
    // full-page navigations finish on unload; partials finish via HTMX events
    window.addEventListener("pageshow", finishProgress);

    /* ---- Submit feedback: spinner + disable, prevent double submit ------- */
    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (form.hasAttribute("data-no-loading")) return;
        // Runs AFTER any inline onsubmit="return confirm(...)" — if confirm was
        // cancelled the submit event never fires, so this is safe.
        var btn = form.querySelector(
            'button[type=submit], button:not([type]), input[type=submit]'
        );
        if (btn) {
            btn.classList.add("is-loading");
            btn.setAttribute("disabled", "disabled");
        }
        startProgress();
    });

    // Navigating away via plain links / clickable rows
    document.addEventListener("click", function (e) {
        var row = e.target.closest(".clickable[data-href]");
        if (row) { startProgress(); return; }
        var link = e.target.closest("a[href]");
        if (link &&
            link.getAttribute("href") &&
            link.getAttribute("href").indexOf("#") !== 0 &&
            !link.target &&
            !link.hasAttribute("data-theme-toggle") &&
            link.origin === window.location.origin) {
            startProgress();
        }
    });

    /* ---- HTMX hooks ------------------------------------------------------ */
    document.body.addEventListener("htmx:beforeRequest", startProgress);
    document.body.addEventListener("htmx:afterRequest", finishProgress);
    document.body.addEventListener("htmx:afterSwap", function () { formatTimestamps(); });

    /* ---- Date formatting -------------------------------------------------
       Reformat any <time class="js-ts" datetime="<ISO instant>">. The element's
       text is the raw value (no-JS fallback); we replace it with a localized,
       human-friendly string and keep the absolute value in the title tooltip. */
    function relative(diffMs) {
        var s = Math.round(diffMs / 1000);
        var past = s >= 0;
        s = Math.abs(s);
        var units = [
            [60, "second"], [60, "minute"], [24, "hour"],
            [7, "day"], [4.34524, "week"], [12, "month"], [Infinity, "year"]
        ];
        var val = s, name = "second";
        for (var i = 0; i < units.length; i++) {
            name = units[i][1];
            if (val < units[i][0]) break;
            val = val / units[i][0];
        }
        val = Math.round(val);
        var label = val + " " + name + (val === 1 ? "" : "s");
        return past ? label + " ago" : "in " + label;
    }
    function formatTimestamps() {
        var nodes = document.querySelectorAll("time.js-ts[datetime]");
        nodes.forEach(function (el) {
            var raw = el.getAttribute("datetime");
            if (!raw) return;
            var d = new Date(raw);
            if (isNaN(d.getTime())) return;
            var abs = d.toLocaleString(undefined, {
                year: "numeric", month: "short", day: "numeric",
                hour: "2-digit", minute: "2-digit"
            });
            el.textContent = abs;
            el.setAttribute("title", relative(Date.now() - d.getTime()) + " · " + raw);
        });
    }
    window.formatTimestamps = formatTimestamps;

    /* ---- Copy-to-clipboard for .copyable mono values --------------------- */
    document.addEventListener("click", function (e) {
        var el = e.target.closest(".copyable");
        if (!el || !navigator.clipboard) return;
        var text = el.getAttribute("data-copy") || el.textContent.trim();
        navigator.clipboard.writeText(text).then(function () {
            var prev = el.getAttribute("title");
            el.setAttribute("title", "Copied!");
            setTimeout(function () {
                if (prev) el.setAttribute("title", prev); else el.removeAttribute("title");
            }, 1200);
        });
    });

    /* ---- Lightweight confirm modal (data-confirm forms) ------------------
       Replaces native confirm(): a form with data-confirm="message" opens a
       styled modal; submitting it posts the same form unchanged. */
    function buildConfirmModal() {
        var overlay = document.createElement("div");
        overlay.className = "modal";
        overlay.setAttribute("hidden", "");
        overlay.innerHTML =
            '<div class="modal-card" role="dialog" aria-modal="true">' +
            '  <h3 data-confirm-title>Are you sure?</h3>' +
            '  <p class="muted" data-confirm-message></p>' +
            '  <div class="modal-actions">' +
            '    <button type="button" class="btn--danger" data-confirm-ok>Confirm</button>' +
            '    <button type="button" class="link" data-confirm-cancel>Cancel</button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(overlay);
        return overlay;
    }
    var confirmModal = null;
    var pendingForm = null;
    document.addEventListener("submit", function (e) {
        var form = e.target;
        if (!form.hasAttribute("data-confirm")) return;
        if (form.dataset.confirmed === "1") { form.dataset.confirmed = ""; return; }
        // Stop the bubbling loading-spinner handler from firing for a submit we
        // are about to cancel in favour of the modal.
        e.preventDefault();
        e.stopPropagation();
        if (!confirmModal) {
            confirmModal = buildConfirmModal();
            confirmModal.addEventListener("click", function (ev) {
                if (ev.target === confirmModal || ev.target.closest("[data-confirm-cancel]")) {
                    confirmModal.setAttribute("hidden", "");
                    pendingForm = null;
                } else if (ev.target.closest("[data-confirm-ok]")) {
                    confirmModal.setAttribute("hidden", "");
                    if (pendingForm) {
                        pendingForm.dataset.confirmed = "1";
                        pendingForm.requestSubmit
                            ? pendingForm.requestSubmit()
                            : pendingForm.submit();
                    }
                }
            });
        }
        pendingForm = form;
        confirmModal.querySelector("[data-confirm-message]").textContent =
            form.getAttribute("data-confirm");
        confirmModal.querySelector("[data-confirm-title]").textContent =
            form.getAttribute("data-confirm-title") || "Are you sure?";
        var okBtn = confirmModal.querySelector("[data-confirm-ok]");
        okBtn.textContent = form.getAttribute("data-confirm-ok") || "Confirm";
        okBtn.className = form.hasAttribute("data-confirm-danger") ? "btn--danger" : "";
        confirmModal.removeAttribute("hidden");
    }, true);

    /* ---- Init ------------------------------------------------------------ */
    document.addEventListener("DOMContentLoaded", formatTimestamps);
    if (document.readyState !== "loading") formatTimestamps();
})();

/* =============================================================================
   Persona wizard — step navigation (Project Neo Stage 4, VA-63).

   Pure presentation: all seven step <section>s live in one form; this script
   only toggles which is visible and recolors the stepper chips. Every input
   stays in the DOM (hidden sections still submit), so the single POST carries
   the whole sparse answer set no matter which step the user saves from.
   Server-side behavior (skip = default, validation, hashing) is untouched —
   with JS disabled the page degrades to all steps visible in order.
   ========================================================================== */
(function () {
    "use strict";

    var sections = Array.prototype.slice.call(document.querySelectorAll("[data-step]"));
    if (sections.length === 0) return;
    sections.sort(function (a, b) { return +a.dataset.step - +b.dataset.step; });

    var chips = Array.prototype.slice.call(document.querySelectorAll("[data-step-chip]"));
    var backBtn = document.querySelector("[data-step-back]");
    var nextBtn = document.querySelector("[data-step-next]");
    var last = sections.length - 1;
    var current = 0;

    function render() {
        sections.forEach(function (s, i) { s.hidden = i !== current; });
        chips.forEach(function (c, i) {
            c.classList.remove("badge--success", "badge--info", "badge--muted");
            c.classList.add(i < current ? "badge--success" : (i === current ? "badge--info" : "badge--muted"));
        });
        if (backBtn) backBtn.disabled = current === 0;
        // The final step carries the form's own Save button.
        if (nextBtn) nextBtn.style.visibility = current === last ? "hidden" : "visible";
        window.scrollTo(0, 0);
    }

    function go(step) {
        current = Math.max(0, Math.min(last, step));
        render();
    }

    if (backBtn) backBtn.addEventListener("click", function () { go(current - 1); });
    if (nextBtn) nextBtn.addEventListener("click", function () { go(current + 1); });
    chips.forEach(function (c, i) {
        c.addEventListener("click", function () { go(i); });
    });

    render();
})();

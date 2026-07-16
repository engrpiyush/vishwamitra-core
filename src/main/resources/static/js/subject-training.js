/* =============================================================================
   Subject training surface (VA-32) — uploader + progress driver.

   Upload mirrors intake-upload.js's three-call handshake, against the subject
   world's own API (/training/api — the operator /api/intake surface doesn't
   exist on subject hosts):
     1. POST /training/api/assets                    → register, get a signed PUT URL
     2. PUT the raw bytes to the returned URL         (GCS in prod; dev sink locally)
     3. POST /training/api/assets/{id}/complete       → confirm the bytes landed

   The consent attestation checkbox gates the button AND rides the register
   payload — the server refuses without it (F2, LLD §13.1).

   Progress: while any job is active, POST /training/api/poll every few seconds
   (the open page drives the run — no server-side scheduler); reload when the
   friendly states change. The payload carries friendly-state names only.
   ========================================================================== */
(function () {
    "use strict";

    var API = "/training/api";

    function csrfHeaders() {
        var meta = document.querySelector('meta[name="csrf-token"]');
        return meta ? { "X-CSRF-TOKEN": meta.getAttribute("content") } : {};
    }

    function errorFrom(text, fallback) {
        try {
            var j = JSON.parse(text);
            if (j && j.error) return j.error;
        } catch (e) { /* not JSON */ }
        return fallback;
    }

    function postJson(url, body) {
        var headers = csrfHeaders();
        if (body !== undefined) headers["Content-Type"] = "application/json";
        return fetch(url, {
            method: "POST",
            headers: headers,
            body: body !== undefined ? JSON.stringify(body) : undefined,
        }).then(function (r) {
            return r.text().then(function (t) {
                if (!r.ok) throw new Error(errorFrom(t, "That didn't go through — try again."));
                return t ? JSON.parse(t) : {};
            });
        });
    }

    function putBytes(file, upload, onProgress) {
        return new Promise(function (resolve, reject) {
            var xhr = new XMLHttpRequest();
            xhr.open(upload.method || "PUT", upload.url, true);
            var headers = upload.headers || {};
            Object.keys(headers).forEach(function (k) { xhr.setRequestHeader(k, headers[k]); });
            if (onProgress && xhr.upload) {
                xhr.upload.onprogress = function (e) {
                    if (e.lengthComputable) onProgress(e.loaded / e.total);
                };
            }
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300) resolve();
                else reject(new Error("The upload didn't finish — try again."));
            };
            xhr.onerror = function () {
                reject(new Error("The upload didn't finish — check your connection and retry."));
            };
            xhr.send(file);
        });
    }

    /* ---- S3: the upload panel --------------------------------------------- */
    function initUpload() {
        var panel = document.getElementById("subjectUpload");
        if (!panel) return;
        var $ = function (id) { return document.getElementById(id); };
        var kind = $("suKind"), kindHint = $("suKindHint"), fromWrap = $("suFromWrap"),
            from = $("suFrom"), fileInput = $("suFile"), dz = $("suDropzone"),
            browse = $("suBrowse"), fileName = $("suFileName"), title = $("suTitle"),
            attest = $("suAttest"), submit = $("suSubmit"), errorBox = $("suError"),
            progress = $("suProgress"), bar = $("suBar"), pct = $("suPct");

        var state = { assetId: null };

        function currentFile() { return fileInput.files && fileInput.files[0]; }
        function refreshKind() {
            var opt = kind.options[kind.selectedIndex];
            kindHint.textContent = opt ? (opt.getAttribute("data-hint") || "") : "";
            fromWrap.hidden = !(opt && opt.getAttribute("data-asks-who") === "true");
        }
        function refreshSubmit() {
            submit.disabled = !(attest.checked && currentFile());
        }
        function clearError() { errorBox.hidden = true; errorBox.textContent = ""; }
        function showError(msg) { errorBox.textContent = msg; errorBox.hidden = false; }
        function showProgress(frac) {
            progress.hidden = false;
            var p = Math.round((frac || 0) * 100);
            bar.style.width = p + "%";
            pct.textContent = p + "%";
        }

        function onFile() {
            var f = currentFile();
            fileName.textContent = f ? f.name : "No file selected.";
            if (f && !title.value.trim()) title.value = f.name.replace(/\.[^.]+$/, "");
            state.assetId = null;         // a new file is a new upload
            submit.textContent = "Upload";
            clearError();
            refreshSubmit();
        }

        kind.addEventListener("change", refreshKind);
        attest.addEventListener("change", refreshSubmit);
        browse.addEventListener("click", function () { fileInput.click(); });
        fileInput.addEventListener("change", onFile);
        ["dragenter", "dragover"].forEach(function (ev) {
            dz.addEventListener(ev, function (e) { e.preventDefault(); dz.classList.add("dragover"); });
        });
        ["dragleave", "drop"].forEach(function (ev) {
            dz.addEventListener(ev, function (e) { e.preventDefault(); dz.classList.remove("dragover"); });
        });
        dz.addEventListener("drop", function (e) {
            if (e.dataTransfer.files.length) { fileInput.files = e.dataTransfer.files; onFile(); }
        });
        refreshKind();
        refreshSubmit();

        submit.addEventListener("click", function () {
            var file = currentFile();
            clearError();
            if (!file || !attest.checked) return;
            submit.disabled = true;
            showProgress(0);

            // Register once; a retry after a failed PUT re-mints for the same upload.
            var obtain = state.assetId
                ? postJson(API + "/assets/" + encodeURIComponent(state.assetId) + "/upload-url")
                : postJson(API + "/assets", {
                    kind: kind.value,
                    from: fromWrap.hidden ? null : from.value,
                    title: title.value.trim() || null,
                    originalFilename: file.name,
                    mimeType: file.type || "application/octet-stream",
                    declaredSizeBytes: file.size,
                    attested: attest.checked,
                });

            obtain
                .then(function (res) {
                    state.assetId = res.asset.id;
                    return putBytes(file, res.upload, showProgress);
                })
                .then(function () {
                    return postJson(API + "/assets/" + encodeURIComponent(state.assetId) + "/complete");
                })
                .then(function () { window.location.reload(); })
                .catch(function (err) {
                    submit.disabled = false;
                    progress.hidden = true;
                    if (state.assetId) submit.textContent = "Retry upload";
                    showError(err.message || "That didn't go through — try again.");
                });
        });
    }

    /* ---- S3: per-row retry for stalled uploads ----------------------------- */
    function initRetries() {
        Array.prototype.forEach.call(document.querySelectorAll(".js-su-retry"), function (btn) {
            btn.addEventListener("click", function () {
                var assetId = btn.getAttribute("data-asset-id");
                var input = document.createElement("input");
                input.type = "file";
                input.style.display = "none";
                document.body.appendChild(input);
                input.addEventListener("change", function () {
                    var file = input.files && input.files[0];
                    if (input.parentNode) input.parentNode.removeChild(input);
                    if (!file) return;
                    btn.disabled = true;
                    postJson(API + "/assets/" + encodeURIComponent(assetId) + "/upload-url")
                        .then(function (res) {
                            return putBytes(file, res.upload, function (frac) {
                                btn.textContent = "Uploading " + Math.round(frac * 100) + "%";
                            });
                        })
                        .then(function () {
                            return postJson(API + "/assets/" + encodeURIComponent(assetId) + "/complete");
                        })
                        .then(function () { window.location.reload(); })
                        .catch(function () {
                            btn.disabled = false;
                            btn.textContent = "Retry upload";
                        });
                });
                input.click();
            });
        });
    }

    /* ---- S4: the open-page progress driver --------------------------------- */
    function initProgress() {
        var panel = document.getElementById("trainingProgress");
        if (!panel) return;
        if (parseInt(panel.getAttribute("data-active-count") || "0", 10) === 0) return;
        var rendered = panel.getAttribute("data-fingerprint") || "";
        var timer = setInterval(tick, 5000);

        function tick() {
            postJson(API + "/poll")
                .then(function (items) {
                    var now = items
                        .map(function (i) { return i.id + ":" + i.state; })
                        .sort()
                        .join(",");
                    if (now !== rendered) {
                        clearInterval(timer);
                        window.location.reload();
                        return;
                    }
                    var anyActive = items.some(function (i) {
                        return i.state === "WORKING" || i.state === "UNDERSTANDING";
                    });
                    if (!anyActive) clearInterval(timer);
                })
                .catch(function () { /* transient — retry next tick */ });
        }
        tick();
    }

    function init() { initUpload(); initRetries(); initProgress(); }
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();

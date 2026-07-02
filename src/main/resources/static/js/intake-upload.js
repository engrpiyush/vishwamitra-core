/* =============================================================================
   Intake — browser→GCS signed-URL uploader (Project Neo Stage 1).

   The one flow a plain form can't do. Three-call handshake:
     1. POST /api/intake/subjects/{id}/assets      → register, get a signed PUT URL
     2. PUT the raw bytes straight to storage       (XMLHttpRequest, for progress)
     3. POST /api/intake/assets/{id}/complete       → confirm the bytes landed

   Call 2 never touches the app: no CSRF header, no cookies. In prod it is a
   cross-origin PUT to storage.googleapis.com whose signature binds Content-Type
   exactly (sent verbatim from the returned headers); in dev it hits the app's
   own /dev/upload sink. CSRF (the prod-only <meta>) rides calls 1 & 3 only.

   A failed PUT re-mints (call 1b, POST /assets/{id}/upload-url) rather than
   re-registering, so a retry never orphans a duplicate asset.
   ========================================================================== */
(function () {
    "use strict";

    var API = "/api/intake";

    /* ---- CSRF: present only in prod (dev disables it, so the <meta> is absent) */
    function csrfHeaders() {
        var meta = document.querySelector('meta[name="csrf-token"]');
        return meta ? { "X-CSRF-TOKEN": meta.getAttribute("content") } : {};
    }

    /* ---- Pull {"error":"…"} out of a response body, else a fallback ------- */
    function errorFrom(text, fallback) {
        try {
            var j = JSON.parse(text);
            if (j && j.error) return j.error;
        } catch (e) { /* not JSON */ }
        return fallback;
    }

    /* ---- App calls: JSON in / JSON out, CSRF-guarded, same-origin -------- */
    function postJson(url, body) {
        var headers = csrfHeaders();
        if (body !== undefined) headers["Content-Type"] = "application/json";
        return fetch(url, {
            method: "POST",
            headers: headers,
            body: body !== undefined ? JSON.stringify(body) : undefined,
        }).then(function (r) {
            return r.text().then(function (t) {
                if (!r.ok) throw new Error(errorFrom(t, "Request failed (HTTP " + r.status + ")."));
                return t ? JSON.parse(t) : {};
            });
        });
    }

    function register(subjectId, payload) {
        return postJson(API + "/subjects/" + encodeURIComponent(subjectId) + "/assets", payload);
    }
    function reissue(assetId) {
        return postJson(API + "/assets/" + encodeURIComponent(assetId) + "/upload-url");
    }
    function complete(assetId) {
        return postJson(API + "/assets/" + encodeURIComponent(assetId) + "/complete");
    }

    /* ---- Call 2: PUT the raw bytes to the signed URL; resolves on 2xx ----- */
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
                else reject(new Error("Storage rejected the upload (HTTP " + xhr.status + ")."));
            };
            xhr.onerror = function () {
                reject(new Error("Upload failed — network, CORS, or the URL expired. Retry to re-mint."));
            };
            xhr.send(file);
        });
    }

    /* ---- Guess the modality from the file extension (a UX nicety) --------- */
    var EXT = {
        AUDIO: ["mp3", "wav", "m4a", "aac", "flac", "ogg"],
        VIDEO: ["mp4", "mov", "webm", "mkv"],
        IMAGE: ["jpg", "jpeg", "png", "heic", "webp", "tiff", "tif"],
        DOCUMENT: ["pdf", "docx", "doc", "rtf", "odt", "pptx"],
        TEXT: ["md", "txt"],
        ARCHIVE: ["zip"],
    };
    function modalityForExt(name) {
        var ext = (name.split(".").pop() || "").toLowerCase();
        for (var m in EXT) {
            if (EXT.hasOwnProperty(m) && EXT[m].indexOf(ext) !== -1) return m;
        }
        return null;
    }
    function selectValue(sel, val) {
        for (var i = 0; i < sel.options.length; i++) {
            if (sel.options[i].value === val) { sel.selectedIndex = i; return; }
        }
    }

    /* ---- The add-asset panel -------------------------------------------- */
    function initPanel() {
        var panel = document.getElementById("uploadPanel");
        if (!panel) return; // hidden while sealed
        var subjectId = panel.getAttribute("data-subject-id");
        var $ = function (id) { return document.getElementById(id); };

        var fileInput = $("upFile"), dz = $("upDropzone"), browse = $("upBrowse"),
            fileName = $("upFileName"), title = $("upTitle"), submit = $("upSubmit"),
            errorBox = $("upError"), progress = $("upProgress"), bar = $("upBar"), pct = $("upPct"),
            modality = $("upModality"), contentType = $("upContentType"),
            relationship = $("upRelationship"), prior = $("upPrior");

        // Re-minting instead of re-registering needs the asset id from the first try.
        var state = { assetId: null };

        function currentFile() { return fileInput.files && fileInput.files[0]; }
        function resetAsset() { state.assetId = null; submit.textContent = "Upload asset"; }

        function onFile() {
            var f = currentFile();
            fileName.textContent = f ? f.name : "No file selected.";
            if (f && !title.value.trim()) title.value = f.name.replace(/\.[^.]+$/, "");
            if (f) {
                var m = modalityForExt(f.name);
                if (m) selectValue(modality, m);
            }
            resetAsset();       // a new file is a new asset
            clearError();
        }

        // Dropzone wiring (mirrors import/index.html).
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

        // Live derived-prior preview.
        function refreshPrior() {
            var ct = contentType.value, rel = relationship.value;
            if (!ct) { prior.textContent = "—"; return; }
            fetch(API + "/prior?contentType=" + encodeURIComponent(ct) +
                  (rel ? "&relationship=" + encodeURIComponent(rel) : ""))
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (j) { prior.textContent = j ? j.authenticityPrior : "—"; })
                .catch(function () { prior.textContent = "—"; });
        }
        contentType.addEventListener("change", refreshPrior);
        relationship.addEventListener("change", refreshPrior);
        refreshPrior();

        function showProgress(frac) {
            progress.hidden = false;
            var p = Math.round((frac || 0) * 100);
            bar.style.width = p + "%";
            pct.textContent = p + "%";
        }
        function hideProgress() { progress.hidden = true; bar.style.width = "0"; pct.textContent = "0%"; }
        function clearError() { errorBox.hidden = true; errorBox.innerHTML = ""; }
        function showError(msg) {
            var li = document.createElement("li");
            li.textContent = msg;
            errorBox.innerHTML = "";
            errorBox.appendChild(li);
            errorBox.hidden = false;
        }

        function payloadFor(file) {
            return {
                title: title.value.trim() || file.name,
                modality: modality.value,
                contentType: contentType.value,
                relationship: relationship.value,
                originalFilename: file.name,
                mimeType: file.type || "application/octet-stream",
                sourceName: $("upSourceName").value.trim() || null,
                captureDate: $("upCaptureDate").value || null,
                claimedEventDate: $("upClaimedEventDate").value || null,
                consentStatus: $("upConsent").value,
                labels: $("upLabels").value.trim() || null,
                notes: $("upNotes").value.trim() || null,
                declaredSizeBytes: file.size,
            };
        }

        submit.addEventListener("click", function () {
            var file = currentFile();
            clearError();
            if (!file) { showError("Choose a file first."); return; }

            submit.disabled = true;
            showProgress(0);

            // Register the first time; on a later retry re-mint for the same asset
            // so a failed PUT never leaves a duplicate behind.
            var obtain = state.assetId
                ? reissue(state.assetId)
                : register(subjectId, payloadFor(file));

            obtain
                .then(function (res) {
                    state.assetId = res.asset.id;
                    return putBytes(file, res.upload, showProgress);
                })
                .then(function () { return complete(state.assetId); })
                .then(function () { window.location.reload(); })
                .catch(function (err) {
                    submit.disabled = false;
                    hideProgress();
                    // Registered but the bytes didn't land → next click re-mints.
                    if (state.assetId) submit.textContent = "Retry upload";
                    showError(err.message || "Upload failed.");
                });
        });
    }

    /* ---- Per-row "Retry upload" for AWAITING_UPLOAD / FAILED assets ------
       The file bytes aren't kept across a reload, so retry re-picks the file,
       re-mints a URL for the existing asset, PUTs, and completes. */
    function initRowRetries() {
        var buttons = document.querySelectorAll(".js-retry-upload");
        Array.prototype.forEach.call(buttons, function (btn) {
            btn.addEventListener("click", function () {
                var assetId = btn.getAttribute("data-asset-id");
                var input = document.createElement("input");
                input.type = "file";
                input.style.display = "none";
                document.body.appendChild(input);
                input.addEventListener("change", function () {
                    var file = input.files && input.files[0];
                    if (input.parentNode) input.parentNode.removeChild(input);
                    if (file) retryRow(btn, assetId, file);
                });
                input.click();
            });
        });
    }

    function retryRow(btn, assetId, file) {
        var turn = btn.closest ? btn.closest(".turn") : null;
        var label = btn.textContent;
        btn.disabled = true;
        var stale = turn && turn.querySelector(".upload-msg");
        if (stale && stale.parentNode) stale.parentNode.removeChild(stale);

        reissue(assetId)
            .then(function (res) {
                return putBytes(file, res.upload, function (frac) {
                    btn.textContent = "Uploading " + Math.round(frac * 100) + "%";
                });
            })
            .then(function () { return complete(assetId); })
            .then(function () { window.location.reload(); })
            .catch(function (err) {
                btn.disabled = false;
                btn.textContent = label;
                if (turn) {
                    var p = document.createElement("p");
                    p.className = "errors upload-msg";
                    p.style.margin = "0.3rem 0 0";
                    p.textContent = err.message || "Retry failed.";
                    turn.appendChild(p);
                }
            });
    }

    /* ---- Init ------------------------------------------------------------ */
    function init() { initPanel(); initRowRetries(); }
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();

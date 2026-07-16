/* =============================================================================
   Advocate chat (VA-43, S10) — plain fetch-POST + append, no framework.

   History loads once from GET /chat/history; sends go to POST /chat/send as
   JSON. Every rendered error string is either the server's friendly `message`
   or one of the fixed lines below — no machine vocabulary ever reaches the
   page (§12.3). A 401 means the capability session ended (the cookie may also
   have expired away entirely, which surfaces as a login redirect — both render
   the same "session ended" copy).
   ========================================================================== */
(function () {
    "use strict";

    var EXPIRED_COPY = "Your session has ended — ask for a new access code to continue.";
    var OFFLINE_COPY = "That didn't go through — check your connection and try again.";

    var log = document.getElementById("chatLog");
    var form = document.getElementById("chatForm");
    var input = document.getElementById("chatInput");
    var send = document.getElementById("chatSend");
    var notice = document.getElementById("chatNotice");
    if (!log || !form) return;

    function csrfHeaders() {
        var meta = document.querySelector('meta[name="csrf-token"]');
        return meta ? { "X-CSRF-TOKEN": meta.getAttribute("content") } : {};
    }

    function append(who, text) {
        var row = document.createElement("div");
        row.className = "chat-msg chat-msg--" + who;
        var bubble = document.createElement("p");
        bubble.className = "chat-bubble";
        bubble.textContent = text;
        row.appendChild(bubble);
        log.appendChild(row);
        log.scrollTop = log.scrollHeight;
        return row;
    }

    function showNotice(text, sticky) {
        notice.textContent = text;
        notice.hidden = false;
        if (!sticky) {
            window.setTimeout(function () { notice.hidden = true; }, 6000);
        }
    }

    /** The conversation is over (session expired / cap reached) — no more sends. */
    function endConversation(text) {
        showNotice(text, true);
        input.disabled = true;
        send.disabled = true;
    }

    function loadHistory() {
        fetch("/chat/history", { headers: { "Accept": "application/json" } })
            .then(function (r) {
                if (r.status === 401) { endConversation(EXPIRED_COPY); return null; }
                return r.ok && !r.redirected ? r.json() : null;
            })
            .then(function (body) {
                if (!body || !body.messages) return;
                body.messages.forEach(function (m) { append(m.who, m.text); });
            })
            .catch(function () { /* first load only — sending still works */ });
    }

    function handleSendResponse(r, pendingRow) {
        // The capability cookie can expire away entirely, turning the 401 into a login
        // redirect — treat both as the end of the session.
        if (r.status === 401 || r.redirected) {
            pendingRow.remove();
            endConversation(EXPIRED_COPY);
            return null;
        }
        return r.json().then(function (body) {
            if (r.ok) {
                pendingRow.querySelector(".chat-bubble").textContent = body.reply;
                pendingRow.classList.remove("chat-msg--pending");
                if (body.truncated) {
                    showNotice("That was a long one — we kept the first part.");
                }
                return;
            }
            pendingRow.remove();
            if (body && body.reason === "conversation_full") {
                endConversation(body.message);
            } else {
                showNotice((body && body.message) || OFFLINE_COPY);
            }
        });
    }

    form.addEventListener("submit", function (e) {
        e.preventDefault();
        var text = (input.value || "").trim();
        if (!text || send.disabled) return;
        append("you", text);
        input.value = "";
        var pendingRow = append("advocate", "…");
        pendingRow.classList.add("chat-msg--pending");
        send.disabled = true;
        var headers = csrfHeaders();
        headers["Content-Type"] = "application/json";
        headers["Accept"] = "application/json";
        fetch("/chat/send", {
            method: "POST",
            headers: headers,
            body: JSON.stringify({ text: text })
        })
            .then(function (r) { return handleSendResponse(r, pendingRow); })
            .catch(function () {
                pendingRow.remove();
                showNotice(OFFLINE_COPY);
            })
            .then(function () {
                if (!input.disabled) {
                    send.disabled = false;
                    input.focus();
                }
            });
    });

    // Enter sends; Shift+Enter keeps the newline (textarea default).
    input.addEventListener("keydown", function (e) {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            form.requestSubmit();
        }
    });

    loadHistory();
})();

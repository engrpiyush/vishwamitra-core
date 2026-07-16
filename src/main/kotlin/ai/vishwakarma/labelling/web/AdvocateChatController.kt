package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateMessage
import ai.vishwakarma.labelling.domain.AdvocateSession
import ai.vishwakarma.labelling.domain.MessageSender
import ai.vishwakarma.labelling.domain.SessionKind
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.GuestCtx
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.AdvocateChatService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * The chat endpoints (VA-42, LLD §6.4/§7.5), on the subject chain's chat rule (operator ∨
 * subject-of-host ∨ validated guest capability session; an expired guest cookie already answers 401
 * `{reason: "session_expired"}` from the chain's entry point). Guests act only on their own
 * capability session; SUBJECT/OPERATOR sessions are minted lazily on the first message. The JSON
 * vocabulary is friendly-only (§12.3) — reasons are stable tokens, messages render verbatim, and no
 * payload ever carries endpoint/model identifiers.
 */
@RestController
@RequestMapping("/s/chat")
class AdvocateChatController(private val chat: AdvocateChatService) {

    data class SendRequest(val text: String? = null)

    private fun ctx(request: HttpServletRequest): SubjectCtx =
        SubjectCtx.of(request) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @PostMapping("/send")
    fun send(
        request: HttpServletRequest,
        @RequestBody body: SendRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val ctx = ctx(request)
        val text = body.text?.trim().orEmpty()
        if (text.isEmpty())
            return ResponseEntity.badRequest()
                .body(mapOf("reason" to "empty", "message" to "Say something first."))
        val session =
            sessionForSend(request, ctx)
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("reason" to "session_expired"))
        return chat
            .chat(ctx, session, text)
            .fold(
                { refusal ->
                    ResponseEntity.status(refusal.status)
                        .body(mapOf("reason" to refusal.reason, "message" to refusal.message))
                },
                { reply ->
                    ResponseEntity.ok(mapOf("reply" to reply.reply, "truncated" to reply.truncated))
                },
            )
    }

    /** The caller's OWN transcript (chat bootstrap) — never mints a session (§6.4). */
    @GetMapping("/history")
    fun history(request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val ctx = ctx(request)
        val session =
            GuestCtx.of(request)?.let { chat.guestSession(it.sessionId) }
                ?: memberIdentity(ctx)?.let { (kind, email) ->
                    chat.findMemberSession(ctx, kind, email)
                }
        val rows = session?.let { chat.history(it) }.orEmpty()
        return ResponseEntity.ok(mapOf("messages" to rows.map { it.toOwnView() }))
    }

    /** Transcript access (VA-42): the subject + operators list their advocate's sessions. */
    @GetMapping("/sessions")
    fun sessions(request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val ctx = ctx(request)
        requireMember(ctx)
        return ResponseEntity.ok(
            mapOf("sessions" to chat.sessions(ctx.subjectId).map { it.toView() })
        )
    }

    @GetMapping("/sessions/{sessionId}")
    fun transcript(
        request: HttpServletRequest,
        @PathVariable sessionId: String,
    ): ResponseEntity<Map<String, Any?>> {
        val ctx = ctx(request)
        requireMember(ctx)
        val rows =
            chat.transcript(ctx.subjectId, sessionId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity.ok(mapOf("messages" to rows.map { it.toTranscriptView() }))
    }

    /** Guest → their capability session; member → find-or-mint (lazily, on the first message). */
    private fun sessionForSend(request: HttpServletRequest, ctx: SubjectCtx): AdvocateSession? {
        GuestCtx.of(request)?.let {
            return chat.guestSession(it.sessionId)
        }
        val (kind, email) =
            memberIdentity(ctx) ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return chat.mintMemberSession(ctx, kind, email)
    }

    /**
     * The authenticated caller's chat identity: OPERATOR for the operator hierarchy, SUBJECT for
     * the host's own subject. The URL rule already excluded everyone else — the null branch is
     * belt-and-braces.
     */
    private fun memberIdentity(ctx: SubjectCtx): Pair<SessionKind, String>? {
        val email = CurrentUser.email() ?: return null
        return when {
            CurrentUser.isOperator() -> SessionKind.OPERATOR to email
            CurrentUser.subjectId() == ctx.subjectId -> SessionKind.SUBJECT to email
            else -> null
        }
    }

    /** Session listing/reading is member-only — a guest capability never grants it (§12.1). */
    private fun requireMember(ctx: SubjectCtx) {
        memberIdentity(ctx) ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    /** The chat surface's own-view rows: just "you" vs "advocate" — no kind vocabulary. */
    private fun AdvocateMessage.toOwnView(): Map<String, Any?> =
        mapOf(
            "who" to (if (sender == MessageSender.ADVOCATE) "advocate" else "you"),
            "text" to text,
            "at" to createdAt?.toString(),
        )

    /** Transcript rows for the subject/operator readers — sender named, still friendly. */
    private fun AdvocateMessage.toTranscriptView(): Map<String, Any?> =
        mapOf(
            "sender" to sender.name.lowercase(),
            "text" to text,
            "at" to createdAt?.toString(),
            "truncated" to truncated,
        )

    private fun AdvocateSession.toView(): Map<String, Any?> =
        mapOf(
            "sessionId" to sessionId,
            "kind" to kind.name.lowercase(),
            "with" to guestEmail,
            "startedAt" to createdAt?.toString(),
            "messageCount" to messageCount,
            "lastActivityAt" to lastActivityAt?.toString(),
        )
}

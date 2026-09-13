package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.AdvocateMessage
import ai.vishwakarma.labelling.domain.AdvocateSession
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.MessageSender
import ai.vishwakarma.labelling.domain.SessionKind
import ai.vishwakarma.labelling.persistence.AdvocateMessageRepository
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.AdvocateSessionRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.serving.ChatMessage
import ai.vishwakarma.labelling.serving.ChatRequest
import ai.vishwakarma.labelling.serving.ServingBackend
import ai.vishwakarma.labelling.stage4.Stage4Generation
import ai.vishwakarma.labelling.stage4.Stage4SystemPrompts
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.security.MessageDigest
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Why a send was refused (LLD §7.5 guards). [reason] is a stable token for the chat JS, [message]
 * the friendly line it may render verbatim — no machine vocabulary in either (§12.3); anything the
 * backend actually said stays in the server log.
 */
sealed class ChatRefusal(val status: Int, val reason: String, val message: String) {
    /** Advocate not LIVE — the §8.2 collapse: one line whatever the underlying state. */
    data object NotAvailable :
        ChatRefusal(409, "not_available", "This advocate isn't taking conversations right now.")

    /** The §7.5 per-session cap tripped. */
    data object ConversationFull :
        ChatRefusal(
            409,
            "conversation_full",
            "This conversation has reached its limit — thanks for talking!",
        )

    /** Sends inside the §7.5 pace window. */
    data object TooFast :
        ChatRefusal(429, "too_fast", "One message at a time — give it a second and try again.")

    /** The backend call failed; details are logged server-side only. */
    data object TryAgain :
        ChatRefusal(502, "try_again", "That didn't go through — try again in a moment.")
}

/** A delivered exchange: the advocate's reply plus what actually got persisted. */
data class ChatReply(val reply: String, val truncated: Boolean, val messageCount: Int)

/**
 * The §7.5 serving wrapper (VA-42) — the ONLY component that talks to the deployed model; guests,
 * subjects and operators all come through [chat]. Injection hardening is layered, not clever: the
 * system prompt is the server-side `advocate_system` row (resolved fresh per send, `{{subject}}`
 * substituted), roles stay separated by the chat template, there are no tools or retrieval, the
 * caps bound the blast radius, and every row lands in `advocate_messages` against the F9
 * accountability tuple. Backend errors are caught here — subjects and guests only ever see
 * [ChatRefusal] copy, never endpoint or model identifiers.
 */
@Service
class AdvocateChatService(
    private val advocates: AdvocateRepository,
    private val sessions: AdvocateSessionRepository,
    private val messages: AdvocateMessageRepository,
    private val prompts: ExtractionPromptService,
    private val subjects: SubjectRepository,
    private val personaService: PersonaService,
    backends: List<ServingBackend>,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val backendsById = backends.associateBy { it.id }

    /** One guarded exchange (LLD §7.5 pseudocode): guards → history → system row → backend. */
    fun chat(
        ctx: SubjectCtx,
        session: AdvocateSession,
        userText: String,
    ): Either<ChatRefusal, ChatReply> {
        val state = advocates.find(ctx.subjectId)?.state ?: AdvocateState.NOT_BUILT
        if (state != AdvocateState.LIVE) return ChatRefusal.NotAvailable.left()
        if (session.messageCount >= props.product.maxMessagesPerSession)
            return ChatRefusal.ConversationFull.left()
        val now = Instant.now()
        val last = session.lastActivityAt
        if (last != null && last.plus(props.product.chatMinInterval).isAfter(now))
            return ChatRefusal.TooFast.left()
        val truncated = userText.length > MAX_INPUT_CHARS
        val text = if (truncated) userText.take(MAX_INPUT_CHARS) else userText

        val history = messages.listBySession(session.sessionId).takeLast(HISTORY_MESSAGES)
        val system = systemPromptFor(ctx)
        val request =
            ChatRequest(
                messages =
                    buildList {
                        add(ChatMessage("system", system))
                        history.forEach { add(ChatMessage(roleOf(it.sender), it.text)) }
                        add(ChatMessage("user", text))
                    }
            )
        val reply =
            if (props.serving.dryRun) cannedReply(ctx, text)
            else
                try {
                    backend()?.chat(request)
                        ?: run {
                            log.warn(
                                "OPERATOR ATTENTION: no serving backend '{}' for chat on {}",
                                props.serving.backend,
                                ctx.subjectId,
                            )
                            return ChatRefusal.TryAgain.left()
                        }
                } catch (e: Exception) {
                    // Verbatim detail stays server-side (§12.3) — the guest gets the friendly line.
                    log.warn(
                        "OPERATOR ATTENTION: advocate chat failed for {}: {}",
                        ctx.subjectId,
                        e.message,
                    )
                    return ChatRefusal.TryAgain.left()
                }
        val answeredAt = Instant.now()
        messages.save(
            AdvocateMessage(
                id = messages.newId(),
                sessionId = session.sessionId,
                subjectId = ctx.subjectId,
                sender = senderOf(session.kind),
                text = text,
                createdAt = now,
                truncated = truncated,
            )
        )
        messages.save(
            AdvocateMessage(
                id = messages.newId(),
                sessionId = session.sessionId,
                subjectId = ctx.subjectId,
                sender = MessageSender.ADVOCATE,
                text = reply,
                createdAt = answeredAt,
                latencyMs = answeredAt.toEpochMilli() - now.toEpochMilli(),
            )
        )
        val updated = session.copy(messageCount = session.messageCount + 2, lastActivityAt = now)
        sessions.save(updated)
        return ChatReply(reply, truncated, updated.messageCount).right()
    }

    /**
     * Find-or-mint the SUBJECT/OPERATOR session (LLD §6.4: minted lazily on the first message — one
     * uniform transcript model). The id is deterministic per (kind, subject, email), so repeat
     * visits keep appending to the same transcript; [AdvocateSession.guestEmail] carries the login
     * email for these kinds (the F9 "who" for non-guest chats).
     */
    fun mintMemberSession(ctx: SubjectCtx, kind: SessionKind, email: String): AdvocateSession {
        val id = memberSessionId(ctx.subjectId, kind, email)
        sessions.find(id)?.let {
            return it
        }
        val session =
            AdvocateSession(
                sessionId = id,
                subjectId = ctx.subjectId,
                guestEmail = email,
                kind = kind,
                createdAt = Instant.now(),
                // Rides the login session — no capability TTL (§6.4).
                expiresAt = null,
            )
        sessions.save(session)
        return session
    }

    /** The member session if one exists — history reads must not mint (§6.4). */
    fun findMemberSession(ctx: SubjectCtx, kind: SessionKind, email: String): AdvocateSession? =
        sessions.find(memberSessionId(ctx.subjectId, kind, email))

    /**
     * The guest capability session behind a validated [ai.vishwakarma.labelling.security.GuestCtx].
     */
    fun guestSession(sessionId: String): AdvocateSession? = sessions.find(sessionId)

    /** A session's transcript, oldest first. */
    fun history(session: AdvocateSession): List<AdvocateMessage> =
        messages.listBySession(session.sessionId)

    /** All of a subject's sessions, newest first (transcript access — subject/operator only). */
    fun sessions(subjectId: String): List<AdvocateSession> = sessions.listBySubject(subjectId)

    /** One session's transcript, ownership re-asserted against the subject (§12.1). */
    fun transcript(subjectId: String, sessionId: String): List<AdvocateMessage>? {
        val session = sessions.find(sessionId)?.takeIf { it.subjectId == subjectId } ?: return null
        return messages.listBySession(session.sessionId)
    }

    /**
     * The serve-time system prompt. With `app.stage4.system-prompts` on, the advocate serves under
     * the SAME composed static header its training conversations opened with (LLD §9.6 train/serve
     * consistency — a tuned model must not meet a prompt it never saw); the per-conversation rules
     * and claim-subset blocks stay train-only, since serving knows neither the template nor a
     * subset (the tuned weights carry the facts). Flag off keeps the legacy `advocate_system` row
     * byte-for-byte.
     */
    private fun systemPromptFor(ctx: SubjectCtx): String {
        if (!props.stage4.systemPrompts) {
            return prompts
                .resolveKey(ExtractionPromptService.ADVOCATE_SYSTEM_KEY)
                .instructions
                .replace("{{subject}}", ctx.displayName)
        }
        val subject = subjects.findById(ctx.subjectId)
        val advocateName =
            runCatching { personaService.resolved(ctx.subjectId).advocateName }
                .getOrDefault("the advocate")
        return Stage4Generation.substituteContext(
            Stage4SystemPrompts.resolveHeader(
                prompts.resolveKey(ExtractionPromptService.STAGE4_SYSTEM_HEADER_KEY).instructions,
                subject?.displayName ?: ctx.displayName,
                subject?.contactEmail.orEmpty(),
                advocateName,
            ),
            "",
            "",
        )
    }

    private fun backend(): ServingBackend? = backendsById[props.serving.backend]

    /** OpenAI role vocabulary for the bounded history window (§7.5). */
    private fun roleOf(sender: MessageSender): String =
        if (sender == MessageSender.ADVOCATE) "assistant" else "user"

    private fun senderOf(kind: SessionKind): MessageSender =
        when (kind) {
            SessionKind.GUEST -> MessageSender.GUEST
            SessionKind.SUBJECT -> MessageSender.SUBJECT
            SessionKind.OPERATOR -> MessageSender.OPERATOR
        }

    /** Dev-profile canned advocate (VA-42): the whole stack runs on the emulator, no endpoint. */
    private fun cannedReply(ctx: SubjectCtx, text: String): String =
        "I'm ${ctx.displayName}'s advocate. You asked: \"${text.take(120)}\" — once my full " +
            "training is live I'll answer that from ${ctx.displayName}'s verified evidence. " +
            "(Local test reply.)"

    companion object {
        /** §7.5: input cap — longer sends are truncated and flagged, never refused. */
        const val MAX_INPUT_CHARS = 2000

        /** §7.5: bounded context window — the last N transcript rows sent to the model. */
        const val HISTORY_MESSAGES = 10

        /** Deterministic member-session doc id — stable per (kind, subject, email). */
        fun memberSessionId(subjectId: String, kind: SessionKind, email: String): String =
            "m-" +
                kind.name.lowercase() +
                "-" +
                MessageDigest.getInstance("SHA-256")
                    .digest("$subjectId:${email.lowercase()}".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(24)
    }
}

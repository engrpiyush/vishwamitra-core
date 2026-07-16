package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Advocate
import ai.vishwakarma.labelling.domain.AdvocateMessage
import ai.vishwakarma.labelling.domain.AdvocateSession
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.MessageSender
import ai.vishwakarma.labelling.domain.SessionKind
import ai.vishwakarma.labelling.persistence.AdvocateMessageRepository
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.AdvocateSessionRepository
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.serving.ChatRequest
import ai.vishwakarma.labelling.serving.Deployment
import ai.vishwakarma.labelling.serving.ServeRequest
import ai.vishwakarma.labelling.serving.ServingBackend
import ai.vishwakarma.labelling.serving.ServingHandle
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class ChatAdvocateRepo : AdvocateRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Advocate>()

    override fun find(subjectId: String): Advocate? = store[subjectId]
}

private class ChatSessionRepo : AdvocateSessionRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, AdvocateSession>()

    override fun find(sessionId: String): AdvocateSession? = store[sessionId]

    override fun listBySubject(subjectId: String): List<AdvocateSession> =
        store.values.filter { it.subjectId == subjectId }

    override fun save(session: AdvocateSession) {
        store[session.sessionId] = session
    }
}

private class ChatMessageRepo : AdvocateMessageRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<AdvocateMessage>()
    private var seq = 0

    override fun newId(): String = "msg-${++seq}"

    override fun save(message: AdvocateMessage) {
        store += message
    }

    override fun listBySession(sessionId: String): List<AdvocateMessage> =
        store.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }
}

private class ChatPromptRepo : ExtractionPromptRepository(mock(Firestore::class.java)) {
    var override: ExtractionPrompt? = null

    override fun findById(id: String): ExtractionPrompt? = override?.takeIf { it.id == id }
}

private class ScriptedChatBackend : ServingBackend {
    override val id = "vertex"
    var reply: String = "A grounded reply."
    var fail = false
    var lastRequest: ChatRequest? = null
    var calls = 0

    override fun target() = "test"

    override fun beginServe(req: ServeRequest) = ServingHandle(state = error("unused"))

    override fun advance(handle: ServingHandle) = handle

    override fun beginTeardown(handle: ServingHandle) = handle

    override fun deployments(): List<Deployment> = emptyList()

    override fun chat(req: ChatRequest): String {
        calls++
        lastRequest = req
        if (fail) throw IllegalStateException("endpoint 500: projects/x/endpoints/123 exploded")
        return reply
    }
}

/**
 * The §7.5 serving wrapper (VA-42): guards, bounded history, system row, persistence, dev double.
 */
class AdvocateChatServiceTest {

    private val ctx = SubjectCtx(subjectId = "s1", handle = "asha", displayName = "Asha")
    private val advocates = ChatAdvocateRepo()
    private val sessions = ChatSessionRepo()
    private val messages = ChatMessageRepo()
    private val promptRepo = ChatPromptRepo()
    private val backend = ScriptedChatBackend()

    private fun props(dryRun: Boolean = false) =
        AppProperties(
            product =
                AppProperties.Product(
                    maxMessagesPerSession = 30,
                    chatMinInterval = Duration.ofSeconds(3),
                ),
            serving = AppProperties.Serving(dryRun = dryRun, backend = "vertex"),
        )

    private fun service(dryRun: Boolean = false) =
        AdvocateChatService(
            advocates,
            sessions,
            messages,
            ExtractionPromptService(promptRepo),
            listOf(backend),
            props(dryRun),
        )

    private fun liveAdvocate() {
        advocates.store["s1"] = Advocate(subjectId = "s1", state = AdvocateState.LIVE)
    }

    private fun guestSession(
        messageCount: Int = 0,
        lastActivityAt: Instant? = null,
    ): AdvocateSession {
        val session =
            AdvocateSession(
                sessionId = "g-1",
                subjectId = "s1",
                tokenId = "tok-hash",
                guestEmail = "guest@example.com",
                kind = SessionKind.GUEST,
                createdAt = Instant.now(),
                expiresAt = Instant.now().plusSeconds(3600),
                messageCount = messageCount,
                lastActivityAt = lastActivityAt,
            )
        sessions.save(session)
        return session
    }

    @Test
    fun `happy path appends both rows, bumps the counter and touches lastActivityAt`() {
        liveAdvocate()
        val result = service().chat(ctx, guestSession(), "What has Asha built?")
        val reply = assertNotNull(result.getOrNull())
        assertEquals("A grounded reply.", reply.reply)
        assertFalse(reply.truncated)
        assertEquals(2, reply.messageCount)
        assertEquals(
            listOf(MessageSender.GUEST, MessageSender.ADVOCATE),
            messages.store.map { it.sender },
        )
        assertTrue(messages.store.all { it.sessionId == "g-1" && it.subjectId == "s1" })
        assertNotNull(messages.store[1].latencyMs)
        val session = assertNotNull(sessions.store["g-1"])
        assertEquals(2, session.messageCount)
        assertNotNull(session.lastActivityAt)
    }

    @Test
    fun `system prompt is first, subject-substituted, with the user text last`() {
        liveAdvocate()
        service().chat(ctx, guestSession(), "hello")
        val request = assertNotNull(backend.lastRequest)
        assertEquals("system", request.messages.first().role)
        assertTrue(request.messages.first().content.contains("Asha"))
        assertFalse(request.messages.first().content.contains("{{subject}}"))
        assertEquals("user", request.messages.last().role)
        assertEquals("hello", request.messages.last().content)
        assertEquals(512, request.maxTokens)
        assertEquals(0.7, request.temperature)
    }

    @Test
    fun `an admin override row displaces the builtin system prompt`() {
        liveAdvocate()
        promptRepo.override =
            ExtractionPrompt(
                id = ExtractionPromptService.ADVOCATE_SYSTEM_KEY,
                instructions = "Custom rules for {{subject}}.",
                version = 3,
            )
        service().chat(ctx, guestSession(), "hello")
        assertEquals(
            "Custom rules for Asha.",
            assertNotNull(backend.lastRequest).messages.first().content,
        )
    }

    @Test
    fun `history is bounded to the last ten rows`() {
        liveAdvocate()
        val session = guestSession()
        repeat(14) { i ->
            messages.save(
                AdvocateMessage(
                    id = "seed-$i",
                    sessionId = session.sessionId,
                    subjectId = "s1",
                    sender = if (i % 2 == 0) MessageSender.GUEST else MessageSender.ADVOCATE,
                    text = "m$i",
                    createdAt = Instant.now().minusSeconds((100 - i).toLong()),
                )
            )
        }
        service().chat(ctx, session, "latest question")
        val request = assertNotNull(backend.lastRequest)
        // 1 system + 10 history + 1 user.
        assertEquals(12, request.messages.size)
        assertEquals("m4", request.messages[1].content)
        assertEquals("assistant", request.messages[2].role)
    }

    @Test
    fun `not LIVE refuses with the friendly state line and touches nothing`() {
        advocates.store["s1"] = Advocate(subjectId = "s1", state = AdvocateState.PROVISIONING)
        val refusal = service().chat(ctx, guestSession(), "hi").swap().getOrNull()
        assertIs<ChatRefusal.NotAvailable>(refusal)
        assertEquals(409, refusal.status)
        assertTrue(messages.store.isEmpty())
        assertEquals(0, backend.calls)
    }

    @Test
    fun `a missing advocate row refuses the same way`() {
        val refusal = service().chat(ctx, guestSession(), "hi").swap().getOrNull()
        assertIs<ChatRefusal.NotAvailable>(refusal)
    }

    @Test
    fun `the transcript cap refuses message 31`() {
        liveAdvocate()
        val refusal =
            service().chat(ctx, guestSession(messageCount = 30), "one more").swap().getOrNull()
        assertIs<ChatRefusal.ConversationFull>(refusal)
        assertEquals(409, refusal.status)
    }

    @Test
    fun `sends inside the pace window answer 429`() {
        liveAdvocate()
        val session = guestSession(messageCount = 2, lastActivityAt = Instant.now())
        val refusal = service().chat(ctx, session, "again!").swap().getOrNull()
        assertIs<ChatRefusal.TooFast>(refusal)
        assertEquals(429, refusal.status)
        assertEquals(0, backend.calls)
    }

    @Test
    fun `long input is truncated and the flag persisted`() {
        liveAdvocate()
        val result = service().chat(ctx, guestSession(), "x".repeat(2500))
        assertTrue(assertNotNull(result.getOrNull()).truncated)
        val userRow = messages.store.first { it.sender == MessageSender.GUEST }
        assertEquals(AdvocateChatService.MAX_INPUT_CHARS, userRow.text.length)
        assertTrue(userRow.truncated)
        assertEquals(
            AdvocateChatService.MAX_INPUT_CHARS,
            assertNotNull(backend.lastRequest).messages.last().content.length,
        )
    }

    @Test
    fun `a backend failure maps to the friendly retry line and leaks nothing`() {
        liveAdvocate()
        backend.fail = true
        val refusal = service().chat(ctx, guestSession(), "hi").swap().getOrNull()
        assertIs<ChatRefusal.TryAgain>(refusal)
        // The endpoint detail from the exception must never reach the caller (§12.3).
        assertFalse(refusal.message.contains("endpoint"))
        assertTrue(messages.store.isEmpty())
        assertEquals(0, sessions.store.getValue("g-1").messageCount)
    }

    @Test
    fun `dev dry-run answers canned without touching the backend`() {
        liveAdvocate()
        val result = service(dryRun = true).chat(ctx, guestSession(), "who is Asha?")
        val reply = assertNotNull(result.getOrNull())
        assertTrue(reply.reply.contains("Asha"))
        assertEquals(0, backend.calls)
        assertEquals(2, messages.store.size)
    }

    @Test
    fun `member sessions mint lazily with a deterministic id and are reused`() {
        liveAdvocate()
        val svc = service()
        assertEquals(null, svc.findMemberSession(ctx, SessionKind.SUBJECT, "asha@example.com"))
        val minted = svc.mintMemberSession(ctx, SessionKind.SUBJECT, "asha@example.com")
        assertEquals(SessionKind.SUBJECT, minted.kind)
        assertEquals("asha@example.com", minted.guestEmail)
        assertEquals(null, minted.expiresAt)
        val again = svc.mintMemberSession(ctx, SessionKind.SUBJECT, "asha@example.com")
        assertEquals(minted.sessionId, again.sessionId)
        assertEquals(1, sessions.store.size)
        // Different kind or email = a different transcript.
        val operator = svc.mintMemberSession(ctx, SessionKind.OPERATOR, "op@vishwakarma.ai")
        assertTrue(operator.sessionId != minted.sessionId)
    }

    @Test
    fun `transcript access re-asserts subject ownership`() {
        liveAdvocate()
        val svc = service()
        val session = guestSession()
        svc.chat(ctx, session, "hello")
        assertEquals(2, assertNotNull(svc.transcript("s1", session.sessionId)).size)
        assertEquals(null, svc.transcript("someone-else", session.sessionId))
    }
}

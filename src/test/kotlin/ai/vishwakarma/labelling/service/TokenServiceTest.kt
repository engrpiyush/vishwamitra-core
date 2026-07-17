package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Advocate
import ai.vishwakarma.labelling.domain.AdvocateSession
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.AdvocateToken
import ai.vishwakarma.labelling.domain.OpsCounters
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.SessionKind
import ai.vishwakarma.labelling.domain.TokenEmailStatus
import ai.vishwakarma.labelling.domain.TokenStatus
import ai.vishwakarma.labelling.domain.User
import ai.vishwakarma.labelling.domain.WallAttempt
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.AdvocateSessionRepository
import ai.vishwakarma.labelling.persistence.AdvocateTokenRepository
import ai.vishwakarma.labelling.persistence.MailBookkeepingRepository
import ai.vishwakarma.labelling.persistence.OpsCounterRepository
import ai.vishwakarma.labelling.persistence.UserRepository
import ai.vishwakarma.labelling.persistence.WallAttemptRepository
import ai.vishwakarma.labelling.security.SubjectCtx
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakeTokenSessionRepo : AdvocateSessionRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, AdvocateSession>()

    override fun find(sessionId: String): AdvocateSession? = store[sessionId]

    override fun listBySubject(subjectId: String): List<AdvocateSession> =
        store.values.filter { it.subjectId == subjectId }

    override fun save(session: AdvocateSession) {
        store[session.sessionId] = session
    }
}

/** In-memory mirror of the repo's transactional semantics (the §6.3 check-then-flip). */
private class FakeTokenTokenRepo(private val sessionsRepo: FakeTokenSessionRepo) :
    AdvocateTokenRepository(mock(Firestore::class.java), sessionsRepo) {
    val store = mutableMapOf<String, AdvocateToken>()

    override fun find(tokenHash: String): AdvocateToken? = store[tokenHash]

    override fun listBySubject(subjectId: String): List<AdvocateToken> =
        store.values.filter { it.subjectId == subjectId }.sortedByDescending { it.createdAt }

    override fun save(token: AdvocateToken) {
        store[token.tokenHash] = token
    }

    override fun markEmailFailed(tokenHash: String) {
        store[tokenHash] = store.getValue(tokenHash).copy(emailStatus = TokenEmailStatus.FAILED)
    }

    override fun redeem(
        tokenHash: String,
        subjectId: String,
        sessionId: String,
        ttl: Duration,
    ): AdvocateSession? {
        val token = store[tokenHash]
        if (token == null || token.subjectId != subjectId || token.status != TokenStatus.UNREDEEMED)
            return null
        val now = Instant.now()
        val session =
            AdvocateSession(
                sessionId = sessionId,
                subjectId = subjectId,
                tokenId = tokenHash,
                guestEmail = token.guestEmail,
                kind = SessionKind.GUEST,
                createdAt = now,
                expiresAt = now.plus(ttl),
                lastActivityAt = now,
            )
        store[tokenHash] =
            token.copy(status = TokenStatus.REDEEMED, redeemedAt = now, sessionId = sessionId)
        sessionsRepo.store[sessionId] = session
        return session
    }

    override fun revokeIfUnredeemed(tokenHash: String): Boolean {
        val token = store[tokenHash] ?: return false
        if (token.status != TokenStatus.UNREDEEMED) return false
        store[tokenHash] = token.copy(status = TokenStatus.REVOKED)
        return true
    }
}

private class FakeTokenAdvocateRepo : AdvocateRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, Advocate>()

    override fun find(subjectId: String): Advocate? = store[subjectId]
}

/** Runs the REAL §6.3 decide() step against an in-memory bucket, on a movable clock. */
private class FakeTokenAttemptRepo : WallAttemptRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, WallAttempt>()
    var now: Instant = Instant.parse("2026-07-16T10:00:00Z")

    override fun tryAttempt(bucketKey: String, limitPerMinute: Int, lockout: Duration): Boolean {
        val next = decide(bucketKey, store[bucketKey], limitPerMinute, lockout, now)
        store[bucketKey] = next
        return next.lockedUntil == null
    }
}

private class FakeTokenBookkeeping : MailBookkeepingRepository(mock(Firestore::class.java)) {
    val provisioningMarks = mutableSetOf<String>()

    override fun countSendIfBelow(date: String, cap: Int): Boolean = true

    override fun provisioningRequestSent(date: String, subjectId: String): Boolean =
        "${date}_$subjectId" in provisioningMarks

    override fun markProvisioningRequest(date: String, subjectId: String) {
        provisioningMarks += "${date}_$subjectId"
    }
}

private class FakeTokenUserRepo : UserRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<User>()

    override fun findAll(): List<User> = store
}

/** VA-68: records bumps per counter key (per-subject scoping is not under test here). */
private class FakeTokenOpsRepo : OpsCounterRepository(mock(Firestore::class.java)) {
    val counters = mutableMapOf<String, Double>()

    override fun bump(
        subjectId: String,
        increments: Map<String, Number>,
        sets: Map<String, Number>,
    ) {
        increments.forEach { (k, v) -> counters.merge(k, v.toDouble(), Double::plus) }
        sets.forEach { (k, v) -> counters[k] = v.toDouble() }
    }
}

private class TokenRecordingTransport : MailTransport {
    val sent = mutableListOf<Triple<String, String, String>>()
    var failWith: Exception? = null

    override fun send(from: String, to: String, subject: String, body: String) {
        failWith?.let { throw it }
        sent += Triple(to, subject, body)
    }
}

class TokenServiceTest {

    private val sessions = FakeTokenSessionRepo()
    private val tokens = FakeTokenTokenRepo(sessions)
    private val advocates = FakeTokenAdvocateRepo()
    private val attempts = FakeTokenAttemptRepo()
    private val ops = FakeTokenOpsRepo()
    private val bookkeeping = FakeTokenBookkeeping()
    private val userRepo = FakeTokenUserRepo()
    private val transport = TokenRecordingTransport()
    private val props =
        AppProperties(
            product =
                AppProperties.Product(
                    baseDomain = "localhost",
                    operatorDomain = "localhost",
                    tokenPepper = "pepper-A",
                )
        )
    private val service = service(props)

    private val ctx = SubjectCtx("subj-1", "dev", "Neo")

    private fun service(props: AppProperties): TokenService =
        TokenService(
            tokens = tokens,
            sessions = sessions,
            advocates = advocates,
            attempts = attempts,
            ops = ops,
            mail = MailService(transport, bookkeeping, props),
            bookkeeping = bookkeeping,
            users = UserService(userRepo),
            links = ProductLinks(props, 8080),
            props = props,
        )

    private fun advocate(state: AdvocateState = AdvocateState.LIVE) {
        advocates.store["subj-1"] = Advocate(subjectId = "subj-1", state = state)
    }

    /** The code exists only in the guest's email — fish it back out of the recorded body. */
    private fun lastEmailedCode(): String =
        Regex("access code: ([a-z0-9]{8})").find(transport.sent.last().third)!!.groupValues[1]

    @Test
    fun `generate emails the code and stores only the peppered hash`() {
        advocate()
        val token = service.generate(ctx, "guest@x.com", "neo@x.com").getOrNull()!!
        val code = lastEmailedCode()
        // The doc id is HMAC(pepper, code) — and nothing derivable from the code alone: a
        // different pepper produces a hash that matches no stored row (leaked-export math, §6.2).
        assertEquals(TokenService.hmac("pepper-A", code), token.tokenHash)
        assertNotEquals(code, token.tokenHash)
        assertNull(tokens.store[TokenService.hmac("pepper-B", code)])
        assertTrue(transport.sent.last().third.contains("http://dev.localhost:8080/"))
        assertEquals(TokenStatus.UNREDEEMED, tokens.store.getValue(token.tokenHash).status)
    }

    @Test
    fun `generate refuses when the advocate is not built and past the cap`() {
        assertIs<DomainError.Invalid>(service.generate(ctx, "guest@x.com", null).swap().getOrNull())
        advocate(AdvocateState.BUILDING) // ≥ BUILDING: tokens may pre-date LIVE
        repeat(props.product.tokenCap) {
            assertTrue(service.generate(ctx, "g$it@x.com", null).isRight())
        }
        assertIs<DomainError.Conflict>(service.generate(ctx, "late@x.com", null).swap().getOrNull())
    }

    @Test
    fun `a mail failure marks emailStatus FAILED but never rolls the token back`() {
        advocate()
        transport.failWith = IllegalStateException("SMTP down")
        val token = service.generate(ctx, "guest@x.com", null).getOrNull()!!
        assertEquals(TokenEmailStatus.FAILED, token.emailStatus)
        assertEquals(
            TokenEmailStatus.FAILED,
            tokens.store.getValue(token.tokenHash).emailStatus,
        )
        assertEquals(TokenStatus.UNREDEEMED, tokens.store.getValue(token.tokenHash).status)
    }

    @Test
    fun `a blank pepper fails closed`() {
        advocate()
        val bare = service(AppProperties(product = AppProperties.Product(tokenPepper = "")))
        assertIs<DomainError.Invalid>(bare.generate(ctx, "g@x.com", null).swap().getOrNull())
        assertTrue(tokens.store.isEmpty())
    }

    @Test
    fun `redeem while LIVE mints a session — the same token a second time is generic-invalid`() {
        advocate()
        service.generate(ctx, "guest@x.com", null)
        val code = lastEmailedCode()
        val outcome = service.redeem(ctx, code, "1.2.3.4")
        val session = assertIs<RedeemOutcome.Minted>(outcome).session
        assertEquals(SessionKind.GUEST, session.kind)
        assertEquals("guest@x.com", session.guestEmail)
        assertTrue(session.expiresAt!!.isAfter(Instant.now()))
        assertEquals(TokenStatus.REDEEMED, tokens.store.values.single().status)
        // One token → one session, ever.
        assertIs<RedeemOutcome.Invalid>(service.redeem(ctx, code, "1.2.3.4"))
        // Foreign-subject and revoked tokens produce the identical outcome (no oracle).
        assertIs<RedeemOutcome.Invalid>(service.redeem(ctx, "zzzzzzzz", "1.2.3.4"))
    }

    @Test
    fun `redeem while not LIVE consumes nothing — the token works once LIVE`() {
        advocate(AdvocateState.UNPROVISIONED)
        service.generate(ctx, "guest@x.com", null)
        val code = lastEmailedCode()
        assertIs<RedeemOutcome.NotLive>(service.redeem(ctx, code, "1.2.3.4"))
        assertEquals(TokenStatus.UNREDEEMED, tokens.store.values.single().status)
        advocate(AdvocateState.LIVE)
        assertIs<RedeemOutcome.Minted>(service.redeem(ctx, code, "1.2.3.4"))
    }

    @Test
    fun `the 6th attempt within a minute locks — and the lockout expires`() {
        advocate()
        repeat(5) { assertIs<RedeemOutcome.Invalid>(service.redeem(ctx, "nope$it", "9.9.9.9")) }
        assertIs<RedeemOutcome.Locked>(service.redeem(ctx, "nope5", "9.9.9.9"))
        // Still locked shortly after; a different IP is a different bucket and unaffected.
        attempts.now = attempts.now.plusSeconds(60)
        assertIs<RedeemOutcome.Locked>(service.redeem(ctx, "nope6", "9.9.9.9"))
        assertIs<RedeemOutcome.Invalid>(service.redeem(ctx, "nope7", "8.8.8.8"))
        // Past the 15-minute lockout the bucket resets.
        attempts.now = attempts.now.plusSeconds(15 * 60 + 1)
        assertIs<RedeemOutcome.Invalid>(service.redeem(ctx, "nope8", "9.9.9.9"))
    }

    @Test
    fun `redeem writes the §14_1 wall counters at each outcome`() {
        advocate()
        service.generate(ctx, "guest@x.com", null)
        val code = lastEmailedCode()
        // 6 attempts total: 1 mint + 4 invalids spend the 5/min window, the 6th is refused.
        assertIs<RedeemOutcome.Minted>(service.redeem(ctx, code, "9.9.9.9"))
        repeat(4) { assertIs<RedeemOutcome.Invalid>(service.redeem(ctx, "nope$it", "9.9.9.9")) }
        assertIs<RedeemOutcome.Locked>(service.redeem(ctx, "nope4", "9.9.9.9"))
        assertEquals(6.0, ops.counters[OpsCounters.WALL_ATTEMPTS])
        assertEquals(1.0, ops.counters[OpsCounters.WALL_LOCKOUTS])
        assertEquals(1.0, ops.counters[OpsCounters.REDEMPTIONS])
    }

    @Test
    fun `revoke is UNREDEEMED-only and subject-scoped`() {
        advocate()
        service.generate(ctx, "guest@x.com", null)
        val hash = tokens.store.keys.single()
        assertIs<DomainError.NotFound>(
            service.revoke(SubjectCtx("subj-2", "other", "Other"), hash).swap().getOrNull()
        )
        assertTrue(service.revoke(ctx, hash).isRight())
        assertEquals(TokenStatus.REVOKED, tokens.store.getValue(hash).status)
        assertIs<DomainError.Conflict>(service.revoke(ctx, hash).swap().getOrNull())
    }

    @Test
    fun `dashboard chips derive across the whole lifecycle`() {
        advocate()
        // Unredeemed.
        service.generate(ctx, "u@x.com", null)
        // Email failed.
        transport.failWith = IllegalStateException("down")
        service.generate(ctx, "f@x.com", null)
        transport.failWith = null
        // Active (redeemed, session unexpired).
        service.generate(ctx, "a@x.com", null)
        service.redeem(ctx, lastEmailedCode(), "1.1.1.1")
        // Used (redeemed, session expired).
        service.generate(ctx, "used@x.com", null)
        val usedSession =
            assertIs<RedeemOutcome.Minted>(service.redeem(ctx, lastEmailedCode(), "1.1.1.1"))
                .session
        sessions.store[usedSession.sessionId] =
            usedSession.copy(expiresAt = Instant.now().minusSeconds(60))
        // Revoked.
        service.generate(ctx, "r@x.com", null)
        service.revoke(ctx, tokens.store.values.single { it.guestEmail == "r@x.com" }.tokenHash)

        val chips = service.dashboard("subj-1").associate { it.guestEmail to it.chip }
        assertEquals(TokenChip.UNREDEEMED, chips["u@x.com"])
        assertEquals(TokenChip.EMAIL_FAILED, chips["f@x.com"])
        assertEquals(TokenChip.ACTIVE, chips["a@x.com"])
        assertEquals(TokenChip.USED, chips["used@x.com"])
        assertEquals(TokenChip.REVOKED, chips["r@x.com"])
        assertTrue(chips.size == 5)
    }

    @Test
    fun `requestProvisioning emails bound member logins once per day`() {
        userRepo.store += User(email = "neo@x.com", role = Role.SUBJECT, subjectId = "subj-1")
        userRepo.store += User(email = "op@x.com", role = Role.ADMIN)
        assertTrue(service.requestProvisioning(ctx, "guest@x.com"))
        val (to, _, body) = transport.sent.single()
        assertEquals("neo@x.com", to)
        assertTrue(body.contains("(guest@x.com)"))
        assertTrue(body.contains("/provisioning"))
        // Second guest the same day: capped.
        assertFalse(service.requestProvisioning(ctx, "another@x.com"))
        assertEquals(1, transport.sent.size)
    }
}

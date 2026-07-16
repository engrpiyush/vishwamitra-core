package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.AdvocateSession
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.AdvocateToken
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.TokenEmailStatus
import ai.vishwakarma.labelling.domain.TokenStatus
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.AdvocateSessionRepository
import ai.vishwakarma.labelling.persistence.AdvocateTokenRepository
import ai.vishwakarma.labelling.persistence.MailBookkeepingRepository
import ai.vishwakarma.labelling.persistence.WallAttemptRepository
import ai.vishwakarma.labelling.security.SubjectCtx
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Dashboard chip per LLD §6.5 — derived from token status + session expiry, never stored. */
enum class TokenChip(val label: String, val badge: String) {
    UNREDEEMED("Unredeemed", "info"),
    ACTIVE("Active", "active"),
    USED("Used", "muted"),
    REVOKED("Revoked", "revoked"),
    EMAIL_FAILED("Email failed", "failed"),
}

/** One dashboard row (S8): the raw token is long gone — the hash is only a revoke handle. */
data class TokenRow(
    val tokenHash: String,
    val guestEmail: String,
    val createdAt: Instant?,
    val chip: TokenChip,
) {
    val revocable: Boolean
        get() = chip == TokenChip.UNREDEEMED || chip == TokenChip.EMAIL_FAILED

    val createdLabel: String
        get() = createdAt?.let { CREATED_FORMAT.format(it) + " UTC" } ?: "—"

    private companion object {
        val CREATED_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
                .withZone(java.time.ZoneOffset.UTC)
    }
}

/** Outcome of a §6.3 wall redemption attempt. */
sealed interface RedeemOutcome {
    /** Rate-limited (active or freshly tripped lockout) → 429. */
    data object Locked : RedeemOutcome

    /** Advocate not LIVE — the token (if any) was deliberately not touched (§6.1). */
    data object NotLive : RedeemOutcome

    /** Unknown/foreign/used token — one generic message, no oracle. */
    data object Invalid : RedeemOutcome

    /** Token destroyed, capability session minted; set the cookie and go chat. */
    data class Minted(val session: AdvocateSession) : RedeemOutcome
}

/**
 * Guest access tokens end-to-end (VA-36, LLD §6): generation with the HMAC pepper (§6.2), the
 * rate-limited wall redemption (§6.3), UNREDEEMED-only revocation and the §6.5 dashboard
 * derivation. The raw token exists only in the generate stack frame and the guest's email — never
 * persisted, logged, or returned to the subject.
 */
@Service
class TokenService(
    private val tokens: AdvocateTokenRepository,
    private val sessions: AdvocateSessionRepository,
    private val advocates: AdvocateRepository,
    private val attempts: WallAttemptRepository,
    private val mail: MailService,
    private val bookkeeping: MailBookkeepingRepository,
    private val users: UserService,
    private val links: ProductLinks,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /**
     * §6.2 generate: cap-checked, peppered, emailed straight to the guest. The returned row is the
     * dashboard's view — the caller never sees the code.
     */
    fun generate(
        ctx: SubjectCtx,
        guestEmail: String,
        actor: String?
    ): Either<DomainError, AdvocateToken> {
        val email = guestEmail.trim().lowercase()
        if (!EMAIL_REGEX.matches(email)) {
            return DomainError.Invalid("A valid guest email is required").left()
        }
        val state = advocates.find(ctx.subjectId)?.state ?: AdvocateState.NOT_BUILT
        if (state == AdvocateState.NOT_BUILT) {
            // §6.2: tokens may pre-date LIVE, but not the advocate itself.
            return DomainError.Invalid(
                    "${ctx.displayName}'s advocate hasn't been built yet — codes come later"
                )
                .left()
        }
        val unredeemed =
            tokens.listBySubject(ctx.subjectId).count { it.status == TokenStatus.UNREDEEMED }
        if (unredeemed >= props.product.tokenCap) {
            return DomainError.Conflict("Revoke an unused code to free a slot").left()
        }
        val pepper = pepper().getOrNull() ?: return pepperMissing().left()
        val raw = randomToken()
        val token =
            AdvocateToken(
                tokenHash = hmac(pepper, raw),
                subjectId = ctx.subjectId,
                guestEmail = email,
                status = TokenStatus.UNREDEEMED,
                createdBy = actor,
                createdAt = Instant.now(),
                emailStatus = TokenEmailStatus.SENT,
            )
        tokens.save(token)
        val result =
            mail.send(
                MailTemplate.GUEST_TOKEN,
                email,
                mapOf(
                    "advocate" to ctx.displayName,
                    "code" to raw,
                    "link" to links.subjectUrl(ctx.handle),
                ),
            )
        return if (result == MailResult.SENT) {
            token.right()
        } else {
            // §11.2: the send failed but the token stands — the dashboard renders the mark.
            tokens.markEmailFailed(token.tokenHash)
            token.copy(emailStatus = TokenEmailStatus.FAILED).right()
        }
    }

    /** §6.5 revoke — UNREDEEMED only; a used or already-revoked row is a conflict. */
    fun revoke(ctx: SubjectCtx, tokenHash: String): Either<DomainError, Unit> {
        val token =
            tokens.find(tokenHash)?.takeIf { it.subjectId == ctx.subjectId }
                ?: return DomainError.NotFound("No such access code").left()
        if (token.status != TokenStatus.UNREDEEMED) {
            return DomainError.Conflict("Only unused codes can be revoked").left()
        }
        return if (tokens.revokeIfUnredeemed(tokenHash)) {
            Unit.right()
        } else {
            DomainError.Conflict("Only unused codes can be revoked").left()
        }
    }

    /** §6.5 dashboard rows, newest first, chips derived against the sessions' expiry. */
    fun dashboard(subjectId: String, now: Instant = Instant.now()): List<TokenRow> {
        val bySession = sessions.listBySubject(subjectId).associateBy { it.sessionId }
        return tokens.listBySubject(subjectId).map { token ->
            TokenRow(
                tokenHash = token.tokenHash,
                guestEmail = token.guestEmail,
                createdAt = token.createdAt,
                chip = chipFor(token, token.sessionId?.let(bySession::get), now),
            )
        }
    }

    fun capReached(subjectId: String): Boolean =
        tokens.listBySubject(subjectId).count { it.status == TokenStatus.UNREDEEMED } >=
            props.product.tokenCap

    /**
     * §6.3 wall redemption: transactional rate limit → LIVE gate (token untouched otherwise) →
     * transactional redeem+mint. The IP is bucketed, never stored raw beyond the hash input.
     */
    fun redeem(ctx: SubjectCtx, rawToken: String, ip: String): RedeemOutcome {
        val allowed =
            attempts.tryAttempt(
                bucketKey(ctx.subjectId, ip),
                props.product.wallAttemptsPerMinute,
                props.product.wallLockout,
            )
        if (!allowed) return RedeemOutcome.Locked
        val state = advocates.find(ctx.subjectId)?.state ?: AdvocateState.NOT_BUILT
        if (state != AdvocateState.LIVE) return RedeemOutcome.NotLive
        val pepper = pepper().getOrNull() ?: return RedeemOutcome.Invalid
        val normalized = rawToken.trim().lowercase()
        if (normalized.isBlank()) return RedeemOutcome.Invalid
        val session =
            tokens.redeem(
                tokenHash = hmac(pepper, normalized),
                subjectId = ctx.subjectId,
                sessionId = newSessionId(),
                ttl = props.product.guestSessionTtl,
            ) ?: return RedeemOutcome.Invalid
        return RedeemOutcome.Minted(session)
    }

    /**
     * The request-provisioning CTA (§8.3): tell the subject's member logins someone wants their
     * advocate on — at most once per subject per day. True = an email actually went out now.
     */
    fun requestProvisioning(ctx: SubjectCtx, guestEmail: String?): Boolean {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        if (bookkeeping.provisioningRequestSent(today, ctx.subjectId)) return false
        val recipients =
            users.list().filter {
                it.role == Role.SUBJECT && it.active && it.subjectId == ctx.subjectId
            }
        if (recipients.isEmpty()) return false
        val model = buildMap {
            put("advocate", ctx.displayName)
            put("link", links.subjectUrl(ctx.handle, "/provisioning"))
            guestEmail
                ?.trim()
                ?.lowercase()
                ?.takeIf { EMAIL_REGEX.matches(it) }
                ?.let { put("guest", it) }
        }
        val delivered =
            recipients.any {
                mail.send(MailTemplate.PROVISIONING_REQUEST, it.email, model) == MailResult.SENT
            }
        if (delivered) bookkeeping.markProvisioningRequest(today, ctx.subjectId)
        return delivered
    }

    /** Advocate state collapsed for the guest panel (§8.2/§8.3 friendly states). */
    fun advocateState(subjectId: String): AdvocateState =
        advocates.find(subjectId)?.state ?: AdvocateState.NOT_BUILT

    private fun chipFor(token: AdvocateToken, session: AdvocateSession?, now: Instant): TokenChip =
        when (token.status) {
            TokenStatus.REVOKED -> TokenChip.REVOKED
            TokenStatus.REDEEMED ->
                if (session?.expiresAt?.isAfter(now) == true) TokenChip.ACTIVE else TokenChip.USED
            TokenStatus.UNREDEEMED ->
                if (token.emailStatus == TokenEmailStatus.FAILED) TokenChip.EMAIL_FAILED
                else TokenChip.UNREDEEMED
        }

    private fun randomToken(): String =
        buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
        }

    /** 128-bit URL-safe session id — the `adv_session` cookie value (§6.3). */
    private fun newSessionId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pepper(): Result<String> =
        props.product.tokenPepper.takeIf { it.isNotBlank() }?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("ADVOCATE_TOKEN_PEPPER is not configured"))

    private fun pepperMissing(): DomainError {
        log.error("ADVOCATE_TOKEN_PEPPER missing — guest tokens are disabled (fail closed)")
        return DomainError.Invalid("Guest access is not configured on this environment yet")
    }

    companion object {
        /** §6.2: 8 chars from [a-z0-9] — 36^8 ≈ 2.8e12; the wall makes online guessing moot. */
        private const val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val TOKEN_LENGTH = 8
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /** `sha256(subjectId + ":" + ip)[:16]` (§6.3) — the wall_attempts doc id. */
        fun bucketKey(subjectId: String, ip: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$subjectId:$ip".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)

        /** Hex HMAC-SHA256 — the `advocate_tokens` doc id (§6.2). */
        fun hmac(pepper: String, token: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(token.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
        }
    }
}

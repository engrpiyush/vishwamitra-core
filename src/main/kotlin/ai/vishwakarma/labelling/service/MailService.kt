package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.persistence.MailBookkeepingRepository
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The six product templates (VA-41, LLD §11.3): plain-text-first bodies, one CTA link each, no
 * tracking pixels. `model` keys are template-specific; `link` is the CTA in every one.
 */
enum class MailTemplate(val id: String) {
    GUEST_TOKEN("guest-token"),
    ADVOCATE_LIVE("advocate-live"),
    CLAIMS_READY("claims-ready"),
    SPEAKER_HELP("speaker-help"),
    PROVISIONING_REQUEST("provisioning-request"),
    QUESTIONS_NUDGE("questions-nudge");

    fun subjectLine(model: Map<String, String>): String {
        val advocate = model["advocate"] ?: "your advocate"
        return when (this) {
            GUEST_TOKEN -> "Your access code for $advocate's advocate"
            ADVOCATE_LIVE -> "$advocate's advocate is live"
            CLAIMS_READY -> "Your claims are ready to review"
            SPEAKER_HELP -> "Help us identify who's speaking"
            PROVISIONING_REQUEST -> "Someone wants to talk to your advocate"
            QUESTIONS_NUDGE -> "Your advocate has questions for you"
        }
    }

    fun body(model: Map<String, String>): String {
        val advocate = model["advocate"] ?: "your advocate"
        val link = model["link"] ?: ""
        return when (this) {
                GUEST_TOKEN ->
                    """
                You've been invited to talk to $advocate's personal advocate — an AI that speaks
                for them, grounded in verified evidence. You'll be chatting with an AI, not with
                $advocate directly, and the conversation may be reviewed by them.

                Your access code: ${model["code"] ?: ""}

                Enter it here: $link
                """
                ADVOCATE_LIVE ->
                    """
                Your advocate is live and answering until ${model["windowEnd"] ?: "the window ends"}.

                Talk to it, or share guest access codes, here: $link
                """
                CLAIMS_READY ->
                    """
                We've finished processing your latest uploads — the claims we extracted are ready
                for you to review.

                Review them here: $link
                """
                SPEAKER_HELP ->
                    """
                One of your recordings has several voices and we couldn't confidently tell which
                one is you. It takes a minute to tag yourself, then processing continues.

                Tag the speakers here: $link
                """
                PROVISIONING_REQUEST ->
                    """
                Someone${model["guest"]?.let { " ($it)" } ?: ""} tried to talk to your advocate, but it isn't online right now.

                Start a serving window here: $link
                """
                QUESTIONS_NUDGE ->
                    """
                Your advocate has ${model["count"] ?: "some"} open question(s) for you — answering
                them fills gaps and strengthens your authenticity score.

                Answer them here: $link
                """
            }
            .trimIndent()
            .trim() + FOOTER
    }

    companion object {
        // Mailbox-neutral: the sender may be a watched inbox (info@) now and no-reply@ at
        // go-live — the footer must stay true for both.
        private const val FOOTER = "\n\n—\nvishwakarma.ai"
    }
}

/** Outcome of a send — callers translate FAILED into their per-feature mark (LLD §11.2). */
enum class MailResult {
    SENT,
    FAILED,
    SKIPPED_CAP,
}

/**
 * The single mail funnel (VA-41, LLD §11): every product send goes through [send], which enforces
 * the app-level daily cap and the never-block failure semantics — a failure logs WARN and returns
 * [MailResult.FAILED] for the caller to mark; nothing here ever throws into a calling flow. No
 * outbox, no retry queue: the notify poker's periodicity IS the retry.
 */
@Service
class MailService(
    private val transport: MailTransport,
    private val bookkeeping: MailBookkeepingRepository,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun send(template: MailTemplate, to: String, model: Map<String, String>): MailResult {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        return try {
            if (!bookkeeping.countSendIfBelow(today, props.product.mailDailyCap)) {
                log.warn(
                    "Mail daily cap ({}) hit — skipping {} to {}",
                    props.product.mailDailyCap,
                    template.id,
                    to,
                )
                return MailResult.SKIPPED_CAP
            }
            transport.send(
                from = props.product.mailFrom,
                to = to,
                subject = template.subjectLine(model),
                body = template.body(model),
            )
            MailResult.SENT
        } catch (e: Exception) {
            log.warn("Mail send failed ({} to {}): {}", template.id, to, e.message)
            MailResult.FAILED
        }
    }
}

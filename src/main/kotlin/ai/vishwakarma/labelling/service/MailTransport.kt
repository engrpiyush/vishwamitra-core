package ai.vishwakarma.labelling.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * The wire under [MailService] (VA-41, LLD §11.1). Two implementations by profile: real SMTP
 * outside dev, a logger no-op in dev — so a local run can never send mail no matter how the
 * spring.mail block is configured.
 */
interface MailTransport {
    /** Deliver one plain-text message; throws on transport failure (MailService catches). */
    fun send(from: String, to: String, subject: String, body: String)
}

/** Dev: log-only transport — the message is visible in the console, nothing leaves the box. */
@Component
@Profile("dev")
class LogMailTransport : MailTransport {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(from: String, to: String, subject: String, body: String) {
        log.info("[dev mail] to={} subject={}\n{}", to, subject, body)
    }
}

/**
 * Prod: Gmail SMTP via Spring Mail. The sender bean exists only when `spring.mail.host` is set — a
 * missing/misconfigured block surfaces as a loud send failure (marked per feature), never a boot
 * failure.
 */
@Component
@Profile("!dev")
class SmtpMailTransport(
    private val sender: ObjectProvider<JavaMailSender>,
) : MailTransport {

    override fun send(from: String, to: String, subject: String, body: String) {
        val mailSender =
            sender.ifAvailable ?: error("SMTP transport not configured (spring.mail.host)")
        val message =
            SimpleMailMessage().apply {
                this.from = from
                setTo(to)
                this.subject = subject
                this.text = body
            }
        mailSender.send(message)
    }
}

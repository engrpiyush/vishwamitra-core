package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.persistence.SubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Captures coming-soon page sign-ups (source [SOURCE]). Validates and normalizes the email, then
 * stores it idempotently — a repeat sign-up is a no-op rather than an error.
 */
@Service
class SubscriptionService(private val subscriptions: SubscriptionRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws IllegalArgumentException if the email is malformed (mapped to 400 by the controller).
     */
    fun subscribe(rawEmail: String) {
        val email = rawEmail.trim().lowercase()
        require(EMAIL_REGEX.matches(email)) { "invalid email" }
        log.info("Coming-soon subscribe | email={}", email)
        if (subscriptions.exists(email)) {
            log.debug("Coming-soon subscription already exists | email={}", email)
            return
        }
        subscriptions.save(email, SOURCE)
    }

    companion object {
        private const val SOURCE = "CS_WEB"
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}

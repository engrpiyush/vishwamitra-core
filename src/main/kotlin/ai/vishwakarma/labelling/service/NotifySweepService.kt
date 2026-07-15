package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.QuestionStatus
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.persistence.MailBookkeepingRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.SubjectQuestionRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * The notify poker (VA-41, LLD §3.4/§11.3): Cloud Scheduler POSTs `/internal/notify/sweep` every 30
 * min; each sweep sends AT MOST ONE digest per subject per day (the `mail_digests` mark), and
 * repeat sweeps are state-guarded no-ops. Signal priority: speaker-help (a parked job blocks the
 * pipeline) → claims-ready (a fresh Stage 2 completion) → questions-nudge (OPEN F11 questions).
 * Consumers with their own hooks (guest-token, advocate-live, provisioning-request) send directly
 * through MailService from their own tickets.
 */
@Service
class NotifySweepService(
    private val subjects: SubjectRepository,
    private val users: UserService,
    private val stage2Jobs: Stage2JobRepository,
    private val questions: SubjectQuestionRepository,
    private val mail: MailService,
    private val bookkeeping: MailBookkeepingRepository,
    private val props: AppProperties,
    /** Dev CTA links carry the actual port (PORT env → server.port); prod URLs have none. */
    @Value("\${server.port:8080}") private val serverPort: Int = 8080,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Returns counters for the endpoint's JSON body (visibility, not contract). */
    fun sweep(): Map<String, Int> {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val memberLogins =
            users.list().filter { it.role == Role.SUBJECT && it.active && it.subjectId != null }
        var sent = 0
        var skipped = 0
        var failed = 0
        for (subject in subjects.findAll()) {
            if (subject.status != SubjectStatus.ACTIVE || subject.handle.isNullOrBlank()) continue
            val recipients = memberLogins.filter { it.subjectId == subject.id }
            if (recipients.isEmpty()) continue
            if (bookkeeping.digestSent(today, subject.id)) {
                skipped++
                continue
            }
            val digest = digestFor(subject.id, subject.handle!!, subject.displayName) ?: continue
            val (template, model) = digest
            var delivered = false
            recipients.forEach { user ->
                when (mail.send(template, user.email, model)) {
                    MailResult.SENT -> delivered = true
                    MailResult.FAILED -> failed++
                    MailResult.SKIPPED_CAP -> skipped++
                }
            }
            if (delivered) {
                // Marked only after a successful delivery — a failed day retries next sweep
                // (the poker's periodicity IS the retry, §11.2).
                bookkeeping.markDigest(today, subject.id, template.id)
                sent++
            }
        }
        val result = mapOf("digestsSent" to sent, "skipped" to skipped, "sendFailures" to failed)
        log.info("Notify sweep: {}", result)
        return result
    }

    private fun digestFor(
        subjectId: String,
        handle: String,
        displayName: String,
    ): Pair<MailTemplate, Map<String, String>>? {
        val jobs = stage2Jobs.findBySubject(subjectId)
        val parked = jobs.any { it.status == Stage2JobStatus.AWAITING_SPEAKER_SELECTION }
        if (parked) {
            return MailTemplate.SPEAKER_HELP to model(displayName, subjectUrl(handle, "/training"))
        }
        val terminal = setOf(Stage2JobStatus.COMPLETED, Stage2JobStatus.FAILED)
        val cutoff = Instant.now().minus(FRESHNESS)
        val claimsReady =
            jobs.isNotEmpty() &&
                jobs.all { it.status in terminal } &&
                jobs.any {
                    it.status == Stage2JobStatus.COMPLETED &&
                        (it.finishedAt ?: Instant.EPOCH) > cutoff
                }
        if (claimsReady) {
            return MailTemplate.CLAIMS_READY to model(displayName, subjectUrl(handle, "/training"))
        }
        val open = questions.listBySubject(subjectId, QuestionStatus.OPEN)
        if (open.isNotEmpty()) {
            return MailTemplate.QUESTIONS_NUDGE to
                model(displayName, subjectUrl(handle, "/questions")) +
                    mapOf("count" to open.size.toString())
        }
        return null
    }

    private fun model(displayName: String, link: String): Map<String, String> =
        mapOf("advocate" to displayName, "link" to link)

    /** CTA links point at the subject's own host (VA-30 routing). */
    private fun subjectUrl(handle: String, path: String): String {
        val base = props.product.baseDomain
        return if (base == "localhost") {
            "http://$handle.localhost:$serverPort$path"
        } else {
            "https://$handle.$base$path"
        }
    }

    companion object {
        /**
         * Claims-ready fires only for completions fresher than this — the daily digest mark dedupes
         * within a day; this stops an old all-terminal state from nagging forever.
         */
        private val FRESHNESS: Duration = Duration.ofHours(24)
    }
}

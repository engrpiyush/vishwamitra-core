package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.QuestionStatus
import ai.vishwakarma.labelling.domain.QuestionTrigger
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.Stage2Job
import ai.vishwakarma.labelling.domain.Stage2JobStatus
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectQuestion
import ai.vishwakarma.labelling.domain.User
import ai.vishwakarma.labelling.persistence.MailBookkeepingRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.SubjectQuestionRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.UserRepository
import com.google.cloud.firestore.Firestore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class SweepSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<Subject>()

    override fun findAll(): List<Subject> = store
}

private class SweepUserRepo : UserRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<User>()

    override fun findAll(): List<User> = store
}

private class SweepJobRepo : Stage2JobRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<Stage2Job>()

    override fun findBySubject(subjectId: String): List<Stage2Job> =
        store.filter { it.subjectId == subjectId }
}

private class SweepQuestionRepo : SubjectQuestionRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<SubjectQuestion>()

    override fun listBySubject(subjectId: String, status: QuestionStatus?): List<SubjectQuestion> =
        store.filter { it.subjectId == subjectId && (status == null || it.status == status) }
}

private class SweepBookkeeping : MailBookkeepingRepository(mock(Firestore::class.java)) {
    val digests = mutableSetOf<String>()

    override fun countSendIfBelow(date: String, cap: Int): Boolean = true

    override fun digestSent(date: String, subjectId: String): Boolean =
        digests.any { it.endsWith("_$subjectId") }

    override fun markDigest(date: String, subjectId: String, template: String) {
        digests += "${date}_$subjectId"
    }
}

private class SweepTransport : MailTransport {
    val sent = mutableListOf<Triple<String, String, String>>()

    override fun send(from: String, to: String, subject: String, body: String) {
        sent += Triple(to, subject, body)
    }
}

class NotifySweepServiceTest {

    private val subjects = SweepSubjectRepo()
    private val users = SweepUserRepo()
    private val jobs = SweepJobRepo()
    private val questions = SweepQuestionRepo()
    private val bookkeeping = SweepBookkeeping()
    private val transport = SweepTransport()
    private val props =
        AppProperties(
            product = AppProperties.Product(baseDomain = "localhost", operatorDomain = "localhost")
        )
    private val service =
        NotifySweepService(
            subjects = subjects,
            users = UserService(users),
            stage2Jobs = jobs,
            questions = questions,
            mail = MailService(transport, bookkeeping, props),
            bookkeeping = bookkeeping,
            props = props,
            serverPort = 8090,
        )

    private fun subject(id: String = "subj-1", handle: String? = "dev") {
        subjects.store += Subject(id = id, displayName = "Neo", handle = handle)
    }

    private fun login(subjectId: String = "subj-1", email: String = "neo@x.com") {
        users.store += User(email = email, role = Role.SUBJECT, subjectId = subjectId)
    }

    private fun job(status: Stage2JobStatus, finishedAt: Instant? = null) {
        jobs.store +=
            Stage2Job(
                id = "job-${jobs.store.size}",
                subjectId = "subj-1",
                assetId = "asset-1",
                modality = AssetModality.AUDIO,
                status = status,
                finishedAt = finishedAt,
            )
    }

    @Test
    fun `no signals — nothing sent`() {
        subject()
        login()
        assertEquals(0, service.sweep()["digestsSent"])
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `parked job wins the priority and sends speaker-help once per day`() {
        subject()
        login()
        job(Stage2JobStatus.AWAITING_SPEAKER_SELECTION)
        questions.store +=
            SubjectQuestion(
                id = "q1",
                subjectId = "subj-1",
                trigger = QuestionTrigger.UNFAVORABLE,
                questionText = "?",
            )
        assertEquals(1, service.sweep()["digestsSent"])
        assertTrue(transport.sent.single().second.contains("identify who's speaking"))
        // Dev CTA links carry the injected server port (the app may not run on 8080).
        assertTrue(transport.sent.single().third.contains("http://dev.localhost:8090/training"))
        // Second sweep the same day: state-guarded no-op.
        assertEquals(0, service.sweep()["digestsSent"])
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun `fresh all-terminal completion sends claims-ready — stale one does not`() {
        subject()
        login()
        job(Stage2JobStatus.COMPLETED, finishedAt = Instant.now().minusSeconds(3600))
        job(Stage2JobStatus.FAILED)
        assertEquals(1, service.sweep()["digestsSent"])
        assertTrue(transport.sent.single().second.contains("ready"))

        // A subject whose completion is ancient never gets nagged again.
        subjects.store.clear()
        jobs.store.clear()
        transport.sent.clear()
        bookkeeping.digests.clear()
        subject(id = "subj-1")
        jobs.store +=
            Stage2Job(
                id = "old",
                subjectId = "subj-1",
                assetId = "a",
                modality = AssetModality.AUDIO,
                status = Stage2JobStatus.COMPLETED,
                finishedAt = Instant.now().minusSeconds(60 * 60 * 48),
            )
        assertEquals(0, service.sweep()["digestsSent"])
    }

    @Test
    fun `open questions nudge with the count`() {
        subject()
        login()
        questions.store +=
            SubjectQuestion(
                id = "q1",
                subjectId = "subj-1",
                trigger = QuestionTrigger.CONTRADICTION,
                questionText = "?",
            )
        assertEquals(1, service.sweep()["digestsSent"])
        assertTrue(transport.sent.single().second.contains("questions"))
    }

    @Test
    fun `subjects without member logins or handles are skipped`() {
        subject(id = "no-login", handle = "haslogin-not")
        subject(id = "no-handle", handle = null)
        login(subjectId = "no-handle")
        job(Stage2JobStatus.AWAITING_SPEAKER_SELECTION)
        assertEquals(0, service.sweep()["digestsSent"])
    }
}

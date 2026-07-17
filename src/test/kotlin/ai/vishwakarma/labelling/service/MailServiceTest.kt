package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.persistence.MailBookkeepingRepository
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class RecordingTransport : MailTransport {
    val sent = mutableListOf<Triple<String, String, String>>()
    var failWith: Exception? = null

    override fun send(from: String, to: String, subject: String, body: String) {
        failWith?.let { throw it }
        sent += Triple(to, subject, body)
    }
}

private class FakeBookkeeping : MailBookkeepingRepository(mock(Firestore::class.java)) {
    val counts = mutableMapOf<String, Int>()
    val digests = mutableSetOf<String>()
    var failed = 0
    var skipped = 0

    override fun countSendIfBelow(date: String, cap: Int): Boolean {
        val current = counts.getOrDefault(date, 0)
        if (current >= cap) return false
        counts[date] = current + 1
        return true
    }

    override fun markSendFailed(date: String) {
        failed++
    }

    override fun markSendSkipped(date: String) {
        skipped++
    }

    override fun digestSent(date: String, subjectId: String): Boolean =
        "${date}_$subjectId" in digests

    override fun markDigest(date: String, subjectId: String, template: String) {
        digests += "${date}_$subjectId"
    }
}

class MailServiceTest {

    private val transport = RecordingTransport()
    private val bookkeeping = FakeBookkeeping()
    private val props = AppProperties(product = AppProperties.Product(mailDailyCap = 2))
    private val service = MailService(transport, bookkeeping, props)

    private val model = mapOf("advocate" to "Neo", "link" to "https://neo.x/wall", "code" to "c-1")

    @Test
    fun `send delivers a plain-text body with the CTA link`() {
        assertEquals(MailResult.SENT, service.send(MailTemplate.GUEST_TOKEN, "g@x.com", model))
        val (to, subject, body) = transport.sent.single()
        assertEquals("g@x.com", to)
        assertTrue(subject.contains("Neo"))
        assertTrue(body.contains("https://neo.x/wall"))
        assertTrue(body.contains("c-1"))
    }

    @Test
    fun `the daily cap trips loudly and skips`() {
        assertEquals(MailResult.SENT, service.send(MailTemplate.CLAIMS_READY, "a@x.com", model))
        assertEquals(MailResult.SENT, service.send(MailTemplate.CLAIMS_READY, "b@x.com", model))
        assertEquals(
            MailResult.SKIPPED_CAP,
            service.send(MailTemplate.CLAIMS_READY, "c@x.com", model),
        )
        assertEquals(2, transport.sent.size)
        // VA-68 (§14.1): the cap refusal is counted on the day's row.
        assertEquals(1, bookkeeping.skipped)
    }

    @Test
    fun `a transport failure never throws — it returns FAILED for the caller to mark`() {
        transport.failWith = IllegalStateException("SMTP down")
        assertEquals(MailResult.FAILED, service.send(MailTemplate.SPEAKER_HELP, "s@x.com", model))
        // VA-68 (§14.1): the failure is counted on the day's row.
        assertEquals(1, bookkeeping.failed)
    }

    @Test
    fun `every template renders a subject line and body`() {
        for (template in MailTemplate.entries) {
            assertTrue(template.subjectLine(model).isNotBlank(), template.id)
            assertTrue(template.body(model).contains("https://neo.x/wall"), template.id)
        }
    }
}

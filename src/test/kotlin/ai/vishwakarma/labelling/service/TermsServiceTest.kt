package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.PolicyAcceptance
import ai.vishwakarma.labelling.domain.TermsAcceptance
import ai.vishwakarma.labelling.persistence.TermsAcceptanceRepository
import com.google.cloud.firestore.Firestore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakeTermsRepo : TermsAcceptanceRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, TermsAcceptance>()

    override fun find(email: String): TermsAcceptance? = store[email]

    override fun save(record: TermsAcceptance) {
        store[record.email] = record
    }
}

class TermsServiceTest {

    private val repo = FakeTermsRepo()
    private val service = TermsService(repo)

    @Test
    fun `unknown email has no acceptances`() {
        assertFalse(service.hasCurrentAcceptances("s@x.com"))
    }

    @Test
    fun `accept covers all three current policies`() {
        service.accept("s@x.com")
        assertTrue(service.hasCurrentAcceptances("s@x.com"))
        val record = repo.store["s@x.com"]!!
        assertEquals(TermsService.CURRENT_VERSIONS.keys, record.acceptances.keys)
    }

    @Test
    fun `a superseded policy version re-gates until re-accepted`() {
        val old = Instant.parse("2026-01-01T00:00:00Z")
        repo.store["s@x.com"] =
            TermsAcceptance(
                email = "s@x.com",
                acceptances =
                    TermsService.CURRENT_VERSIONS.mapValues { (policy, version) ->
                        if (policy == TermsService.POLICY_PRIVACY) PolicyAcceptance("v0", old)
                        else PolicyAcceptance(version, old)
                    },
            )
        assertFalse(service.hasCurrentAcceptances("s@x.com"))
        service.accept("s@x.com")
        assertTrue(service.hasCurrentAcceptances("s@x.com"))
        val record = repo.store["s@x.com"]!!
        // Already-current stamps keep their original (earliest) acceptance time.
        assertEquals(old, record.acceptances[TermsService.POLICY_TNC]!!.at)
        // The superseded one is freshly re-stamped at the current version.
        assertEquals(
            TermsService.CURRENT_VERSIONS[TermsService.POLICY_PRIVACY],
            record.acceptances[TermsService.POLICY_PRIVACY]!!.version,
        )
        assertTrue(record.acceptances[TermsService.POLICY_PRIVACY]!!.at > old)
    }
}

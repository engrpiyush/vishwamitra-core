package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import com.google.cloud.firestore.Firestore
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class ScoreClaimRepo : ClaimRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<Claim>()

    override fun findBySubject(subjectId: String): List<Claim> =
        store.filter { it.subjectId == subjectId }
}

private class ScoreAdvocateRepo : AdvocateRepository(mock(Firestore::class.java)) {
    val writes = mutableListOf<Triple<String, Double?, Int>>()

    override fun updateScore(
        subjectId: String,
        score: Double?,
        scoredClaimCount: Int,
        computedAt: Instant,
    ) {
        writes += Triple(subjectId, score, scoredClaimCount)
    }
}

/** The §10 evidence-weighted mean (VA-44), pinned to the LLD §10.2 worked example. */
class AggregateScoreServiceTest {

    private val claims = ScoreClaimRepo()
    private val advocates = ScoreAdvocateRepo()
    private val service = AggregateScoreService(claims, advocates)

    private var seq = 0

    private fun claim(score: Double?, mass: Double?, subjectId: String = "s1"): Claim =
        Claim(
            id = "c${++seq}",
            subjectId = subjectId,
            assetId = "a1",
            claimType = ClaimType.EPISODE,
            text = "claim $seq",
            authenticityScore = score,
            authenticitySignals = mass?.let { mapOf("evidenceMass" to it, "support" to 1.0) },
        )

    @Test
    fun `the LLD worked example lands on 86 over 100`() {
        claims.store +=
            listOf(
                claim(0.88, 3.1), // led the payments migration
                claim(0.97, 2.2), // B.E. in CS
                claim(0.93, 1.8), // unfavorable sem-3 grade — contributes positively (D4)
                claim(0.41, 0.6), // self-reported Kubernetes
                claim(0.72, 1.1), // mentored juniors
            )
        val result = service.recompute("s1")
        val score = assertNotNull(result.score)
        assertTrue(abs(score - 7.574 / 8.8) < 1e-9, "expected the exact weighted mean, got $score")
        assertEquals(86, result.display)
        assertEquals(5, result.scoredClaimCount)
        assertEquals(1, advocates.writes.size)
        assertEquals(Triple("s1", score as Double?, 5), advocates.writes.single())
    }

    @Test
    fun `mass-zero and unpublished claims contribute nothing`() {
        claims.store +=
            listOf(
                claim(0.88, 3.1),
                claim(0.99, 0.0), // published but massless — no weight, not counted
                claim(0.99, null), // published, no signal vector — treated as massless
                claim(null, null), // not published — never counted
                claim(0.9, 5.0, subjectId = "someone-else"), // other subject
            )
        val result = service.recompute("s1")
        assertEquals(88, result.display)
        assertEquals(1, result.scoredClaimCount)
    }

    @Test
    fun `no published scores means no score — the still-being-scored state`() {
        claims.store += claim(null, null)
        val result = service.recompute("s1")
        assertNull(result.score)
        assertNull(result.display)
        assertEquals(0, result.scoredClaimCount)
        // The write still lands: a re-publish that removed all mass clears a stale number.
        assertEquals(1, advocates.writes.size)
        assertEquals(Triple("s1", null as Double?, 0), advocates.writes.single())
    }

    @Test
    fun `recompute is idempotent`() {
        claims.store += listOf(claim(0.88, 3.1), claim(0.72, 1.1))
        val first = service.recompute("s1")
        val second = service.recompute("s1")
        assertEquals(first, second)
        assertEquals(2, advocates.writes.size)
        assertEquals(advocates.writes[0], advocates.writes[1])
    }
}

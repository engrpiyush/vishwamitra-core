package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** The recomputed aggregate — what got denormalized onto `advocates/{subjectId}`. */
data class AggregateScore(val score: Double?, val scoredClaimCount: Int) {
    /** §10.1 presentation: round(100·A). */
    val display: Int?
        get() = score?.let { Math.round(it * 100).toInt() }
}

/**
 * The ONE consolidated number subjects see (VA-44, product LLD §10 — D4):
 *
 * `A(s) = Σ(scoreᵢ·massᵢ) / Σ(massᵢ)` over the subject's **published** claim scores —
 * favorability-agnostic by decision, it measures how well the picture is corroborated, not how
 * flattering it is. Published means the score block is on the ledger (`authenticityScore` is only
 * ever written by the Stage 3 publish path; provisional scores live graph-side and never count).
 * Mass-0 claims contribute nothing to either sum; no contributing claims ⇒ no score ("still being
 * scored"). One query, one field-merge write — idempotent and cheap. Triggered from the Stage 3
 * ledger publish and the operator Advocates panel.
 */
@Service
class AggregateScoreService(
    private val claims: ClaimRepository,
    private val advocates: AdvocateRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun recompute(subjectId: String): AggregateScore {
        val weighted =
            claims.findBySubject(subjectId).mapNotNull { claim ->
                val score = claim.authenticityScore ?: return@mapNotNull null
                val mass = claim.authenticitySignals?.get(EVIDENCE_MASS) ?: 0.0
                if (mass > 0.0) score * mass to mass else null
            }
        val totalMass = weighted.sumOf { it.second }
        val aggregate =
            if (totalMass > 0.0)
                AggregateScore(weighted.sumOf { it.first } / totalMass, weighted.size)
            else AggregateScore(null, 0)
        advocates.updateScore(subjectId, aggregate.score, aggregate.scoredClaimCount, Instant.now())
        log.info(
            "Aggregate score for {}: {} over {} published claim(s)",
            subjectId,
            aggregate.display ?: "—",
            aggregate.scoredClaimCount,
        )
        return aggregate
    }

    private companion object {
        /** The Stage 3 §3.2 signal key inside `authenticitySignals`. */
        const val EVIDENCE_MASS = "evidenceMass"
    }
}

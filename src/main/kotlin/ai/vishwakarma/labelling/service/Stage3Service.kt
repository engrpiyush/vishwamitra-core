package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Stage3Counters
import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.stage3.EmbeddedClaim
import ai.vishwakarma.labelling.stage3.EmbeddingService
import ai.vishwakarma.labelling.stage3.EmbeddingTaskType
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import ai.vishwakarma.labelling.stage3.buildEvidenceProjection
import ai.vishwakarma.labelling.stage3.embeddingText
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Stage 3 (reviewed claims → authenticity graph/scores) — the run lifecycle chassis (LLD §9.6–9.7)
 * plus the phases built so far: SYNC (§11.2) and EMBED (§11.4). RESOLVE_ENTITIES (VA-11), MATCH
 * (VA-14), JUDGE (VA-15), ASSEMBLE (VA-16), SCORE (VA-17) and the publish gate (VA-18) plug into
 * the [poll] dispatch as they land; until then their slots log and advance so the state machine is
 * walkable end-to-end (the VA-9 contract) without pretending any judgment happened — counters stay
 * empty and publish does not exist yet, so nothing can reach the ledger.
 *
 * Conventions carried over from Stage 2: no scheduler — the run advances only inside poll requests,
 * one bounded step each; failures are terminal FAILED states carrying the verbatim provider error
 * (Retry resumes — phases are re-entrant by construction); a phase that stops making progress past
 * `app.stage3.phase-timeout` is reclaimed to FAILED on the next poll (§12.7 idiom, generalized via
 * [Stage3Run.phaseSince]).
 */
@Service
class Stage3Service(
    private val runs: Stage3RunRepository,
    private val manifests: IntakeManifestRepository,
    private val subjects: SubjectRepository,
    private val assets: AssetRepository,
    private val stage2Jobs: Stage2JobRepository,
    private val claimReviews: ClaimReviewRepository,
    private val reviewService: ClaimReviewService,
    private val graph: Stage3GraphRepository,
    private val embeddings: EmbeddingService,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(Stage3Service::class.java)

    // ---- submit -----------------------------------------------------------------

    /**
     * Start a run. Guards (LLD §10): the subject's claim review must be **submitted** (the §12.6
     * gate — Stage 3 never consumes unreviewed claims), no active run may exist, and Neo4j must be
     * reachable (fail fast here, not three phases in). Params are snapshotted at this instant.
     */
    fun submit(subjectId: String, actor: String?): Either<DomainError, Stage3Run> {
        val manifest =
            manifests.findBySubject(subjectId)
                ?: return DomainError.NotFound("No manifest for subject $subjectId").left()
        if (manifest.reviewSubmittedAt == null)
            return DomainError.Conflict(
                    "Claim review is not submitted for this subject — Stage 3 consumes only the " +
                        "reviewed, approved claim set"
                )
                .left()
        runs.findActiveBySubject(subjectId)?.let {
            return DomainError.Conflict("A Stage 3 run is already active (${it.id}: ${it.status})")
                .left()
        }
        val ping = graph.ping()
        if (!ping.reachable) return DomainError.Conflict("Neo4j unreachable: ${ping.error}").left()
        runCatching { graph.ensureSchema() }
            .onFailure {
                return DomainError.Conflict("Neo4j schema could not be ensured: ${it.message}")
                    .left()
            }
        val now = Instant.now()
        val run =
            Stage3Run(
                id = runs.newId(),
                subjectId = subjectId,
                status = Stage3RunStatus.PENDING,
                paramsSnapshot = Json.writeLine(props.stage3),
                createdBy = actor,
                createdAt = now,
                phaseSince = now,
            )
        runs.save(run)
        log.info("Stage 3 run {} created for subject {}", run.id, subjectId)
        return run.right()
    }

    // ---- poll (one bounded step) ---------------------------------------------------

    /**
     * Advance the run exactly one bounded step. Terminal runs and the AWAITING_REVIEW park are
     * no-ops; a transport-level failure before any phase work persists nothing (the run stays
     * pollable); an in-phase provider/graph failure marks the run FAILED with the verbatim error.
     */
    fun poll(runId: String): Either<DomainError, Stage3Run> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status.terminal) return run.right()
        // The Q6 gate: queue actions and publish (VA-18) move it on, never the poll loop.
        if (run.status == Stage3RunStatus.AWAITING_REVIEW) return run.right()
        reclaimIfStuck(run)?.let {
            return it.right()
        }
        return when (run.status) {
            Stage3RunStatus.PENDING -> runSync(enterPhase(run, Stage3RunStatus.SYNCING))
            Stage3RunStatus.SYNCING -> runSync(run) // re-entrant after a crashed poll
            Stage3RunStatus.RESOLVING_ENTITIES ->
                stubAdvance(run, Stage3RunStatus.EMBEDDING, "RESOLVE_ENTITIES", "VA-11").right()
            Stage3RunStatus.EMBEDDING -> runEmbedChunk(run).right()
            Stage3RunStatus.MATCHING ->
                stubAdvance(run, Stage3RunStatus.JUDGING, "MATCH", "VA-14").right()
            Stage3RunStatus.JUDGING ->
                stubAdvance(run, Stage3RunStatus.ASSEMBLING, "JUDGE", "VA-15").right()
            Stage3RunStatus.ASSEMBLING ->
                stubAdvance(run, Stage3RunStatus.SCORING, "ASSEMBLE", "VA-16").right()
            Stage3RunStatus.SCORING ->
                stubAdvance(run, Stage3RunStatus.AWAITING_REVIEW, "SCORE", "VA-17").right()
            // PUBLISHING is only enterable via the VA-18 publish action; nothing to do here.
            else -> run.right()
        }
    }

    /** Operator retry: FAILED → resume in the phase that failed (phases are re-entrant). */
    fun retry(runId: String): Either<DomainError, Stage3Run> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status != Stage3RunStatus.FAILED)
            return DomainError.Conflict("Only FAILED runs can be retried (run is ${run.status})")
                .left()
        val resumed =
            run.copy(
                status = run.failedPhase ?: Stage3RunStatus.PENDING,
                failedPhase = null,
                error = null,
                finishedAt = null,
                phaseSince = Instant.now(),
            )
        runs.save(resumed)
        return resumed.right()
    }

    /**
     * Re-run a PUBLISHED subject: a **new** PENDING run (fresh params snapshot) rather than
     * mutating the published record — ledger claims stamp `scoreRunId`, and that id must keep
     * resolving to the exact params that produced the published scores (§9.6 reproducibility).
     * [fresh] additionally wipes the subject's evidence layer at SYNC (and will drop the judge
     * cache once VA-15 lands).
     */
    fun rerun(runId: String, fresh: Boolean, actor: String?): Either<DomainError, Stage3Run> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status != Stage3RunStatus.PUBLISHED)
            return DomainError.Conflict(
                    "Only PUBLISHED runs can be re-run (run is ${run.status}; use Retry for FAILED)"
                )
                .left()
        runs.findActiveBySubject(run.subjectId)?.let {
            return DomainError.Conflict("A Stage 3 run is already active (${it.id}: ${it.status})")
                .left()
        }
        val now = Instant.now()
        val next =
            Stage3Run(
                id = runs.newId(),
                subjectId = run.subjectId,
                fresh = fresh,
                paramsSnapshot = Json.writeLine(props.stage3),
                createdBy = actor,
                createdAt = now,
                phaseSince = now,
            )
        runs.save(next)
        log.info(
            "Stage 3 re-run {} (fresh={}) created for subject {} replacing published {}",
            next.id,
            fresh,
            run.subjectId,
            run.id,
        )
        return next.right()
    }

    // ---- reads ------------------------------------------------------------------

    fun run(runId: String): Stage3Run? = runs.findById(runId)

    fun latestForSubject(subjectId: String): Stage3Run? =
        runs.findBySubject(subjectId).firstOrNull()

    // ---- phases -----------------------------------------------------------------

    /**
     * SYNC (LLD §11.2): project the reviewed claim set into the graph — claims/sources (evidence
     * layer), derived attestors (global trust layer), SIDECARED explanations. Pure mapping in
     * `buildEvidenceProjection`; idempotent MERGE in the repository. A `fresh` run wipes the
     * subject's evidence layer first (never the global layers).
     *
     * Input loading (Firestore) is outside the phase guard: a transport blip there returns 400 and
     * persists nothing — the run stays pollable (LLD §10). Graph work failing is the state
     * machine's "SYNCING → FAILED : Neo4j unreachable (verbatim)" edge.
     */
    private fun runSync(run: Stage3Run): Either<DomainError, Stage3Run> {
        val projection =
            try {
                buildEvidenceProjection(
                    subjectId = run.subjectId,
                    subjectName = subjects.findById(run.subjectId)?.displayName,
                    approved = reviewService.approvedForDownstream(run.subjectId),
                    assets = assets.findBySubject(run.subjectId),
                    speakerBindings =
                        stage2Jobs
                            .findBySubject(run.subjectId)
                            .mapNotNull { job -> job.speakerRoles?.let { job.assetId to it } }
                            .toMap(),
                    reviews = claimReviews.findBySubject(run.subjectId).associateBy { it.claimId },
                )
            } catch (e: Exception) {
                return DomainError.Invalid("Could not load Stage 3 sync inputs: ${e.message}")
                    .left()
            }
        return inPhase(run, "SYNC") {
                if (run.fresh) {
                    val wiped = graph.wipeEvidenceLayer(run.subjectId)
                    log.info("Run {}: fresh re-run wiped {} evidence node(s)", run.id, wiped)
                }
                graph.mergeEvidence(projection)
                if (projection.citationsDropped > 0)
                    log.info(
                        "Run {}: dropped {} sidecar citation(s) pointing at non-approved claims",
                        run.id,
                        projection.citationsDropped,
                    )
                advance(
                    run,
                    Stage3RunStatus.RESOLVING_ENTITIES,
                    mapOf(
                        Stage3Counters.CLAIMS_SYNCED to projection.claims.size.toLong(),
                        Stage3Counters.SOURCES_SYNCED to projection.sources.size.toLong(),
                        Stage3Counters.ATTESTORS_SYNCED to projection.attestors.size.toLong(),
                        Stage3Counters.EXPLANATIONS_SYNCED to projection.explanations.size.toLong(),
                        Stage3Counters.CITATIONS_DROPPED to projection.citationsDropped.toLong(),
                    ),
                )
            }
            .right()
    }

    /**
     * EMBED (LLD §11.4): one bounded chunk per poll. The graph is the cursor — claims with a
     * missing or stale `embeddingModelVersion` stamp are the remaining work, so a killed poll
     * resumes for free and a model/dims/dry-run change re-embeds automatically (§15 #6).
     */
    private fun runEmbedChunk(run: Stage3Run): Stage3Run =
        inPhase(run, "EMBED") {
            val stamp = embeddings.versionStamp
            val batch =
                graph.claimsNeedingEmbedding(run.subjectId, stamp, props.stage3.embedBatchPerPoll)
            if (batch.isEmpty()) {
                advance(
                    run,
                    Stage3RunStatus.MATCHING,
                    mapOf(Stage3Counters.CLAIMS_EMBEDDED to graph.countClaims(run.subjectId)),
                )
            } else {
                val embedded =
                    batch.map {
                        EmbeddedClaim(
                            claimId = it.claimId,
                            embedding =
                                embeddings.embed(
                                    it.embeddingText(),
                                    EmbeddingTaskType.SEMANTIC_SIMILARITY,
                                ),
                        )
                    }
                graph.setClaimEmbeddings(run.subjectId, embedded, stamp)
                val done =
                    graph.countClaims(run.subjectId) -
                        graph.countClaimsNeedingEmbedding(run.subjectId, stamp)
                // Same phase, fresh progress stamp — the reclaim clock resets on real progress.
                val progressed =
                    run.copy(
                        counters = run.counters + (Stage3Counters.CLAIMS_EMBEDDED to done),
                        phaseSince = Instant.now(),
                    )
                runs.save(progressed)
                progressed
            }
        }

    /** A not-yet-built phase slot: log, advance, keep counters honest (empty) — see class KDoc. */
    private fun stubAdvance(
        run: Stage3Run,
        to: Stage3RunStatus,
        phase: String,
        ticket: String,
    ): Stage3Run {
        log.info("Run {}: {} not implemented yet ({}) — advancing to {}", run.id, phase, ticket, to)
        return advance(run, to, emptyMap())
    }

    // ---- lifecycle helpers ---------------------------------------------------------

    private fun enterPhase(run: Stage3Run, phase: Stage3RunStatus): Stage3Run =
        run.copy(
                status = phase,
                startedAt = run.startedAt ?: Instant.now(),
                phaseSince = Instant.now(),
            )
            .also { runs.save(it) }

    private fun advance(
        run: Stage3Run,
        to: Stage3RunStatus,
        counterUpdates: Map<String, Long>,
    ): Stage3Run =
        run.copy(
                status = to,
                counters = run.counters + counterUpdates,
                phaseSince = Instant.now(),
            )
            .also { runs.save(it) }

    /**
     * Run one phase step, converting any exception into the terminal FAILED state with the verbatim
     * error (LLD §9.7's FAILED edges; §15 #1/#2) — Retry resumes from [Stage3Run.failedPhase].
     */
    private fun inPhase(run: Stage3Run, phase: String, work: () -> Stage3Run): Stage3Run =
        try {
            work()
        } catch (e: Exception) {
            log.warn("Run {}: {} failed — {}", run.id, phase, e.message)
            fail(run, "$phase: ${e.message}")
        }

    private fun fail(run: Stage3Run, message: String): Stage3Run =
        run.copy(
                status = Stage3RunStatus.FAILED,
                failedPhase = run.status,
                error = message,
                finishedAt = Instant.now(),
            )
            .also { runs.save(it) }

    /**
     * The §12.7 reclaim: a phase that has made no successful advance for `phase-timeout` is failed
     * on the next poll so the operator can Retry (covers polls that died mid-phase and phases
     * error-looping without progress). PENDING is exempt — it advances the moment it is polled.
     */
    private fun reclaimIfStuck(run: Stage3Run): Stage3Run? {
        if (run.status == Stage3RunStatus.PENDING) return null
        val since = run.phaseSince ?: return null
        val timeout = props.stage3.phaseTimeout
        if (Instant.now().isBefore(since.plus(timeout))) return null
        log.warn("Run {}: {} stuck since {} — reclaiming to FAILED", run.id, run.status, since)
        return fail(
            run,
            "${run.status} made no progress for ${timeout.toMinutes()}m (since $since) — " +
                "reclaimed; Retry resumes the phase",
        )
    }
}

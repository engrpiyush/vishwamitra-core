package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.Stage3Counters
import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimAuthenticityRow
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.PublishContract
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeRepository
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectFactRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRecord
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.stage3.ClaimJudgeService
import ai.vishwakarma.labelling.stage3.ClaimMatcher
import ai.vishwakarma.labelling.stage3.ContradictionEdgeRef
import ai.vishwakarma.labelling.stage3.ContradictionView
import ai.vishwakarma.labelling.stage3.EmbeddedClaim
import ai.vishwakarma.labelling.stage3.EmbeddingService
import ai.vishwakarma.labelling.stage3.EmbeddingTaskType
import ai.vishwakarma.labelling.stage3.EntityEmbedding
import ai.vishwakarma.labelling.stage3.EntityMentionExtractor
import ai.vishwakarma.labelling.stage3.EntityResolver
import ai.vishwakarma.labelling.stage3.ExplanationRow
import ai.vishwakarma.labelling.stage3.FactAssembler
import ai.vishwakarma.labelling.stage3.JudgeProgress
import ai.vishwakarma.labelling.stage3.PublishProjection
import ai.vishwakarma.labelling.stage3.ScoreOutcome
import ai.vishwakarma.labelling.stage3.Scorer
import ai.vishwakarma.labelling.stage3.ScorerParams
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import ai.vishwakarma.labelling.stage3.SubjectScore
import ai.vishwakarma.labelling.stage3.SubjectScorer
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
 * plus the complete pipeline: SYNC (§11.2), RESOLVE_ENTITIES (§11.3), EMBED (§11.4), MATCH (§11.5),
 * JUDGE (§11.6), ASSEMBLE (§11.7) and SCORE (§11.8), which parks the run at the Q6 AWAITING_REVIEW
 * gate; the §11.10 queue actions (confirm / dismiss / explain-re-judge) act there, and [publish] —
 * the only door to the Firestore ledger — moves it through PUBLISHING to PUBLISHED.
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
    private val entityExtractor: EntityMentionExtractor,
    private val entityResolver: EntityResolver,
    private val judge: ClaimJudgeService,
    private val judgeCache: Stage3EdgeRepository,
    private val claimLedger: ClaimRepository,
    private val subjectScores: SubjectScoreRepository,
    private val subjectFacts: SubjectFactRepository,
    private val aggregateScores: AggregateScoreService,
    private val config: StageConfigService,
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
                paramsSnapshot = Json.writeLine(config.stage3()),
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
            Stage3RunStatus.RESOLVING_ENTITIES -> runResolveEntitiesChunk(run).right()
            Stage3RunStatus.EMBEDDING -> runEmbedChunk(run).right()
            Stage3RunStatus.MATCHING -> runMatchTick(run).right()
            Stage3RunStatus.JUDGING -> runJudgeTick(run).right()
            Stage3RunStatus.ASSEMBLING -> runAssembleTick(run).right()
            Stage3RunStatus.SCORING -> runScoreTick(run).right()
            // Entered via the publish action; a poll here resumes a crashed ledger write.
            Stage3RunStatus.PUBLISHING -> runPublishTick(run).right()
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
     * Re-run a PUBLISHED or AWAITING_REVIEW subject: a **new** PENDING run (fresh params snapshot)
     * rather than mutating the old record — ledger claims stamp `scoreRunId`, and that id must keep
     * resolving to the exact params that produced the published scores (§9.6 reproducibility). A
     * parked AWAITING_REVIEW run retires as SUPERSEDED first (it never published, so nothing
     * references it; its provisional graph-side scores are overwritten by the new run's SCORE).
     * [fresh] additionally wipes the subject's evidence layer AND drops its cached judge verdicts
     * at SYNC — the full re-judge posture; a plain re-run keeps the cache and is LLM-free.
     */
    fun rerun(runId: String, fresh: Boolean, actor: String?): Either<DomainError, Stage3Run> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (
            run.status != Stage3RunStatus.PUBLISHED && run.status != Stage3RunStatus.AWAITING_REVIEW
        )
            return DomainError.Conflict(
                    "Only PUBLISHED or AWAITING_REVIEW runs can be re-run " +
                        "(run is ${run.status}; use Retry for FAILED)"
                )
                .left()
        runs
            .findActiveBySubject(run.subjectId)
            ?.takeIf { it.id != run.id }
            ?.let {
                return DomainError.Conflict(
                        "A Stage 3 run is already active (${it.id}: ${it.status})"
                    )
                    .left()
            }
        // Same graph guards as submit: the documented wipe-then-rerun recovery (RUNBOOK §7)
        // otherwise reaches SYNC against a bare database and fails mid-phase instead of here.
        val ping = graph.ping()
        if (!ping.reachable) return DomainError.Conflict("Neo4j unreachable: ${ping.error}").left()
        runCatching { graph.ensureSchema() }
            .onFailure {
                return DomainError.Conflict("Neo4j schema could not be ensured: ${it.message}")
                    .left()
            }
        val now = Instant.now()
        // Retire the parked run before creating its replacement: a crash between the two saves
        // leaves the subject unblocked (no active run) rather than with two active runs.
        if (run.status == Stage3RunStatus.AWAITING_REVIEW)
            runs.save(run.copy(status = Stage3RunStatus.SUPERSEDED, finishedAt = now))
        val next =
            Stage3Run(
                id = runs.newId(),
                subjectId = run.subjectId,
                fresh = fresh,
                paramsSnapshot = Json.writeLine(config.stage3()),
                createdBy = actor,
                createdAt = now,
                phaseSince = now,
            )
        runs.save(next)
        log.info(
            "Stage 3 re-run {} (fresh={}) created for subject {} replacing {} {}",
            next.id,
            fresh,
            run.subjectId,
            run.status.name.lowercase(),
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
                    val dropped = judgeCache.deleteBySubject(run.subjectId)
                    log.info(
                        "Run {}: fresh re-run wiped {} evidence node(s), dropped {} cached " +
                            "judge verdict(s)",
                        run.id,
                        wiped,
                        dropped,
                    )
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
     * RESOLVE_ENTITIES (LLD §11.3): one bounded chunk per poll — one Gemini typed-mention
     * extraction call over the next claim batch, resolved against the **global** canon (link / mint
     * / review-list, the [EntityResolver] pipeline) and persisted as a single transaction. The
     * graph is the cursor (`entityResolutionStamp` vs the extractor's prompt stamp), so a killed
     * poll resumes free and a prompt edit or dry-run flip re-resolves on the next run. When nothing
     * remains, the §18.2 Q2 issuer-attestor upgrade sweep runs (documentary fallback attestors
     * migrate to the freshly resolved issuer entities) and the phase advances.
     */
    private fun runResolveEntitiesChunk(run: Stage3Run): Stage3Run =
        inPhase(run, "RESOLVE_ENTITIES") {
            val stamp = entityExtractor.versionStamp
            val batch =
                graph.claimsNeedingEntityResolution(
                    run.subjectId,
                    stamp,
                    config.stage3().entityBatchPerPoll,
                )
            if (batch.isEmpty()) {
                val upgraded = graph.upgradeIssuerAttestors(run.subjectId)
                if (upgraded > 0)
                    log.info(
                        "Run {}: upgraded {} documentary attestation(s) to issuer-entity keys " +
                            "(LLD §18.2 Q2)",
                        run.id,
                        upgraded,
                    )
                advance(
                    run,
                    Stage3RunStatus.EMBEDDING,
                    mapOf(
                        Stage3Counters.CLAIMS_ENTITY_RESOLVED to graph.countClaims(run.subjectId),
                        Stage3Counters.ISSUER_ATTESTORS_UPGRADED to upgraded,
                    ),
                )
            } else {
                val outcome =
                    entityResolver.resolve(
                        run.subjectId,
                        batch,
                        entityExtractor.extract(batch),
                        stamp,
                    )
                val done =
                    graph.countClaims(run.subjectId) -
                        graph.countClaimsNeedingEntityResolution(run.subjectId, stamp)
                val counters = run.counters
                val progressed =
                    run.copy(
                        counters =
                            counters +
                                mapOf(
                                    Stage3Counters.CLAIMS_ENTITY_RESOLVED to done,
                                    Stage3Counters.MENTIONS_LINKED to
                                        (counters[Stage3Counters.MENTIONS_LINKED] ?: 0L) +
                                            outcome.linked,
                                    Stage3Counters.ENTITIES_MINTED to
                                        (counters[Stage3Counters.ENTITIES_MINTED] ?: 0L) +
                                            outcome.minted,
                                    Stage3Counters.MENTIONS_REVIEW_LISTED to
                                        (counters[Stage3Counters.MENTIONS_REVIEW_LISTED] ?: 0L) +
                                            outcome.reviewListed,
                                ),
                        phaseSince = Instant.now(),
                    )
                runs.save(progressed)
                progressed
            }
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
                graph.claimsNeedingEmbedding(
                    run.subjectId,
                    stamp,
                    config.stage3().embedBatchPerPoll
                )
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

    /**
     * MATCH (LLD §11.5): blocking union → precision cascade → persisted judge queue. Three tick
     * shapes, so every poll stays bounded:
     * 1. **Stale claim vectors** (§15 #6 — model/dims/dry-run changed since EMBED): bounce the run
     *    back to EMBEDDING; its graph-as-cursor re-embeds exactly the stale claims and returns
     *    here. Vector spaces never mix.
     * 2. **Stale entity vectors** (same rule, ontology side — no phase owns entity vectors after
     *    minting): re-embed one bounded chunk of the entities this subject mentions and stay in
     *    MATCHING.
     * 3. **All vectors current**: run the whole blocking + cascade tick — §16 sizes this as seconds
     *    of Cypher + in-app rungs even at the ~800-claim reference — write auto-REPEATS + the
     *    ranked queue in one transaction, advance to JUDGING.
     */
    private fun runMatchTick(run: Stage3Run): Stage3Run =
        inPhase(run, "MATCH") {
            val stamp = embeddings.versionStamp
            val staleClaims = graph.countClaimsNeedingEmbedding(run.subjectId, stamp)
            if (staleClaims > 0) {
                log.info(
                    "Run {}: {} claim vector(s) stale against {} — re-embedding before kNN " +
                        "(LLD §15 #6)",
                    run.id,
                    staleClaims,
                    stamp,
                )
                advance(run, Stage3RunStatus.EMBEDDING, emptyMap())
            } else {
                val staleEntities =
                    graph.entitiesNeedingReembedding(
                        run.subjectId,
                        stamp,
                        config.stage3().embedBatchPerPoll,
                    )
                if (staleEntities.isNotEmpty()) {
                    graph.setEntityEmbeddings(
                        staleEntities.map {
                            EntityEmbedding(
                                entityId = it.entityId,
                                embedding =
                                    embeddings.embed(
                                        it.canonicalName,
                                        EmbeddingTaskType.CLASSIFICATION,
                                    ),
                            )
                        },
                        stamp,
                    )
                    val progressed =
                        run.copy(
                            counters =
                                run.counters +
                                    (Stage3Counters.ENTITIES_REEMBEDDED to
                                        (run.counters[Stage3Counters.ENTITIES_REEMBEDDED] ?: 0L) +
                                            staleEntities.size),
                            phaseSince = Instant.now(),
                        )
                    runs.save(progressed)
                    progressed
                } else {
                    runMatchBlocking(run)
                }
            }
        }

    /** The single blocking + cascade tick (§11.5) — all vectors verified current by the caller. */
    private fun runMatchBlocking(run: Stage3Run): Stage3Run {
        val s3 = config.stage3()
        val claims = graph.claimsForMatching(run.subjectId)
        val knn =
            if (s3.exhaustiveMatching) emptyList()
            else graph.claimKnnPairs(run.subjectId, s3.knnK, s3.simFloor)
        val coMention =
            if (s3.exhaustiveMatching) emptyList()
            else graph.coMentionPairs(run.subjectId, s3.entityIdfFloor)
        val human = graph.humanAssertedPairs(run.subjectId)
        val candidates = ClaimMatcher.candidates(claims, knn, coMention, human, s3)
        // kNN already scored its arm; every other candidate pair gets an exact cosine.
        val knnSims = knn.associate { it.pair to it.sim }
        val sims = knnSims + graph.pairSimilarities(run.subjectId, candidates.keys - knnSims.keys)
        val outcome = ClaimMatcher.cascade(claims, candidates, sims, s3)
        graph.applyMatchOutcome(run.subjectId, outcome)
        log.info(
            "Run {}: MATCH {} candidates → {} auto-resolved, {} discarded, {} queued ({})",
            run.id,
            outcome.counters.pairsCandidate,
            outcome.counters.pairsAutoResolved,
            outcome.counters.pairsDiscarded,
            outcome.counters.pairsQueued,
            s3.matchingMode,
        )
        return advance(
            run,
            Stage3RunStatus.JUDGING,
            mapOf(
                Stage3Counters.PAIRS_KNN to outcome.counters.pairsKnn,
                Stage3Counters.PAIRS_CO_MENTION to outcome.counters.pairsCoMention,
                Stage3Counters.PAIRS_STRUCTURAL to outcome.counters.pairsStructural,
                Stage3Counters.PAIRS_HUMAN_ASSERTED to outcome.counters.pairsHumanAsserted,
                Stage3Counters.PAIRS_CANDIDATE to outcome.counters.pairsCandidate,
                Stage3Counters.PAIRS_AUTO_RESOLVED to outcome.counters.pairsAutoResolved,
                Stage3Counters.PAIRS_DISCARDED to outcome.counters.pairsDiscarded,
                Stage3Counters.PAIRS_QUEUED to outcome.counters.pairsQueued,
            ),
        )
    }

    /**
     * JUDGE (LLD §11.6): one bounded chunk per poll — up to `judge-pairs-per-poll` QUEUED pairs by
     * ascending rank, verdict-cache-first (a re-run over unchanged pairs samples nothing), the
     * ensemble only for misses. The `JUDGE_QUEUED.status` flip is the phase cursor (`judgeCursor`
     * as-built): a killed poll re-reads exactly the unfinished pairs, and their cache hits make the
     * retry free. When nothing remains QUEUED the phase advances with the authoritative JUDGED
     * count.
     */
    private fun runJudgeTick(run: Stage3Run): Stage3Run =
        inPhase(run, "JUDGE") {
            val batch = graph.judgeQueueBatch(run.subjectId, config.stage3().judgePairsPerPoll)
            if (batch.isEmpty()) {
                advance(
                    run,
                    Stage3RunStatus.ASSEMBLING,
                    mapOf(
                        Stage3Counters.PAIRS_JUDGED to
                            graph.countJudgeQueue(run.subjectId, "JUDGED")
                    ),
                )
            } else {
                val outcome =
                    judge.judgePairs(
                        run.subjectId,
                        batch,
                        JudgeProgress(
                            judgedSoFar = run.counters[Stage3Counters.PAIRS_JUDGED] ?: 0L,
                            totalQueued = run.counters[Stage3Counters.PAIRS_QUEUED] ?: 0L,
                        ),
                    )
                graph.applyJudgeOutcome(run.subjectId, outcome.judged)
                val counters = run.counters
                val progressed =
                    run.copy(
                        counters =
                            counters +
                                mapOf(
                                    Stage3Counters.PAIRS_JUDGED to
                                        graph.countJudgeQueue(run.subjectId, "JUDGED"),
                                    Stage3Counters.JUDGE_CACHE_HITS to
                                        (counters[Stage3Counters.JUDGE_CACHE_HITS] ?: 0L) +
                                            outcome.cacheHits,
                                    Stage3Counters.JUDGE_SAMPLER_CALLS to
                                        (counters[Stage3Counters.JUDGE_SAMPLER_CALLS] ?: 0L) +
                                            outcome.samplerCalls,
                                    Stage3Counters.JUDGE_TIES to
                                        (counters[Stage3Counters.JUDGE_TIES] ?: 0L) + outcome.ties,
                                ),
                        phaseSince = Instant.now(),
                    )
                runs.save(progressed)
                progressed
            }
        }

    /**
     * ASSEMBLE (LLD §11.7): one idempotent tick — union-find clustering over REPEATS, exemplar +
     * kind + interval inference, SUCCEEDS sequencing and the temporally-gated edge lift, persisted
     * wholesale (the subject's `:Fact` layer is rebuilt). In-app work is trivial at the §16 sizing,
     * so the phase never chunks.
     */
    private fun runAssembleTick(run: Stage3Run): Stage3Run =
        inPhase(run, "ASSEMBLE") {
            val outcome =
                FactAssembler.assemble(
                    graph.claimsForAssembly(run.subjectId),
                    graph.repeatsPairs(run.subjectId),
                    graph.judgedPairRecords(run.subjectId),
                    config.stage3(),
                )
            graph.applyAssembleOutcome(run.subjectId, outcome)
            log.info(
                "Run {}: ASSEMBLE {} fact(s) ({} STATE / {} EVENT / {} TIMELESS), {} corroborates, " +
                    "{} contradicts (+{} gated), {} succeeds",
                run.id,
                outcome.counters.facts,
                outcome.counters.factsState,
                outcome.counters.factsEvent,
                outcome.counters.factsTimeless,
                outcome.counters.factCorroborates,
                outcome.counters.factContradicts,
                outcome.counters.contradictionsGated,
                outcome.counters.succeedsEdges,
            )
            advance(
                run,
                Stage3RunStatus.SCORING,
                mapOf(
                    Stage3Counters.FACTS to outcome.counters.facts,
                    Stage3Counters.FACTS_STATE to outcome.counters.factsState,
                    Stage3Counters.FACTS_EVENT to outcome.counters.factsEvent,
                    Stage3Counters.FACTS_TIMELESS to outcome.counters.factsTimeless,
                    Stage3Counters.FACT_CORROBORATES to outcome.counters.factCorroborates,
                    Stage3Counters.FACT_CONTRADICTS to outcome.counters.factContradicts,
                    Stage3Counters.CONTRADICTIONS_GATED to outcome.counters.contradictionsGated,
                    Stage3Counters.SUCCEEDS_EDGES to outcome.counters.succeedsEdges,
                ),
            )
        }

    /**
     * SCORE (LLD §11.8): one in-app tick — snapshot the assembled graph, run the pure dual-pass
     * fixed point, persist provisional scores graph-side, park at the Q6 gate. The ledger stays
     * untouched (§11.10); publish is VA-18's action.
     */
    private fun runScoreTick(run: Stage3Run): Stage3Run =
        inPhase(run, "SCORE") {
            val outcome = rescore(run.subjectId)
            val queue =
                graph.countContradictionQueue(run.subjectId, config.stage3().judgeConfidenceFloor)
            if (outcome.i2Clamped > 0)
                log.warn(
                    "Run {}: {} claim(s) violated score ≥ scoreBare and were lifted (I2 — " +
                        "investigate the ctx verdicts)",
                    run.id,
                    outcome.i2Clamped,
                )
            log.info(
                "Run {}: SCORE {} claim(s) over {} fact(s) — converged={} in {} iteration(s), " +
                    "queue={}",
                run.id,
                outcome.claims.size,
                outcome.facts.size,
                outcome.converged,
                outcome.iterations,
                queue,
            )
            advance(
                run.copy(converged = outcome.converged, iterations = outcome.iterations),
                Stage3RunStatus.AWAITING_REVIEW,
                mapOf(
                    Stage3Counters.CLAIMS_SCORED to outcome.claims.size.toLong(),
                    Stage3Counters.CONTRADICTION_QUEUE to queue,
                    Stage3Counters.SCORE_I2_CLAMPED to outcome.i2Clamped,
                ),
            )
        }

    /**
     * The §11.8 scoring pass over the subject's current graph, shared by the SCORING tick and the
     * §11.10 queue actions (**incremental re-score**: edges changed ⇒ re-run steps 1–5 only — no
     * matching, no LLM, milliseconds). Persists provisional scores + trust and returns the outcome;
     * `asOf` is the wall clock (recency decays with real time between runs — scores stay
     * attributable via the run's paramsSnapshot + scoredAt).
     */
    fun rescore(subjectId: String): ScoreOutcome {
        val outcome =
            Scorer.score(
                graph.scoreSnapshot(subjectId),
                ScorerParams(
                    stage3 = config.stage3(),
                    favorabilityThreshold = config.stage2().favorabilityThreshold,
                    asOf = java.time.LocalDate.now(),
                ),
            )
        graph.applyScoreOutcome(subjectId, outcome)
        return outcome
    }

    // ---- AWAITING_REVIEW queue actions + gated publish (LLD §11.10–§11.11) ------------

    /** The §11.10 queue read: PROPOSED, unexplained contradictions at/above the floor. */
    fun contradictions(subjectId: String): List<ContradictionView> =
        graph.contradictionQueue(subjectId, config.stage3().judgeConfidenceFloor)

    /** Confirm: the penalty stands — CONFIRMED leaves the queue, keeps its effect. */
    fun confirmContradiction(edgeId: String): Either<DomainError, Stage3Run> =
        edgeAction(edgeId) { edge, run ->
            if (!graph.confirmContradiction(edge.subjectId, edgeId))
                DomainError.Conflict("Contradiction is not PROPOSED anymore").left()
            else refreshQueueCounter(run).right()
        }

    /**
     * Dismiss: the judge was wrong — delete the edge, permanently override the cached verdicts
     * behind it (they may never hit again), and incrementally re-score (steps 1–5, no LLM).
     */
    fun dismissContradiction(edgeId: String): Either<DomainError, Stage3Run> =
        edgeAction(edgeId) { edge, run ->
            if (!graph.deleteContradiction(edge.subjectId, edgeId))
                return@edgeAction DomainError.Conflict("Contradiction is already gone").left()
            edge.pairs().forEach { judgeCache.markOverridden(it.a, it.b) }
            rescore(edge.subjectId)
            log.info(
                "Run {}: dismissed contradiction {} ({} cached verdict pair(s) overridden)",
                run.id,
                edgeId,
                edge.pairs().size,
            )
            refreshQueueCounter(run).right()
        }

    /**
     * The explain hook (§11.10 "Explain"): after the operator authors/edits the §12.6 sidecar on an
     * involved claim, this re-projects the explanation into the graph, re-judges the edge's
     * contributing pairs **with context** (one ensemble batch — the changed sidecar text misses the
     * ctx cache by hash), stamps the fresh ctx verdict + relevance onto the edge, and incrementally
     * re-scores. An affirmed-relevant or neutralized edge leaves the queue as `explained`.
     */
    fun rejudgeContradiction(edgeId: String): Either<DomainError, Stage3Run> =
        edgeAction(edgeId) { edge, run ->
            val pairs = edge.pairs()
            if (pairs.isEmpty())
                return@edgeAction DomainError.Invalid(
                        "Contradiction $edgeId carries no contributing pairs"
                    )
                    .left()
            val sidecars =
                pairs
                    .flatMap { listOf(it.a, it.b) }
                    .distinct()
                    .mapNotNull { claimId ->
                        claimReviews
                            .findByClaim(claimId)
                            ?.takeIf {
                                it.decision == ReviewDecision.SIDECARED &&
                                    !it.justification.isNullOrBlank()
                            }
                            ?.let { review ->
                                ExplanationRow(
                                    explanationId = claimId,
                                    claimId = claimId,
                                    text = review.justification!!.trim(),
                                    author = review.reviewedBy,
                                    createdAt = review.reviewedAt?.toString(),
                                    cites = emptyList(),
                                )
                            }
                    }
            graph.upsertExplanations(edge.subjectId, sidecars)
            val hydrated = graph.hydratePairs(edge.subjectId, pairs).filter { it.withContext }
            if (hydrated.isEmpty())
                return@edgeAction DomainError.Conflict(
                        "No sidecar on either claim of this contradiction — author the " +
                            "explanation first"
                    )
                    .left()
            val outcome = judge.judgePairs(edge.subjectId, hydrated)
            graph.applyJudgeOutcome(edge.subjectId, outcome.judged)
            val best =
                outcome.judged
                    .filter { it.ctx != null }
                    .maxWithOrNull(
                        compareBy<ai.vishwakarma.labelling.stage3.JudgedPair> { it.bare.confidence }
                            .thenBy { it.pair.a }
                            .thenBy { it.pair.b }
                    )
            graph.updateContradictionContext(
                edge.subjectId,
                edgeId,
                ctxRelation = best?.ctx?.relation?.name,
                ctxConfidence = best?.ctx?.confidence,
                explained = outcome.judged.any { it.ctx?.explanationRelevant == true },
            )
            rescore(edge.subjectId)
            refreshQueueCounter(run).right()
        }

    /**
     * Publish (§11.10, the Q6 gate): from AWAITING_REVIEW only; refused while PROPOSED
     * contradictions remain unless [skipReview] — which is recorded as the audit cost of skipping.
     * PUBLISHING then batch-writes the ledger idempotently (a crash resumes via poll/Retry).
     */
    fun publish(
        runId: String,
        skipReview: Boolean,
        actor: String?
    ): Either<DomainError, Stage3Run> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status != Stage3RunStatus.AWAITING_REVIEW)
            return DomainError.Conflict(
                    "Only AWAITING_REVIEW runs can publish (run is ${run.status})"
                )
                .left()
        val queue =
            graph.countContradictionQueue(run.subjectId, config.stage3().judgeConfidenceFloor)
        if (queue > 0 && config.stage3().publishRequiresReview && !skipReview)
            return DomainError.Conflict(
                    "$queue proposed contradiction(s) await review — confirm/dismiss/explain " +
                        "them, or publish with skipReview=true"
                )
                .left()
        val publishing =
            run.copy(
                    status = Stage3RunStatus.PUBLISHING,
                    publishedBy = actor,
                    reviewSkipped = skipReview && queue > 0,
                    counters = run.counters + (Stage3Counters.CONTRADICTION_QUEUE to queue),
                    phaseSince = Instant.now(),
                )
                .also { runs.save(it) }
        return runPublishTick(publishing).right()
    }

    /**
     * PUBLISHING (§11.11, contract v2): project the §21 A.3 readback (+ timeline) into the ledger —
     * per-claim vectors WITH their fact/entity context, the `subject_facts` detail docs, and the
     * subject aggregate + frozen provenance legend — all idempotent and resumable (per-claim
     * updates are atomic; `subject_facts` replaces write-first-then-delete-stale; a resumed tick
     * re-writes identical values). Then PUBLISHED.
     */
    private fun runPublishTick(run: Stage3Run): Stage3Run =
        inPhase(run, "PUBLISH") {
            val readback = graph.scoresReadback(run.subjectId)
            // Invariant guard (I-P1): every scored claim ASSERTS a fact, so the fact-joined
            // readback must cover exactly the scored set — anything else would silently
            // under-publish the ledger. Fail the run loudly instead.
            val expected = graph.scoredClaimsForPublish(run.subjectId).map { it.claimId }.toSet()
            val got = readback.map { it.claimId }.toSet()
            check(expected == got) {
                "scored claims without a fact — publish contract violated " +
                    "(scored=${expected.size}, readback=${got.size}, " +
                    "missing=${(expected - got).sorted().take(5)})"
            }
            val now = Instant.now()
            val blocks = PublishProjection.claimBlocks(readback)
            claimLedger.publishAuthenticity(
                readback.map { row ->
                    val block = blocks.getValue(row.claimId)
                    ClaimAuthenticityRow(
                        claimId = row.claimId,
                        score = row.score,
                        signals = parseSignals(row.signalsJson),
                        tier = tierOf(row.score),
                        scoreRunId = run.id,
                        scoredAt = now,
                        scoreBare = row.scoreBare,
                        factStamp = block.factStamp,
                        entityMentions = block.entityMentions,
                        edgeCounts = block.edgeCounts,
                        attestor = block.attestor,
                    )
                }
            )
            log.info("Run {}: published {} claim vector(s) to the ledger", run.id, readback.size)
            val factDocs =
                PublishProjection.factRecords(
                    run.subjectId,
                    readback,
                    graph.timeline(run.subjectId),
                    run.id,
                    now,
                )
            subjectFacts.replaceForSubject(run.subjectId, factDocs)
            log.info("Run {}: froze {} fact doc(s) into subject_facts", run.id, factDocs.size)
            val aggregate = SubjectScorer.score(readback, config.stage3())
            subjectScores.save(aggregate.toRecord(run, now))
            log.info(
                "Run {}: subject aggregate frozen — SAI {} ({})",
                run.id,
                aggregate.display,
                aggregate.band,
            )
            // The product LLD §10 seam (VA-44): the freshly published ledger re-derives the
            // subject-facing evidence-strength number on `advocates/{subjectId}`.
            aggregateScores.recompute(run.subjectId)
            val published =
                run.copy(
                    status = Stage3RunStatus.PUBLISHED,
                    publishedAt = now,
                    finishedAt = now,
                    counters =
                        run.counters +
                            (Stage3Counters.CLAIMS_PUBLISHED to readback.size.toLong()) +
                            (Stage3Counters.FACTS_PUBLISHED to factDocs.size.toLong()) +
                            (Stage3Counters.SUBJECT_SCORE to aggregate.display.toLong()),
                    phaseSince = now,
                )
            runs.save(published)
            published
        }

    /** Flatten the pure [SubjectScore] into the Firestore ledger record (Stage 3.5 LLD §5). */
    private fun SubjectScore.toRecord(run: Stage3Run, at: Instant): SubjectScoreRecord =
        SubjectScoreRecord(
            subjectId = run.subjectId,
            score = score,
            display = display,
            band = band,
            components =
                mapOf(
                    "weightedBelief" to components.weightedBelief,
                    "evidenceDepth" to components.evidenceDepth,
                    "independentCoverage" to components.independentCoverage,
                    "sourceDiversity" to components.sourceDiversity,
                    "contradictionDrag" to components.contradictionDrag,
                    "depthFactor" to components.depthFactor,
                    "coverageFactor" to components.coverageFactor,
                    "diversityFactor" to components.diversityFactor,
                    "contradictionFactor" to components.contradictionFactor,
                ),
            inputs =
                mapOf(
                    "selfOnlyFactCount" to inputs.selfOnlyFactCount,
                    "independentAttestorCount" to inputs.independentAttestorCount,
                    "attestorKindCount" to inputs.attestorKindCount,
                    "attestorsByKind" to inputs.attestorsByKind,
                    "documentaryFactFraction" to inputs.documentaryFactFraction,
                    "corroborationCount" to inputs.corroborationCount,
                    "contradictionCount" to inputs.contradictionCount,
                    "explainedContradictionCount" to inputs.explainedContradictionCount,
                    "confirmedContradictionCount" to inputs.confirmedContradictionCount,
                    "proposedContradictionCount" to inputs.proposedContradictionCount,
                    "meanEvidenceMass" to inputs.meanEvidenceMass,
                    "medianEvidenceMass" to inputs.medianEvidenceMass,
                    "anchoredFactCount" to inputs.anchoredFactCount,
                ),
            factCount = inputs.factCount,
            claimCount = inputs.claimCount,
            scoreRunId = run.id,
            publishedAt = at,
            publishedBy = run.publishedBy,
            publishContractVersion = PublishContract.VERSION,
            publishContract = PublishContract.asMap(),
        )

    /**
     * ADMIN reopen (§15 #12): PUBLISHED → AWAITING_REVIEW for another review round. The ledger
     * keeps the last-published values until the next publish overwrites them; the prior publish
     * audit stays on the run until then too.
     */
    fun reopen(runId: String): Either<DomainError, Stage3Run> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status != Stage3RunStatus.PUBLISHED)
            return DomainError.Conflict("Only PUBLISHED runs can reopen (run is ${run.status})")
                .left()
        runs.findActiveBySubject(run.subjectId)?.let {
            return DomainError.Conflict("A Stage 3 run is already active (${it.id}: ${it.status})")
                .left()
        }
        val reopened =
            run.copy(
                status = Stage3RunStatus.AWAITING_REVIEW,
                finishedAt = null,
                phaseSince = Instant.now(),
            )
        runs.save(reopened)
        log.info("Run {}: reopened to AWAITING_REVIEW", run.id)
        return reopened.right()
    }

    /** Queue actions share the guard: the edge must exist and its run must sit at the Q6 gate. */
    private fun edgeAction(
        edgeId: String,
        work: (ContradictionEdgeRef, Stage3Run) -> Either<DomainError, Stage3Run>,
    ): Either<DomainError, Stage3Run> {
        val edge =
            graph.findContradictionEdge(edgeId)
                ?: return DomainError.NotFound("Contradiction $edgeId not found").left()
        val run =
            runs.findActiveBySubject(edge.subjectId)
                ?: return DomainError.Conflict(
                        "No active Stage 3 run for subject ${edge.subjectId}"
                    )
                    .left()
        if (run.status != Stage3RunStatus.AWAITING_REVIEW)
            return DomainError.Conflict(
                    "Queue actions require AWAITING_REVIEW (run is ${run.status})"
                )
                .left()
        return work(edge, run)
    }

    private fun refreshQueueCounter(run: Stage3Run): Stage3Run =
        run.copy(
                counters =
                    run.counters +
                        (Stage3Counters.CONTRADICTION_QUEUE to
                            graph.countContradictionQueue(
                                run.subjectId,
                                config.stage3().judgeConfidenceFloor,
                            )),
            )
            .also { runs.save(it) }

    @Suppress("UNCHECKED_CAST")
    private fun parseSignals(json: String?): Map<String, Double> {
        val raw =
            json?.let { runCatching { Json.parse(it) as? Map<String, Any?> }.getOrNull() }
                ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }.toMap()
    }

    private fun tierOf(score: Double): String =
        when {
            score >= config.stage3().tierHigh -> "HIGH"
            score >= config.stage3().tierMedium -> "MEDIUM"
            else -> "LOW"
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
        val timeout = config.stage3().phaseTimeout
        if (Instant.now().isBefore(since.plus(timeout))) return null
        log.warn("Run {}: {} stuck since {} — reclaiming to FAILED", run.id, run.status, since)
        return fail(
            run,
            "${run.status} made no progress for ${timeout.toMinutes()}m (since $since) — " +
                "reclaimed; Retry resumes the phase",
        )
    }
}

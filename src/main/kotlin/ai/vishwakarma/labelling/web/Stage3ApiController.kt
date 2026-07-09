package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.persistence.Stage3EntityJournalRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.EntityAdminService
import ai.vishwakarma.labelling.service.Stage3CorpusSeeder
import ai.vishwakarma.labelling.service.Stage3EvalService
import ai.vishwakarma.labelling.service.Stage3Service
import ai.vishwakarma.labelling.stage3.EntityType
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import arrow.core.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Hard cap for the entity-browser list reads (VA-46) — the canon is small at POC scale. */
private const val MAX_ENTITY_PAGE = 500

/** `POST /entities/{id}/merge` body (LLD §10, VA-12). */
data class EntityMergeRequest(val intoId: String? = null)

/** `POST /eval/pairs` body — one golden-pair label (LLD §13, VA-20). */
data class GoldenPairRequest(
    val subjectId: String? = null,
    val claimIdA: String? = null,
    val claimIdB: String? = null,
    val humanRelation: String? = null,
    /** CALIBRATION / TEST; omitted → deterministic hash assignment. */
    val split: String? = null,
)

/**
 * Stage 3 (Claims → Authenticity graph/scores) JSON API — LLD §10. Same conventions as
 * [Stage2ApiController]: same-origin, REVIEWER+, CSRF on, submit-then-poll, error model `{"error":
 * "..."}` with 404/409/400. Scoring/queue/publish endpoints arrive with their phase tickets
 * (VA-17/VA-18).
 */
@RestController
@RequestMapping("/api/stage3")
@PreAuthorize("hasRole('REVIEWER')")
class Stage3ApiController(
    private val stage3: Stage3Service,
    private val graph: Stage3GraphRepository,
    private val corpusSeeder: Stage3CorpusSeeder,
    private val entityAdmin: EntityAdminService,
    private val eval: Stage3EvalService,
    private val entityJournal: Stage3EntityJournalRepository,
) {

    private fun actor(): String? = CurrentUser.email()

    /** Start a run: review-submitted + no-active-run + Neo4j-ping guards, params snapshot. */
    @PostMapping("/subjects/{id}/run")
    fun run(@PathVariable id: String): ResponseEntity<Any> =
        stage3.submit(id, actor()).toResponse(HttpStatus.CREATED)

    /** Advance the run one bounded step (sync / entity batch / embed batch / …). */
    @PostMapping("/runs/{id}/poll")
    fun poll(@PathVariable id: String): ResponseEntity<Any> = stage3.poll(id).toResponse()

    /** FAILED → resume from the failed phase (phases are re-entrant). */
    @PostMapping("/runs/{id}/retry")
    fun retry(@PathVariable id: String): ResponseEntity<Any> = stage3.retry(id).toResponse()

    /**
     * Re-run a PUBLISHED run as a fresh record; `fresh=true` also wipes the subject's evidence
     * layer at SYNC (judge-cache dropping rides this once VA-15 lands).
     */
    @PostMapping("/runs/{id}/rerun")
    fun rerun(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "false") fresh: Boolean,
    ): ResponseEntity<Any> = stage3.rerun(id, fresh, actor()).toResponse()

    /** The subject's latest run (poll-loop + UI read). */
    @GetMapping("/subjects/{id}/run")
    fun latest(@PathVariable id: String): ResponseEntity<Any> =
        stage3.latestForSubject(id)?.let { ResponseEntity.ok<Any>(it) }
            ?: notFound("No Stage 3 run for subject $id")

    @GetMapping("/runs/{id}")
    fun runById(@PathVariable id: String): ResponseEntity<Any> =
        stage3.run(id)?.let { ResponseEntity.ok<Any>(it) } ?: notFound("Run $id")

    /**
     * Scored claims with the full §3.2 vector, term decomposition and judged edge list — the "why
     * this score" panel + the Stage 4 preview read (LLD §10, §21 A.3). Provisional until publish;
     * empty before SCORING has run.
     */
    @GetMapping("/subjects/{id}/scores")
    fun scores(@PathVariable id: String): ResponseEntity<Any> =
        ResponseEntity.ok(graph.scoresReadback(id))

    /** The §11.10 contradiction queue: PROPOSED pair cards with rationale + score impact. */
    @GetMapping("/subjects/{id}/contradictions")
    fun contradictions(@PathVariable id: String): ResponseEntity<Any> =
        ResponseEntity.ok(stage3.contradictions(id))

    /**
     * The §12 timeline read (VA-46): STATE slot lanes + SUCCEEDS chains, EVENT points, off-axis
     * (undated/TIMELESS) facts listed, conflict edge ids for anachronism markers.
     */
    @GetMapping("/subjects/{id}/timeline")
    fun timeline(@PathVariable id: String): ResponseEntity<Any> =
        ResponseEntity.ok(graph.timeline(id))

    /** Entity-browser search (VA-46): type filter + name/alias contains + usage counts. */
    @GetMapping("/entities")
    fun entities(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false, defaultValue = "") q: String,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): ResponseEntity<Any> {
        val entityType =
            type
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    EntityType.fromOrNull(it)?.name
                        ?: return ResponseEntity.badRequest()
                            .body(
                                mapOf(
                                    "error" to
                                        "Unknown entity type '$it' — one of " +
                                            EntityType.entries.joinToString(", ")
                                )
                            )
                }
        return ResponseEntity.ok(
            graph.searchEntities(entityType, q, limit.coerceIn(1, MAX_ENTITY_PAGE))
        )
    }

    /** Entity detail (VA-46): full row + mention rows + this entity's journal history. */
    @GetMapping("/entities/{id}")
    fun entityDetail(@PathVariable id: String): ResponseEntity<Any> {
        val entity = graph.findEntityAdmin(id) ?: return notFound("Entity $id")
        return ResponseEntity.ok(
            mapOf(
                "entity" to entity,
                "mentions" to graph.entityMentionRows(id),
                "journal" to entityJournal.findByEntity(id),
            )
        )
    }

    /** The §11.3 near-miss review list (VA-46): provisional links with claim context. */
    @GetMapping("/entities/review-list")
    fun entityReviewList(
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): ResponseEntity<Any> =
        ResponseEntity.ok(graph.entityReviewList(limit.coerceIn(1, MAX_ENTITY_PAGE)))

    /** Ratify a proposed contradiction — the penalty stands, the edge leaves the queue. */
    @PostMapping("/contradictions/{edgeId}/confirm")
    fun confirmContradiction(@PathVariable edgeId: String): ResponseEntity<Any> =
        stage3.confirmContradiction(edgeId).toResponse()

    /**
     * Reject a judge false-positive: edge deleted, cached verdicts overridden forever, incremental
     * re-score in the same request.
     */
    @PostMapping("/contradictions/{edgeId}/dismiss")
    fun dismissContradiction(@PathVariable edgeId: String): ResponseEntity<Any> =
        stage3.dismissContradiction(edgeId).toResponse()

    /**
     * The explain hook: call after authoring/editing the §12.6 sidecar on an involved claim —
     * re-judges the pair with context, sets `explained` on affirmed relevance, re-scores.
     */
    @PostMapping("/contradictions/{edgeId}/rejudge")
    fun rejudgeContradiction(@PathVariable edgeId: String): ResponseEntity<Any> =
        stage3.rejudgeContradiction(edgeId).toResponse()

    /**
     * The Q6 gate (LLD §11.10): write the ledger from AWAITING_REVIEW. Refused while PROPOSED
     * contradictions remain unless `skipReview=true` (recorded as the audit cost of skipping).
     */
    @PostMapping("/runs/{id}/publish")
    fun publish(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "false") skipReview: Boolean,
    ): ResponseEntity<Any> = stage3.publish(id, skipReview, actor()).toResponse()

    /** ADMIN: PUBLISHED → AWAITING_REVIEW; the ledger keeps the last-published values. */
    @PostMapping("/runs/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    fun reopen(@PathVariable id: String): ResponseEntity<Any> = stage3.reopen(id).toResponse()

    /**
     * ADMIN, dry-run only (LLD §11.12): seed the sample corpus as a review-submitted subject so a
     * dev run reproduces the §11.8 worked example end-to-end. Idempotent — fixed ids, wholesale
     * replace; refused while the corpus subject has an active run.
     */
    @PostMapping("/dev/seed-corpus")
    @PreAuthorize("hasRole('ADMIN')")
    fun seedCorpus(): ResponseEntity<Any> =
        corpusSeeder.seed(actor()).toResponse(HttpStatus.CREATED)

    /**
     * ADMIN (LLD §11.3 "Human repair", §21 A.2): merge entity `{id}` into `body.intoId` — MENTIONS
     * rewired in batches, aliases unioned, tombstone redirect left behind, journaled. 409 when
     * either side is already a tombstone or the types differ.
     */
    @PostMapping("/entities/{id}/merge")
    @PreAuthorize("hasRole('ADMIN')")
    fun mergeEntity(
        @PathVariable id: String,
        @RequestBody body: EntityMergeRequest,
    ): ResponseEntity<Any> {
        val intoId =
            body.intoId?.trim()?.takeIf { it.isNotBlank() }
                ?: return ResponseEntity.badRequest().body(mapOf("error" to "intoId is required"))
        return entityAdmin.merge(id, intoId, actor()).toResponse()
    }

    /**
     * ADMIN (LLD §11.3): split entity `{id}` — its mentions re-resolve with the entity excluded
     * (exact → kNN bands → mint); only surfaces that re-resolve to it stay. Journaled with the
     * affected subject ids so stale MATCH blocking can be re-run by hand.
     */
    @PostMapping("/entities/{id}/split")
    @PreAuthorize("hasRole('ADMIN')")
    fun splitEntity(@PathVariable id: String): ResponseEntity<Any> =
        entityAdmin.split(id, actor()).toResponse()

    // ---- Eval harness (LLD §13, VA-20) --------------------------------------------

    /** Record one golden-pair label (the queue/row-expand/eval-page ride-alongs). */
    @PostMapping("/eval/pairs")
    fun labelGoldenPair(@RequestBody body: GoldenPairRequest): ResponseEntity<Any> =
        eval
            .label(
                subjectId = body.subjectId.orEmpty(),
                claimIdA = body.claimIdA.orEmpty(),
                claimIdB = body.claimIdB.orEmpty(),
                humanRelation = body.humanRelation.orEmpty(),
                split = body.split,
                actor = actor(),
            )
            .toResponse(HttpStatus.CREATED)

    /**
     * The "label N random pairs" feed — stratified ~40% judge-queue hard cases / ~40% blocking
     * survivors / ~20% blocking-discarded probes; already-labeled pairs are never re-served.
     */
    @GetMapping("/eval/pairs/sample")
    fun sampleGoldenPairs(
        @RequestParam subjectId: String,
        @RequestParam(required = false, defaultValue = "10") n: Int,
    ): ResponseEntity<Any> = eval.samplePairs(subjectId, n).toResponse()

    /**
     * Run the §13 metrics job for the CURRENT judge prompt + params: judge P/R/F1 + confusion on
     * the TEST split, blocking recall, the calibration curve, and (when `referenceSubjectId` is
     * given) the score-sanity flags. Persisted per `promptStamp|paramsHash`.
     */
    @PostMapping("/eval/run")
    fun runEvalMetrics(
        @RequestParam(required = false) referenceSubjectId: String?,
    ): ResponseEntity<Any> = eval.runMetrics(referenceSubjectId, actor()).toResponse()

    /** Every persisted metrics record, newest first — the cross-version comparison read. */
    @GetMapping("/eval/metrics")
    fun evalMetrics(): ResponseEntity<Any> = ResponseEntity.ok(eval.allMetrics())

    /**
     * Connectivity diagnostic — verifies the service can reach the configured Neo4j (AuraDB in
     * prod: plain TLS egress, no VPC path) and optionally sweeps the §21 A.4 layer-boundary guards
     * (`?guards=true`; each count must be 0). Deploy-time smoke: call this once after wiring the
     * NEO4J_* secrets.
     */
    @GetMapping("/graph/health")
    fun graphHealth(
        @RequestParam(required = false, defaultValue = "false") guards: Boolean,
    ): ResponseEntity<Any> {
        val ping = graph.ping()
        val body = mutableMapOf<String, Any?>("ping" to ping)
        if (ping.reachable && guards)
            body["layerGuardViolations"] =
                runCatching { graph.layerGuardViolations() }
                    .getOrElse { mapOf("error" to it.message) }
        return if (ping.reachable) ResponseEntity.ok(body)
        else ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun notFound(what: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "$what not found"))

    private fun Either<DomainError, Any>.toResponse(
        okStatus: HttpStatus = HttpStatus.OK
    ): ResponseEntity<Any> =
        fold(
            { err -> errorResponse(err) },
            { value -> ResponseEntity.status(okStatus).body(value) }
        )

    private fun errorResponse(err: DomainError): ResponseEntity<Any> {
        val status =
            when (err) {
                is DomainError.NotFound -> HttpStatus.NOT_FOUND
                is DomainError.Conflict -> HttpStatus.CONFLICT
                is DomainError.Invalid -> HttpStatus.BAD_REQUEST
            }
        return ResponseEntity.status(status).body(mapOf("error" to err.message))
    }
}

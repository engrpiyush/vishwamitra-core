package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ExampleSource
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.ResolvedPersona
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4Counters
import ai.vishwakarma.labelling.domain.Stage4Plan
import ai.vishwakarma.labelling.domain.Stage4Run
import ai.vishwakarma.labelling.domain.Stage4RunStatus
import ai.vishwakarma.labelling.domain.Stage4Stamp
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.DpoPairRepository
import ai.vishwakarma.labelling.persistence.PublishContract
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.persistence.Stage4PlanRepository
import ai.vishwakarma.labelling.persistence.Stage4RunRepository
import ai.vishwakarma.labelling.persistence.SubjectFactRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.stage4.EvidencedClaim
import ai.vishwakarma.labelling.stage4.Stage4ConversationDrafter
import ai.vishwakarma.labelling.stage4.Stage4Generation
import ai.vishwakarma.labelling.stage4.Stage4GenerationRequest
import ai.vishwakarma.labelling.stage4.Stage4Planning
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * `POST /api/stage4/subjects/{id}/run` body: the `fresh` full-regeneration flag (QA-6) plus the
 * per-run mix-weight overrides (QA-3). Null dials inherit the `app.stage4.mix` defaults; the
 * effective values are frozen into the run's paramsSnapshot at submit.
 */
data class Stage4SubmitRequest(val fresh: Boolean = false, val mix: MixOverrides? = null) {
    data class MixOverrides(
        val qa: Double? = null,
        val situational: Double? = null,
        val multiClaim: Double? = null,
        val negative: Double? = null,
        val meta: Double? = null,
    )
}

/**
 * Stage 4 (published ledger → conversation notebooks) — the run lifecycle chassis (LLD §9): the
 * Stage 2/3 submit-then-poll idiom exactly. No scheduler — the run advances only inside poll
 * requests, one bounded step each; failures are terminal FAILED states carrying the verbatim error
 * (Retry resumes — phases are re-entrant); a phase making no progress past `app.stage4
 * .phase-timeout` is reclaimed to FAILED on the next poll; REVIEW_WAIT parks the run at the QA-4
 * 100%-human-review gate (export moves it on, never the poll loop).
 *
 * Phases in this slice: SELECT (§9.1 — guards, eligible set, QA-6 drift sweep), PLAN (§9.2 —
 * [Stage4Planning] over the frozen params) and GENERATE (§9.3 — bounded drafter batches through the
 * plan-keyed generation cache, VA-56). JUDGE advances as a no-op until VA-57 lands its body.
 */
@Service
class Stage4Service(
    private val runs: Stage4RunRepository,
    private val subjects: SubjectRepository,
    private val subjectScores: SubjectScoreRepository,
    private val subjectFacts: SubjectFactRepository,
    private val claimLedger: ClaimRepository,
    private val claimReviews: ClaimReviewRepository,
    private val reviewService: ClaimReviewService,
    private val personaService: PersonaService,
    private val prompts: ExtractionPromptService,
    private val drafter: Stage4ConversationDrafter,
    private val plans: Stage4PlanRepository,
    private val sftExamples: SftExampleRepository,
    private val dpoPairs: DpoPairRepository,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(Stage4Service::class.java)

    private val planning = Stage4Planning()

    // ---- submit -----------------------------------------------------------------

    /**
     * Start a run. Guards: Stage 4 enabled, subject exists, no active run — except a parked
     * REVIEW_WAIT run, which the newer run retires as SUPERSEDED (QA-6: it never exported, so
     * nothing references it). The ledger guards (§9.1) run inside SELECT so their verbatim reasons
     * land on the run record. Effective params — including the QA-3 mix overrides and `fresh` — are
     * frozen into the paramsSnapshot at this instant.
     */
    fun submit(
        subjectId: String,
        request: Stage4SubmitRequest,
        actor: String?,
    ): Either<DomainError, Stage4Run> {
        if (!props.stage4.enabled)
            return DomainError.Conflict("Stage 4 is disabled (app.stage4.enabled)").left()
        subjects.findById(subjectId)
            ?: return DomainError.NotFound("Subject $subjectId not found").left()
        val overrides = request.mix
        if (overrides != null) {
            val negative =
                listOfNotNull(
                        overrides.qa,
                        overrides.situational,
                        overrides.multiClaim,
                        overrides.negative,
                        overrides.meta,
                    )
                    .any { it < 0.0 }
            if (negative)
                return DomainError.Invalid("Mix-weight overrides must be non-negative").left()
        }
        val now = Instant.now()
        runs.findActiveBySubject(subjectId)?.let { active ->
            if (active.status != Stage4RunStatus.REVIEW_WAIT)
                return DomainError.Conflict(
                        "A Stage 4 run is already active (${active.id}: ${active.status})"
                    )
                    .left()
            // Retire the parked run before creating its replacement — a crash between the two
            // saves leaves the subject unblocked rather than with two active runs.
            runs.save(active.copy(status = Stage4RunStatus.SUPERSEDED, finishedAt = now))
            log.info("Stage 4 run {} superseded by a newer submit", active.id)
        }
        val effective = effectiveStage4(overrides)
        val run =
            Stage4Run(
                id = runs.newId(),
                subjectId = subjectId,
                fresh = request.fresh,
                paramsSnapshot = snapshotOf(effective, request.fresh),
                createdBy = actor,
                createdAt = now,
                phaseSince = now,
            )
        runs.save(run)
        log.info("Stage 4 run {} created for subject {} (fresh={})", run.id, subjectId, run.fresh)
        return run.right()
    }

    // ---- poll (one bounded step) ---------------------------------------------------

    /**
     * Advance the run exactly one bounded step. Terminal runs and the REVIEW_WAIT park are no-ops;
     * an in-phase failure marks the run FAILED with the verbatim error.
     */
    fun poll(runId: String): Either<DomainError, Stage4Run> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status.terminal) return run.right()
        // The QA-4 gate: the operator's export completes the run, never the poll loop.
        if (run.status == Stage4RunStatus.REVIEW_WAIT) return run.right()
        reclaimIfStuck(run)?.let {
            return it.right()
        }
        return when (run.status) {
            Stage4RunStatus.PENDING -> runSelect(enterPhase(run, Stage4RunStatus.SELECTING))
            Stage4RunStatus.SELECTING -> runSelect(run) // re-entrant after a crashed poll
            Stage4RunStatus.PLANNING -> runPlan(run)
            Stage4RunStatus.GENERATING -> runGenerate(run)
            Stage4RunStatus.JUDGING -> runJudge(run)
            else -> run
        }.right()
    }

    /** Operator retry: FAILED → resume in the phase that failed (phases are re-entrant). */
    fun retry(runId: String): Either<DomainError, Stage4Run> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status != Stage4RunStatus.FAILED)
            return DomainError.Conflict("Only FAILED runs can be retried (run is ${run.status})")
                .left()
        val resumed =
            run.copy(
                status = run.failedPhase ?: Stage4RunStatus.PENDING,
                failedPhase = null,
                error = null,
                finishedAt = null,
                phaseSince = Instant.now(),
            )
        runs.save(resumed)
        return resumed.right()
    }

    // ---- reads ------------------------------------------------------------------

    fun run(runId: String): Stage4Run? = runs.findById(runId)

    fun latestForSubject(subjectId: String): Stage4Run? =
        runs.findBySubject(subjectId).firstOrNull()

    // ---- SELECT (LLD §9.1, VA-54) -------------------------------------------------

    /**
     * Guards (each failing the run with its verbatim reason): a published `subject_scores` doc at
     * contract ≥ v2, and publish-consistency — every scored claim and every `subject_facts` doc
     * carries the frozen `scoreRunId` (§4 consumer rule). The persona resolves defaults-only when
     * nothing is stored (§9.1) and its hash is frozen onto the run beside the scoreRunId. The QA-6
     * drift sweep then archives every stamped example whose scoreRunId/personaHash mismatch the
     * run's — their generation-cache keys stay valid, so unchanged claims regenerate for free.
     */
    private fun runSelect(run: Stage4Run): Stage4Run =
        inPhase(run, "SELECT") {
            val scores =
                subjectScores.find(run.subjectId)
                    ?: error(
                        "no published subject_scores for subject ${run.subjectId} — Stage 4 " +
                            "consumes only published, publish-consistent ledgers (LLD §4)"
                    )
            check(scores.publishContractVersion >= PublishContract.VERSION) {
                "publish contract v${scores.publishContractVersion} < " +
                    "v${PublishContract.VERSION} — reopen and re-publish the subject to " +
                    "upgrade its ledger (§4 consumer rule 2)"
            }
            val scoreRunId = scores.scoreRunId
            val staleClaims =
                claimLedger
                    .findBySubject(run.subjectId)
                    .filter { it.authenticityScore != null || it.scoreRunId != null }
                    .filter {
                        it.scoreRunId != scoreRunId ||
                            (it.publishContractVersion ?: 1) < PublishContract.VERSION
                    }
            check(staleClaims.isEmpty()) {
                "publish-consistency violated: ${staleClaims.size} scored claim(s) (e.g. " +
                    "${staleClaims.first().id}) do not carry scoreRunId $scoreRunId at " +
                    "contract v${PublishContract.VERSION} — re-publish the subject " +
                    "(§4 consumer rule)"
            }
            val staleFacts =
                subjectFacts.findBySubject(run.subjectId).filter { it.scoreRunId != scoreRunId }
            check(staleFacts.isEmpty()) {
                "publish-consistency violated: ${staleFacts.size} subject_facts doc(s) (e.g. " +
                    "${staleFacts.first().factId}) do not carry scoreRunId $scoreRunId — " +
                    "re-publish the subject (§4 consumer rule)"
            }
            val personaHash = personaService.resolved(run.subjectId).hash()
            val eligible = eligibleClaims(run.subjectId, scoreRunId)
            val archived = driftSweep(run.subjectId, scoreRunId, personaHash)
            advance(
                run.copy(scoreRunId = scoreRunId, personaHash = personaHash),
                Stage4RunStatus.PLANNING,
                mapOf(
                    Stage4Counters.CLAIMS_ELIGIBLE to eligible.size.toLong(),
                    Stage4Counters.EXAMPLES_ARCHIVED to archived,
                ),
            )
        }

    /**
     * The §9.1 eligible set: approved-for-downstream claims (CONTESTED and hidden-PII never enter —
     * [ClaimReviewService.approvedForDownstream] is that gate) that are scored, current and at
     * contract v2, paired with their review layer for the planner's accessors.
     */
    private fun eligibleClaims(subjectId: String, scoreRunId: String): List<EvidencedClaim> {
        val reviewMap = claimReviews.findBySubject(subjectId).associateBy { it.claimId }
        return reviewService
            .approvedForDownstream(subjectId)
            .map { it.claim }
            .filter {
                it.authenticityScore != null &&
                    it.scoreRunId == scoreRunId &&
                    (it.publishContractVersion ?: 1) >= PublishContract.VERSION
            }
            .sortedBy { it.id }
            .map { EvidencedClaim(it, reviewMap[it.id]) }
    }

    /** QA-6: archive stamped examples/pairs whose publish or persona drifted; returns the count. */
    private fun driftSweep(subjectId: String, scoreRunId: String, personaHash: String): Long {
        val now = Instant.now()
        var archived = 0L
        sftExamples.findByStampSubject(subjectId).forEach { example ->
            if (example.status == ExampleStatus.ARCHIVED) return@forEach
            val stamp = example.stamp ?: return@forEach
            val reason = driftReason(stamp, scoreRunId, personaHash) ?: return@forEach
            sftExamples.save(
                example.copy(
                    status = ExampleStatus.ARCHIVED,
                    archivedReason = reason,
                    updatedAt = now,
                )
            )
            archived++
        }
        dpoPairs.findByStampSubject(subjectId).forEach { pair ->
            if (pair.status == ExampleStatus.ARCHIVED) return@forEach
            val stamp = pair.stamp ?: return@forEach
            val reason = driftReason(stamp, scoreRunId, personaHash) ?: return@forEach
            dpoPairs.save(
                pair.copy(status = ExampleStatus.ARCHIVED, archivedReason = reason, updatedAt = now)
            )
            archived++
        }
        if (archived > 0)
            log.info("Subject {}: drift sweep archived {} stale example(s)", subjectId, archived)
        return archived
    }

    private fun driftReason(stamp: Stage4Stamp, scoreRunId: String, personaHash: String): String? {
        val drifted = buildList {
            if (stamp.scoreRunId != scoreRunId) add("scoreRunId $scoreRunId")
            if (stamp.personaHash != personaHash) add("personaHash ${personaHash.take(12)}")
        }
        return drifted.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "superseded by ")
    }

    // ---- PLAN (LLD §9.2, VA-55) -----------------------------------------------------

    /**
     * One tick, LLM-free: re-derive the eligible set (SELECT's guards already held; recomputation
     * is the "store is the cursor" idiom), verify nothing drifted mid-run, and run [Stage4Planning]
     * under the paramsSnapshot's frozen mix/cap/threshold. Plans are content-addressed (doc id =
     * planId), so re-planning an unchanged subject overwrites identical docs.
     */
    private fun runPlan(run: Stage4Run): Stage4Run =
        inPhase(run, "PLAN") {
            val scoreRunId =
                checkNotNull(run.scoreRunId) { "run carries no frozen scoreRunId — SELECT first" }
            val personaHash =
                checkNotNull(run.personaHash) { "run carries no frozen personaHash — SELECT first" }
            val currentScoreRun = subjectScores.find(run.subjectId)?.scoreRunId
            check(currentScoreRun == scoreRunId) {
                "publish drifted mid-run (frozen $scoreRunId, ledger $currentScoreRun) — " +
                    "resubmit the run against the new publish"
            }
            val persona = personaService.resolved(run.subjectId)
            check(persona.hash() == personaHash) {
                "persona drifted mid-run (frozen ${personaHash.take(12)}, now " +
                    "${persona.hash().take(12)}) — resubmit the run"
            }
            val params = frozenParams(run)
            val outcome =
                planning.plan(
                    subjectName = subjects.findById(run.subjectId)?.displayName ?: "the subject",
                    eligible = eligibleClaims(run.subjectId, scoreRunId),
                    facts =
                        subjectFacts.findBySubject(run.subjectId).filter {
                            it.scoreRunId == scoreRunId
                        },
                    persona = persona.plannerView(),
                    personaHash = personaHash,
                    mix = params.mix,
                    maxConversationsPerClaim = params.maxConversationsPerClaim,
                    dedupeJaccardThreshold = params.dedupeJaccardThreshold,
                )
            val now = Instant.now()
            outcome.planned.forEach {
                plans.save(
                    Stage4Plan(
                        subjectId = run.subjectId,
                        scoreRunId = scoreRunId,
                        personaHash = personaHash,
                        question = it.question,
                        plan = it.plan,
                        createdAt = now,
                    )
                )
            }
            advance(
                run,
                Stage4RunStatus.GENERATING,
                mapOf(
                    Stage4Counters.PLANS to outcome.planned.size.toLong(),
                    Stage4Counters.PLANS_DEDUPED to outcome.deduped.toLong(),
                    Stage4Counters.PLANS_CAPPED to outcome.capped.toLong(),
                ),
            )
        }

    // ---- GENERATE (LLD §9.3, VA-56) ---------------------------------------------------

    /**
     * One bounded tick. The store is the cursor: a plan is *satisfied* when a live (non-ARCHIVED)
     * stamped example carries its planId at the category's current generator-prompt hash — so a
     * killed poll resumes free, a prompt bump re-drafts exactly the affected plans, and a re-run of
     * an unchanged subject is 100% cache hits with zero LLM calls. Counters are recomputed
     * absolutely each tick (re-entrant, never double-counted). Row-5 plans (sftEligible=false)
     * never generate — they are DPO rejected-side candidates (VA-61).
     */
    private fun runGenerate(run: Stage4Run): Stage4Run =
        inPhase(run, "GENERATE") {
            val scoreRunId =
                checkNotNull(run.scoreRunId) { "run carries no frozen scoreRunId — SELECT first" }
            val personaHash =
                checkNotNull(run.personaHash) { "run carries no frozen personaHash — SELECT first" }
            val persona = personaService.resolved(run.subjectId)
            val presetStyle = prompts.resolveStage4Preset(persona.presetId).instructions
            val subjectName = subjects.findById(run.subjectId)?.displayName ?: "the subject"
            val promptByCategory =
                Stage4Category.entries
                    .filter { it != Stage4Category.META }
                    .associateWith { prompts.resolveStage4Generator(it) }
            fun promptHashFor(category: Stage4Category): String =
                if (category == Stage4Category.META) Stage4Generation.META_TEMPLATE_STAMP
                else promptByCategory.getValue(category).hash

            val subjectPlans =
                plans
                    .findBySubject(run.subjectId)
                    .filter {
                        it.scoreRunId == scoreRunId &&
                            it.personaHash == personaHash &&
                            it.plan.sftEligible
                    }
                    .sortedBy { it.plan.planId }
            val evidenceById = eligibleClaims(run.subjectId, scoreRunId).associateBy { it.claim.id }

            // QA-6 `fresh`: pre-run live matches are archived so every plan re-drafts. Bounded to
            // pre-run examples (createdAt < startedAt), so the sweep is idempotent across ticks.
            if (run.fresh) {
                val planIds = subjectPlans.map { it.plan.planId }.toSet()
                sftExamples
                    .findByStampSubject(run.subjectId)
                    .filter {
                        it.status != ExampleStatus.ARCHIVED &&
                            it.stamp?.planId?.let { id -> id in planIds } == true &&
                            (it.createdAt ?: Instant.EPOCH).isBefore(run.startedAt ?: Instant.now())
                    }
                    .forEach {
                        sftExamples.save(
                            it.copy(
                                status = ExampleStatus.ARCHIVED,
                                archivedReason = "fresh regeneration (run ${run.id})",
                                updatedAt = Instant.now(),
                            )
                        )
                    }
            }

            val stamped = sftExamples.findByStampSubject(run.subjectId)
            val liveByPlan =
                stamped
                    .filter { it.status != ExampleStatus.ARCHIVED && it.stamp?.planId != null }
                    .groupBy { it.stamp!!.planId!! }
            fun satisfied(p: Stage4Plan): Boolean =
                liveByPlan[p.plan.planId].orEmpty().any {
                    it.stamp?.generatorPromptHash == promptHashFor(p.plan.category)
                }

            val pending = subjectPlans.filterNot(::satisfied)
            if (pending.isEmpty()) {
                return@inPhase advance(run, Stage4RunStatus.JUDGING, generateCounters(run))
            }

            val batch = pending.take(frozenParams(run).generateBatchPerPoll)
            batch.forEach { p ->
                generateOne(
                    run,
                    p,
                    subjectName,
                    persona,
                    presetStyle,
                    promptByCategory,
                    evidenceById
                )
            }
            run.copy(counters = run.counters + generateCounters(run), phaseSince = Instant.now())
                .also { runs.save(it) }
        }

    /** Produce the one example plan [p] is missing: cache copy, meta render, or LLM draft. */
    private fun generateOne(
        run: Stage4Run,
        p: Stage4Plan,
        subjectName: String,
        persona: ResolvedPersona,
        presetStyle: String,
        promptByCategory: Map<Stage4Category, ResolvedExtractionPrompt>,
        evidenceById: Map<String, EvidencedClaim>,
    ) {
        val category = p.plan.category
        val row = promptByCategory[category]
        val promptHash = row?.hash ?: Stage4Generation.META_TEMPLATE_STAMP
        val now = Instant.now()

        // A live example on this plan at a *different* prompt hash is stale-by-prompt-bump:
        // archive it before the re-draft (drift sweeps only cover scoreRunId/personaHash).
        sftExamples
            .findByStampSubject(run.subjectId)
            .filter {
                it.status != ExampleStatus.ARCHIVED &&
                    it.stamp?.planId == p.plan.planId &&
                    it.stamp?.generatorPromptHash != promptHash
            }
            .forEach {
                sftExamples.save(
                    it.copy(
                        status = ExampleStatus.ARCHIVED,
                        archivedReason = "superseded by generator prompt $promptHash",
                        updatedAt = now,
                    )
                )
            }

        // Generation cache (§9.3): an ARCHIVED example with the same planId + prompt hash carries
        // turns produced from identical inputs — copy them into a new DRAFT, no LLM call. The
        // `fresh` flag bypasses.
        val cached =
            if (run.fresh) null
            else
                sftExamples
                    .findByStampSubject(run.subjectId)
                    .filter {
                        it.status == ExampleStatus.ARCHIVED &&
                            it.stamp?.planId == p.plan.planId &&
                            it.stamp?.generatorPromptHash == promptHash &&
                            it.turns.isNotEmpty()
                    }
                    .maxByOrNull { it.updatedAt ?: Instant.EPOCH }

        val stamp =
            Stage4Stamp(
                subjectId = run.subjectId,
                sourceClaimIds = p.plan.sourceClaimIds,
                scoreRunId = run.scoreRunId,
                category = category,
                planId = p.plan.planId,
                personaHash = run.personaHash,
                generatorPromptHash = promptHash,
            )
        val tags = ExampleTags(labels = listOf("stage4:${category.name.lowercase()}"))

        val (turns, llmModel, createdBy) =
            when {
                cached != null -> Triple(cached.turns, cached.llmModel, "stage4-cache:${run.id}")
                category == Stage4Category.META ->
                    Triple(
                        Stage4Generation.renderMeta(
                            metaRequest(p, subjectName, persona, presetStyle)
                        ),
                        null,
                        "stage4:${run.id}",
                    )
                else ->
                    Triple(
                        drafter.draft(
                            Stage4GenerationRequest(
                                subjectName = subjectName,
                                question = p.question ?: "Tell me about $subjectName.",
                                plan = p.plan,
                                persona = persona,
                                presetStyle = presetStyle,
                                evidence =
                                    p.plan.sourceClaimIds.mapNotNull { id ->
                                        evidenceById[id]?.let { evidenceLine(it) }
                                    },
                                promptInstructions = checkNotNull(row).instructions,
                                promptVersion = row.version,
                                promptHash = row.hash,
                            )
                        ),
                        drafter.model,
                        "stage4:${run.id}",
                    )
            }
        sftExamples.save(
            SftExample(
                id = sftExamples.newId(),
                tags = tags,
                turns = turns,
                status = ExampleStatus.DRAFT,
                source = ExampleSource.LLM,
                llmModel = llmModel,
                stamp = stamp,
                createdBy = createdBy,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun metaRequest(
        p: Stage4Plan,
        subjectName: String,
        persona: ResolvedPersona,
        presetStyle: String,
    ): Stage4GenerationRequest =
        Stage4GenerationRequest(
            subjectName = subjectName,
            question = p.question ?: "Who am I speaking with?",
            plan = p.plan,
            persona = persona,
            presetStyle = presetStyle,
            evidence = emptyList(),
            promptInstructions = "",
            promptVersion = 0,
            promptHash = Stage4Generation.META_TEMPLATE_STAMP,
        )

    /**
     * One §9.3 evidence line: the claim text with its score, explanation context and F5-precision
     * dates — everything the prompt may ground on, nothing it may not.
     */
    private fun evidenceLine(e: EvidencedClaim): String = buildString {
        append("[${e.claim.id}] \"${e.claim.text}\" — score ")
        append("%.2f".format(e.score))
        e.claim.authenticityTier?.let { append(" (${it.name})") }
        e.sidecar?.let { append("; explanation on record: \"$it\"") }
        e.claim.factStamp?.let { fs ->
            val dates = listOfNotNull(fs.validFrom, fs.validTo).distinct()
            if (dates.isNotEmpty()) {
                append("; dated ${dates.joinToString(" → ")}")
                fs.datePrecision?.let { append(" ($it precision — never voice finer)") }
            }
        }
    }

    /**
     * Absolute GENERATE counters, recomputed from the store each tick: [Stage4Counters.GENERATED] =
     * plans satisfied by a live stamped example; [Stage4Counters.CACHE_HITS] = those satisfied
     * without an LLM call by this run (pre-run survivors and cache copies).
     */
    private fun generateCounters(run: Stage4Run): Map<String, Long> {
        val scoreRunId = run.scoreRunId ?: return emptyMap()
        val personaHash = run.personaHash ?: return emptyMap()
        val live =
            sftExamples
                .findByStampSubject(run.subjectId)
                .filter {
                    it.status != ExampleStatus.ARCHIVED &&
                        it.stamp?.scoreRunId == scoreRunId &&
                        it.stamp?.personaHash == personaHash &&
                        it.stamp?.planId != null
                }
                .distinctBy { it.stamp!!.planId }
        val started = run.startedAt ?: Instant.EPOCH
        val cacheHits =
            live.count { example ->
                (example.createdAt ?: Instant.EPOCH).isBefore(started) ||
                    example.createdBy?.startsWith("stage4-cache:") == true
            }
        return mapOf(
            Stage4Counters.GENERATED to live.size.toLong(),
            Stage4Counters.CACHE_HITS to cacheHits.toLong(),
        )
    }

    // ---- JUDGE — skeleton no-op until VA-57 lands its body -------------------------------

    private fun runJudge(run: Stage4Run): Stage4Run =
        inPhase(run, "JUDGE") { advance(run, Stage4RunStatus.REVIEW_WAIT, emptyMap()) }

    // ---- params snapshot ------------------------------------------------------------

    private fun effectiveStage4(
        overrides: Stage4SubmitRequest.MixOverrides?
    ): AppProperties.Stage4 {
        val base = props.stage4
        if (overrides == null) return base
        return base.copy(
            mix =
                AppProperties.Stage4.Mix(
                    qa = overrides.qa ?: base.mix.qa,
                    situational = overrides.situational ?: base.mix.situational,
                    multiClaim = overrides.multiClaim ?: base.mix.multiClaim,
                    negative = overrides.negative ?: base.mix.negative,
                    meta = overrides.meta ?: base.mix.meta,
                )
        )
    }

    /** Every effective `app.stage4.*` value plus `fresh`, as a parseable JSON map (§3.2 audit). */
    private fun snapshotOf(effective: AppProperties.Stage4, fresh: Boolean): String =
        Json.writeLine(
            mapOf(
                "enabled" to effective.enabled,
                "dryRun" to effective.dryRun,
                "mix" to
                    mapOf(
                        "qa" to effective.mix.qa,
                        "situational" to effective.mix.situational,
                        "multiClaim" to effective.mix.multiClaim,
                        "negative" to effective.mix.negative,
                        "meta" to effective.mix.meta,
                    ),
                "maxConversationsPerClaim" to effective.maxConversationsPerClaim,
                "dedupeJaccardThreshold" to effective.dedupeJaccardThreshold,
                "generateBatchPerPoll" to effective.generateBatchPerPoll,
                "judgeBatchPerPoll" to effective.judgeBatchPerPoll,
                "ensembleK" to effective.ensembleK,
                "reviewSampleRate" to effective.reviewSampleRate,
                "dpoEnabled" to effective.dpoEnabled,
                "evalHoldoutFraction" to effective.evalHoldoutFraction,
                "evalBehaviorBar" to effective.evalBehaviorBar,
                "phaseTimeout" to effective.phaseTimeout.toString(),
                "fresh" to fresh,
            )
        )

    private data class FrozenParams(
        val mix: AppProperties.Stage4.Mix,
        val maxConversationsPerClaim: Int,
        val dedupeJaccardThreshold: Double,
        val generateBatchPerPoll: Int,
    )

    /**
     * The phase-relevant values back out of the snapshot — per-run mix overrides live *only* there,
     * so phases must read the frozen copy, not the live props (which remain the fallback for
     * pre-snapshot/corrupt runs).
     */
    private fun frozenParams(run: Stage4Run): FrozenParams {
        @Suppress("UNCHECKED_CAST")
        val raw =
            run.paramsSnapshot
                ?.let { runCatching { Json.parse(it) as? Map<String, Any?> }.getOrNull() }
                .orEmpty()

        @Suppress("UNCHECKED_CAST") val mixRaw = raw["mix"] as? Map<String, Any?> ?: emptyMap()
        fun mixOf(key: String, fallback: Double): Double =
            (mixRaw[key] as? Number)?.toDouble() ?: fallback
        val base = props.stage4
        return FrozenParams(
            mix =
                AppProperties.Stage4.Mix(
                    qa = mixOf("qa", base.mix.qa),
                    situational = mixOf("situational", base.mix.situational),
                    multiClaim = mixOf("multiClaim", base.mix.multiClaim),
                    negative = mixOf("negative", base.mix.negative),
                    meta = mixOf("meta", base.mix.meta),
                ),
            maxConversationsPerClaim =
                (raw["maxConversationsPerClaim"] as? Number)?.toInt()
                    ?: base.maxConversationsPerClaim,
            dedupeJaccardThreshold =
                (raw["dedupeJaccardThreshold"] as? Number)?.toDouble()
                    ?: base.dedupeJaccardThreshold,
            generateBatchPerPoll =
                (raw["generateBatchPerPoll"] as? Number)?.toInt() ?: base.generateBatchPerPoll,
        )
    }

    // ---- lifecycle helpers ---------------------------------------------------------

    private fun enterPhase(run: Stage4Run, phase: Stage4RunStatus): Stage4Run =
        run.copy(
                status = phase,
                startedAt = run.startedAt ?: Instant.now(),
                phaseSince = Instant.now(),
            )
            .also { runs.save(it) }

    private fun advance(
        run: Stage4Run,
        to: Stage4RunStatus,
        counterUpdates: Map<String, Long>,
    ): Stage4Run =
        run.copy(
                status = to,
                counters = run.counters + counterUpdates,
                phaseSince = Instant.now(),
            )
            .also { runs.save(it) }
            .also { log.info("Run {}: {} → {}", run.id, run.status, to) }

    /**
     * Run one phase step, converting any exception into the terminal FAILED state with the verbatim
     * error — Retry resumes from [Stage4Run.failedPhase].
     */
    private fun inPhase(run: Stage4Run, phase: String, work: () -> Stage4Run): Stage4Run =
        try {
            work()
        } catch (e: Exception) {
            log.warn("Run {}: {} failed — {}", run.id, phase, e.message)
            fail(run, "$phase: ${e.message}")
        }

    private fun fail(run: Stage4Run, message: String): Stage4Run =
        run.copy(
                status = Stage4RunStatus.FAILED,
                failedPhase = run.status,
                error = message,
                finishedAt = Instant.now(),
            )
            .also { runs.save(it) }

    /**
     * The Stage 2 §12.7 reclaim: a phase with no successful advance for `phase-timeout` is failed
     * on the next poll so the operator can Retry. PENDING is exempt — it advances the moment it is
     * polled.
     */
    private fun reclaimIfStuck(run: Stage4Run): Stage4Run? {
        if (run.status == Stage4RunStatus.PENDING) return null
        val since = run.phaseSince ?: return null
        val timeout = props.stage4.phaseTimeout
        if (Instant.now().isBefore(since.plus(timeout))) return null
        log.warn("Run {}: {} stuck since {} — reclaiming to FAILED", run.id, run.status, since)
        return fail(
            run,
            "${run.status} made no progress for ${timeout.toMinutes()}m (since $since) — " +
                "reclaimed; Retry resumes the phase",
        )
    }
}

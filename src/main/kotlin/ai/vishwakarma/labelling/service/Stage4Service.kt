package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ExampleSource
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.ExportRecord
import ai.vishwakarma.labelling.domain.JudgeVerdict
import ai.vishwakarma.labelling.domain.ResolvedPersona
import ai.vishwakarma.labelling.domain.ResolvedSubjectProfile
import ai.vishwakarma.labelling.domain.ReviewComment
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4Counters
import ai.vishwakarma.labelling.domain.Stage4Judgment
import ai.vishwakarma.labelling.domain.Stage4Plan
import ai.vishwakarma.labelling.domain.Stage4Run
import ai.vishwakarma.labelling.domain.Stage4RunStatus
import ai.vishwakarma.labelling.domain.Stage4Stamp
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.DpoPairRepository
import ai.vishwakarma.labelling.persistence.PublishContract
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.persistence.Stage4JudgmentRepository
import ai.vishwakarma.labelling.persistence.Stage4PlanRepository
import ai.vishwakarma.labelling.persistence.Stage4RunRepository
import ai.vishwakarma.labelling.persistence.SubjectFactRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.stage4.EvidencedClaim
import ai.vishwakarma.labelling.stage4.HedgeVerdict
import ai.vishwakarma.labelling.stage4.SituationalEvidence
import ai.vishwakarma.labelling.stage4.SituationalHedging
import ai.vishwakarma.labelling.stage4.Stage4ConversationDrafter
import ai.vishwakarma.labelling.stage4.Stage4Evidence
import ai.vishwakarma.labelling.stage4.Stage4Generation
import ai.vishwakarma.labelling.stage4.Stage4GenerationRequest
import ai.vishwakarma.labelling.stage4.Stage4JudgeRequest
import ai.vishwakarma.labelling.stage4.Stage4JudgeSampler
import ai.vishwakarma.labelling.stage4.Stage4Judging
import ai.vishwakarma.labelling.stage4.Stage4KnowledgeBase
import ai.vishwakarma.labelling.stage4.Stage4Planning
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * `POST /api/stage4/subjects/{id}/run` body: the `fresh` full-regeneration flag (QA-6) plus the
 * per-run mix-weight overrides (QA-3). Null dials inherit the `app.stage4.mix` defaults; the
 * effective values are frozen into the run's paramsSnapshot at submit.
 */
data class Stage4SubmitRequest(
    val fresh: Boolean = false,
    val mix: MixOverrides? = null,
    /**
     * Operator override for `{{knowledge_as_of}}` (ISO-8601 date). It wins over the profile's own
     * date and the publish date (profile LLD §4.3) but is a *run* param — it never touches the
     * sealed profile, which is why its home is the paramsSnapshot.
     */
    val knowledgeAsOf: String? = null,
) {
    data class MixOverrides(
        val qa: Double? = null,
        val situational: Double? = null,
        val multiClaim: Double? = null,
        val negative: Double? = null,
        val meta: Double? = null,
    )
}

/**
 * What [Stage4Service.export] returns: the completed run, its `exports` record (VA-58), and how
 * many examples the §14 holdout carve kept back for the post-tune eval (VA-60).
 */
data class Stage4ExportOutcome(val run: Stage4Run, val record: ExportRecord, val heldOut: Int)

/**
 * What [Stage4Service.bulkApprove] did: [approved] flips, plus everything left untouched by reason
 * — the flash message spells it out so the operator knows exactly what the shortcut took.
 */
data class Stage4BulkApproveOutcome(
    val approved: Int,
    /** SUBMITTED whose latest same-turns verdict is BORDERLINE or FAIL. */
    val notPass: Int,
    /** SUBMITTED with no judgment on the current turns (never judged, or edited since). */
    val unjudged: Int,
    /** NEEDS_CHANGES rows — sent back by a human, never bulk-approved over. */
    val sentBack: Int,
)

/**
 * What [Stage4Service.overrideApprove] did (VA-176, widened 2026-07-23): every selected
 * awaiting/sent-back row flips to APPROVED regardless of verdict — PASS/unjudged rows as plain bulk
 * approval, FAIL/BORDERLINE rows *over* the judge's verdict (the audit comment names which) — plus
 * everything left untouched, by reason; the flash spells it out.
 */
data class Stage4OverrideApproveOutcome(
    val approved: Int,
    /** Selected ids that matched no example. */
    val notFound: Int,
    /** Status not in {SUBMITTED, NEEDS_CHANGES} — ARCHIVED, already-APPROVED, or still DRAFT. */
    val ineligibleStatus: Int,
    /** Stamp no longer current (the next SELECT tick would archive it) — bulkApprove's guard. */
    val stale: Int,
)

/**
 * Stage 4 (published ledger → conversation notebooks) — the run lifecycle chassis (LLD §9): the
 * Stage 2/3 submit-then-poll idiom exactly. No scheduler — the run advances only inside poll
 * requests, one bounded step each; failures are terminal FAILED states carrying the verbatim error
 * (Retry resumes — phases are re-entrant); a phase making no progress past `app.stage4
 * .phase-timeout` is reclaimed to FAILED on the next poll; REVIEW_WAIT parks the run at the QA-4
 * 100%-human-review gate (export moves it on, never the poll loop).
 *
 * Phases: SELECT (§9.1 — guards, eligible set, QA-6 drift sweep), PLAN (§9.2 — [Stage4Planning]
 * over the frozen params), GENERATE (§9.3 — bounded drafter batches through the plan-keyed
 * generation cache, VA-56), JUDGE (§11 — bounded ensemble batches through the judge-once cursor,
 * VA-57) and the operator [export] that completes a parked run (§9.4/§13, VA-58).
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
    private val judge: Stage4JudgeSampler,
    private val judgments: Stage4JudgmentRepository,
    private val exportService: ExportService,
    private val sft: SftService,
    private val plans: Stage4PlanRepository,
    private val sftExamples: SftExampleRepository,
    private val dpoPairs: DpoPairRepository,
    private val notebookTemplates: NotebookTemplateService,
    private val subjectProfiles: SubjectProfileService,
    private val config: StageConfigService,
) {

    private val log = LoggerFactory.getLogger(Stage4Service::class.java)

    private val planning = Stage4Planning()

    /** kb-generation (VA-164): renders the posture-labelled KB tier from the eligible set. */
    private val kbRenderer = Stage4KnowledgeBase()

    /** Same §10.3 computer (and default bands) the voicing planner derives with — the symmetry. */
    private val hedging = SituationalHedging()

    // ---- submit -----------------------------------------------------------------

    /**
     * Start a run. Guards: Stage 4 enabled, subject exists, no active run — except a parked
     * REVIEW_WAIT run, which the newer run retires as SUPERSEDED (QA-6: it never exported, so
     * nothing references it). The ledger guards (§9.1) run inside SELECT so their verbatim reasons
     * land on the run record. Effective params — including the QA-3 mix overrides and `fresh` — are
     * frozen into the paramsSnapshot at this instant.
     *
     * The SubjectProfile's two injection scalars freeze here too (profile LLD §4.3): resolved once,
     * not per poll tick, so a mid-run profile edit cannot half-colour a dataset. `knowledge_as_of`
     * resolves operator override → profile → publish date; the A/B profileHash is frozen onto the
     * run beside them so SELECT's drift sweep archives examples baked with an older locale.
     */
    fun submit(
        subjectId: String,
        request: Stage4SubmitRequest,
        actor: String?,
    ): Either<DomainError, Stage4Run> {
        if (!config.stage4().enabled)
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
        val override = request.knowledgeAsOf?.trim()?.takeIf { it.isNotBlank() }
        if (override != null && runCatching { LocalDate.parse(override) }.isFailure) {
            return DomainError.Invalid(
                    "knowledgeAsOf must be an ISO-8601 date (yyyy-MM-dd), got '$override'"
                )
                .left()
        }
        val effective = effectiveStage4(overrides)
        val profile = subjectProfiles.resolved(subjectId)
        val run =
            Stage4Run(
                id = runs.newId(),
                subjectId = subjectId,
                fresh = request.fresh,
                profileHash = profile.hashOrNull(),
                paramsSnapshot =
                    snapshotOf(
                        effective,
                        request.fresh,
                        locale = profile.locale,
                        knowledgeAsOf = knowledgeAsOfFor(subjectId, override, profile),
                    ),
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
            val archived = driftSweep(run.subjectId, scoreRunId, personaHash, run.profileHash)
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

    /**
     * QA-6: archive stamped examples/pairs whose publish, persona **or profile** drifted; returns
     * the count. The profile axis (OD-7) is what catches an A/B-only edit: it changes no claim and
     * no score, so without it a notebook baked with the old locale would survive and export.
     */
    private fun driftSweep(
        subjectId: String,
        scoreRunId: String,
        personaHash: String,
        profileHash: String?,
    ): Long {
        val now = Instant.now()
        var archived = 0L
        sftExamples.findByStampSubject(subjectId).forEach { example ->
            if (example.status == ExampleStatus.ARCHIVED) return@forEach
            val stamp = example.stamp ?: return@forEach
            val reason = driftReason(stamp, scoreRunId, personaHash, profileHash) ?: return@forEach
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
            val reason = driftReason(stamp, scoreRunId, personaHash, profileHash) ?: return@forEach
            dpoPairs.save(
                pair.copy(status = ExampleStatus.ARCHIVED, archivedReason = reason, updatedAt = now)
            )
            archived++
        }
        if (archived > 0)
            log.info("Subject {}: drift sweep archived {} stale example(s)", subjectId, archived)
        return archived
    }

    private fun driftReason(
        stamp: Stage4Stamp,
        scoreRunId: String,
        personaHash: String,
        profileHash: String?,
    ): String? {
        val drifted = buildList {
            if (stamp.scoreRunId != scoreRunId) add("scoreRunId $scoreRunId")
            if (stamp.personaHash != personaHash) add("personaHash ${personaHash.take(12)}")
            // Null on both sides for a subject with no declared profile — the pre-profile world
            // never drifts on this axis, so nothing is archived just because the feature landed.
            if (stamp.profileHash != profileHash) {
                add("profileHash ${profileHash?.take(12) ?: "none"}")
            }
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
                    // VA-88: the library (taxonomy order) drives planning; empty ⇒ legacy trio.
                    templates = notebookTemplates.list(),
                    // VA-164: with a library present, freeze specs instead of phrased questions.
                    kbGeneration = params.kbGeneration,
                )
            val now = Instant.now()
            outcome.planned.forEach {
                plans.save(
                    Stage4Plan(
                        subjectId = run.subjectId,
                        scoreRunId = scoreRunId,
                        personaHash = personaHash,
                        // Spec plans carry no phrased question (kb-generation) — persist null, not
                        // the empty placeholder, so `question == null` reads as "has a spec".
                        question = it.question.ifBlank { null },
                        plan = it.plan,
                        createdAt = now,
                    )
                )
            }
            // The VA-88 coverage report freezes with the plan — hit/missed vs coverage targets.
            val coverageJson =
                outcome.coverage
                    .takeIf { it.isNotEmpty() }
                    ?.let { rows ->
                        Json.writeLine(
                            rows.map {
                                mapOf(
                                    "category" to it.category,
                                    "target" to it.target,
                                    "planned" to it.planned,
                                )
                            }
                        )
                    }
            advance(
                run.copy(coverageReport = coverageJson),
                Stage4RunStatus.GENERATING,
                mapOf(
                    Stage4Counters.PLANS to outcome.planned.size.toLong(),
                    Stage4Counters.PLANS_DEDUPED to outcome.deduped.toLong(),
                    Stage4Counters.PLANS_CAPPED to outcome.capped.toLong(),
                    Stage4Counters.PLANS_FROM_TEMPLATES to
                        outcome.planned.count { it.plan.templateId != null }.toLong(),
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
            val subjectTag = subjectTag(run.subjectId)
            val promptByCategory =
                Stage4Category.entries
                    .filter { it != Stage4Category.META }
                    .associateWith { prompts.resolveStage4Generator(it) }
            // VA-88: a template-shaped plan resolves its instruction block (and cache hash) from
            // the template itself — an edit re-drafts exactly the affected plans, the prompt-bump
            // semantics. A deleted template falls back to the plan's category row.
            val templatesById = notebookTemplates.list().associateBy { it.id }
            fun rowFor(plan: VoicingPlan): ResolvedExtractionPrompt? {
                plan.templateId
                    ?.let { templatesById[it] }
                    ?.let {
                        return Stage4Generation.templateRow(it, subjectName)
                    }
                return if (plan.category == Stage4Category.META) null
                else promptByCategory.getValue(plan.category)
            }
            fun promptHashFor(plan: VoicingPlan): String =
                rowFor(plan)?.hash ?: Stage4Generation.META_TEMPLATE_STAMP

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
                    it.stamp?.generatorPromptHash == promptHashFor(p.plan)
                }

            val frozen = frozenParams(run)
            val unsatisfied = subjectPlans.filterNot(::satisfied)
            // Spec-mode runs draft only spec-carrying trio plans (VA-164): stale pre-library
            // plans still match the subject's scoreRunId/personaHash but carry a phrased
            // question and no spec — drafting them would reintroduce the exact
            // templated-question shape kb-generation exists to kill. Probe banks
            // (NEGATIVE/META) never carry specs and always draft. Skipped plans leave the
            // pending set entirely so the run still advances to JUDGING when the spec work
            // is done.
            val pending =
                if (frozen.kbGeneration) {
                    val (draftable, stalePhrased) =
                        unsatisfied.partition { Stage4Planning.draftsUnderSpecMode(it.plan) }
                    if (stalePhrased.isNotEmpty()) {
                        log.info(
                            "Run {}: GENERATE spec mode — skipping {} stale phrased plan(s) " +
                                "without a spec (e.g. {})",
                            run.id,
                            stalePhrased.size,
                            stalePhrased.first().plan.planId,
                        )
                    }
                    draftable
                } else {
                    unsatisfied
                }
            if (pending.isEmpty()) {
                return@inPhase advance(run, Stage4RunStatus.JUDGING, generateCounters(run))
            }

            // kb-generation (VA-164): the KB tier is a per-publish derivation over the eligible set
            // — built once per tick and shared by every spec plan drafted this poll. Empty when the
            // flag is off, so the request stays byte-for-byte legacy on that path.
            val knowledgeBase =
                if (frozen.kbGeneration)
                    kbRenderer.render(evidenceById.values.toList(), frozen.kbMaxClaims)
                else emptyList()
            val kbStandingRules =
                if (frozen.kbGeneration) Stage4KnowledgeBase.STANDING_RULES else ""
            val batch = pending.take(frozen.generateBatchPerPoll)
            log.info(
                "Run {}: GENERATE — {} plan(s) pending, drafting {} this tick",
                run.id,
                pending.size,
                batch.size,
            )
            batch.forEach { p ->
                generateOne(
                    run,
                    p,
                    subjectName,
                    subjectTag,
                    persona,
                    presetStyle,
                    ::rowFor,
                    evidenceById,
                    frozen.locale,
                    frozen.knowledgeAsOf,
                    frozen.kbGeneration,
                    knowledgeBase,
                    kbStandingRules,
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
        /** Who's-who prefix for the example id: the subject's handle, else an id stem. */
        subjectTag: String,
        persona: ResolvedPersona,
        presetStyle: String,
        /** The plan's instruction row: template block (VA-88) or category row; null = META. */
        rowFor: (VoicingPlan) -> ResolvedExtractionPrompt?,
        evidenceById: Map<String, EvidencedClaim>,
        /** The run-frozen profile scalars (§4.3); blank ⇒ today's prompt, byte for byte. */
        locale: String,
        knowledgeAsOf: String,
        /** kb-generation (VA-164): the frozen flag + the per-tick KB tier passed to the drafter. */
        kbGeneration: Boolean,
        knowledgeBase: List<String>,
        kbStandingRules: String,
    ) {
        val category = p.plan.category
        val row = rowFor(p.plan)
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
        // `fresh` flag bypasses. The profileHash is part of the key (profile LLD §6.4): the plan id
        // is computed before injection, so without it a locale edit would archive the stale
        // conversations and then copy those very turns straight back, defeating the drift axis.
        val cached =
            if (run.fresh) null
            else
                sftExamples
                    .findByStampSubject(run.subjectId)
                    .filter {
                        it.status == ExampleStatus.ARCHIVED &&
                            it.stamp?.planId == p.plan.planId &&
                            it.stamp?.generatorPromptHash == promptHash &&
                            it.stamp?.profileHash == run.profileHash &&
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
                profileHash = run.profileHash,
                generatorPromptHash = promptHash,
                templateId = p.plan.templateId,
                templateCategory = p.plan.templateCategory,
            )
        val tags =
            ExampleTags(
                labels =
                    listOfNotNull(
                        "stage4:${category.name.lowercase()}",
                        p.plan.templateCategory?.let { "tpl:$it" },
                    )
            )

        val draftStarted = System.currentTimeMillis()
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
                                locale = locale,
                                knowledgeAsOf = knowledgeAsOf,
                                // Spec-mode grounding — buildPrompt uses it only when the flag is
                                // on
                                // AND the plan carries a spec; otherwise it is ignored (legacy
                                // path).
                                kbGeneration = kbGeneration,
                                knowledgeBase = knowledgeBase,
                                kbStandingRules = kbStandingRules,
                            )
                        ),
                        drafter.model,
                        "stage4:${run.id}",
                    )
            }
        sftExamples.save(
            SftExample(
                id = "$subjectTag-${sftExamples.newId()}",
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
        log.info(
            "Run {}: GENERATE plan {} ({}) — {} — {} turn(s), {} ms",
            run.id,
            p.plan.planId,
            category,
            when {
                cached != null -> "cache copy, no LLM call"
                category == Stage4Category.META -> "meta render, no LLM call"
                else -> "LLM draft via ${llmModel ?: "gemini"}"
            },
            turns.size,
            System.currentTimeMillis() - draftStarted,
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

    /** One §9.3 evidence line — the shared [Stage4Evidence] rendering. */
    private fun evidenceLine(e: EvidencedClaim): String = Stage4Evidence.line(e)

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
                        it.stamp?.profileHash == run.profileHash &&
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

    // ---- JUDGE (LLD §11, VA-57) --------------------------------------------------------

    /**
     * One bounded tick, the GENERATE idiom mirrored: the store is the cursor — an example is
     * *judged* when a `stage4_judgments` doc exists at its (exampleId, rubric versionStamp,
     * turnsHash). Unchanged examples never re-judge (the verdict cache), a rubric bump re-judges
     * everything, and an edited example misses at its new turnsHash and re-judges (§11 feedback
     * loop). The overall verdict lands on the example (the queue's FAIL → BORDERLINE → PASS
     * pre-sort key) and routes it: FAIL → NEEDS_CHANGES with the failing axes as a review comment;
     * PASS and BORDERLINE → SUBMITTED, the human queue — 100% review in v1 (QA-4:
     * `review-sample-rate` exists but is deliberately not read). Counters recompute absolutely.
     */
    private fun runJudge(run: Stage4Run): Stage4Run =
        inPhase(run, "JUDGE") {
            val scoreRunId =
                checkNotNull(run.scoreRunId) { "run carries no frozen scoreRunId — SELECT first" }
            checkNotNull(run.personaHash) { "run carries no frozen personaHash — SELECT first" }

            // QD-6: judge disabled at submit — the whole queue goes to the human unsorted and
            // unverdicted; no judgments accrue. One tick, then the QA-4 park as usual.
            if (!frozenParams(run).judgeEnabled) {
                val now = Instant.now()
                val submitted = liveStampedExamples(run).filter { it.status == ExampleStatus.DRAFT }
                submitted.forEach {
                    sftExamples.save(it.copy(status = ExampleStatus.SUBMITTED, updatedAt = now))
                }
                log.info(
                    "Run {}: judge disabled (judgeEnabled=false) — {} example(s) submitted " +
                        "unjudged",
                    run.id,
                    submitted.size,
                )
                return@inPhase advance(run, Stage4RunStatus.REVIEW_WAIT, emptyMap())
            }

            val stamp = judge.versionStamp
            val judgedHashes = judgedTurnsHashes(run.subjectId, stamp)
            fun judged(e: SftExample): Boolean =
                Stage4Judging.turnsHash(e.turns) in judgedHashes[e.id].orEmpty()

            val pending = liveStampedExamples(run).filterNot(::judged)
            if (pending.isEmpty()) {
                return@inPhase advance(run, Stage4RunStatus.REVIEW_WAIT, judgeCounters(run, stamp))
            }

            val persona = personaService.resolved(run.subjectId)
            val presetStyle = prompts.resolveStage4Preset(persona.presetId).instructions
            val subjectName = subjects.findById(run.subjectId)?.displayName ?: "the subject"
            val evidenceById = eligibleClaims(run.subjectId, scoreRunId).associateBy { it.claim.id }
            val params = frozenParams(run)
            // kb-generation (VA-164) judge symmetry: the KB tier is the same per-publish derivation
            // GENERATE built — rendered once per tick from the eligible set and handed to every
            // spec
            // example judged this poll. Empty when the flag is off, so the request stays byte-for-
            // byte legacy on that path.
            val knowledgeBase =
                if (params.kbGeneration)
                    kbRenderer.render(evidenceById.values.toList(), params.kbMaxClaims)
                else emptyList()
            val kbStandingRules =
                if (params.kbGeneration) Stage4KnowledgeBase.STANDING_RULES else ""
            val batch = pending.take(params.judgeBatchPerPoll)
            log.info(
                "Run {}: JUDGE — {} example(s) pending, judging {} this tick (ensemble k={})",
                run.id,
                pending.size,
                batch.size,
                params.ensembleK,
            )
            batch.forEach { e ->
                judgeOne(
                    run,
                    e,
                    subjectName,
                    persona,
                    presetStyle,
                    evidenceById,
                    params.ensembleK,
                    params.kbGeneration,
                    knowledgeBase,
                    kbStandingRules,
                )
            }
            run.copy(
                    counters = run.counters + judgeCounters(run, stamp),
                    phaseSince = Instant.now()
                )
                .also { runs.save(it) }
        }

    /** Judge one example: k ensemble samples, majority per axis, persist + verdict routing. */
    private fun judgeOne(
        run: Stage4Run,
        example: SftExample,
        subjectName: String,
        persona: ResolvedPersona,
        presetStyle: String,
        evidenceById: Map<String, EvidencedClaim>,
        ensembleK: Int,
        /** kb-generation (VA-164): the frozen flag + the per-tick KB tier passed to the judge. */
        kbGeneration: Boolean,
        knowledgeBase: List<String>,
        kbStandingRules: String,
    ) {
        val planId = checkNotNull(example.stamp?.planId)
        val plan =
            plans.findById(planId)
                ?: error("plan $planId behind example ${example.id} is missing — resubmit the run")
        val request =
            Stage4JudgeRequest(
                subjectName = subjectName,
                turns = example.turns,
                plan = plan.plan,
                persona = persona,
                presetStyle = presetStyle,
                evidence =
                    plan.plan.sourceClaimIds.mapNotNull { id ->
                        evidenceById[id]?.let { evidenceLine(it) }
                    },
                expectedHedge = expectedHedge(run, plan.plan, evidenceById),
                // Spec-mode judging — buildPrompt uses these only when the flag is on AND the plan
                // carries a spec; otherwise they are ignored (legacy judge prompt, byte for byte).
                kbGeneration = kbGeneration,
                knowledgeBase = knowledgeBase,
                kbStandingRules = kbStandingRules,
            )
        val judgeStarted = System.currentTimeMillis()
        val samples =
            (0 until ensembleK).mapNotNull { i ->
                log.info(
                    "Run {}: judging example {} (plan {}) — sample {}/{}",
                    run.id,
                    example.id,
                    planId,
                    i + 1,
                    ensembleK,
                )
                judge.sample(request, i)
            }
        check(samples.isNotEmpty()) {
            "judge cast no votes on example ${example.id} ($ensembleK unusable samples)"
        }
        val axes = Stage4Judging.aggregate(samples)
        val overall = Stage4Judging.overallOf(axes)
        val now = Instant.now()
        judgments.save(
            Stage4Judgment(
                id = judgments.newId(),
                exampleId = example.id,
                runId = run.id,
                subjectId = run.subjectId,
                axes = axes.mapKeys { it.key.name },
                overall = overall,
                judgePromptVersion = judge.promptVersion,
                judgePromptHash = judge.versionStamp,
                turnsHash = Stage4Judging.turnsHash(example.turns),
                model = judge.modelId,
                createdAt = now,
            )
        )
        val routed =
            when {
                example.status !in ROUTABLE_STATUSES ->
                    example.copy(judgeVerdict = overall, updatedAt = now)
                overall == JudgeVerdict.FAIL ->
                    example.copy(
                        status = ExampleStatus.NEEDS_CHANGES,
                        judgeVerdict = overall,
                        reviewComments =
                            example.reviewComments +
                                ReviewComment("stage4-judge", Stage4Judging.failRationale(axes)),
                        updatedAt = now,
                    )
                else ->
                    example.copy(
                        status = ExampleStatus.SUBMITTED,
                        judgeVerdict = overall,
                        updatedAt = now,
                    )
            }
        sftExamples.save(routed)
        log.info(
            "Run {}: example {} judged {} — {}/{} sample(s) voted, {} ms",
            run.id,
            example.id,
            overall,
            samples.size,
            ensembleK,
            System.currentTimeMillis() - judgeStarted,
        )
    }

    /**
     * The judge-time §10.3 re-derivation for a situational plan: the fact chain intersecting the
     * plan's surviving claims, mapped through the same [SituationalEvidence] and the same [hedging]
     * PLAN derived with — same evidence, same verdict (the VA-57 symmetry).
     */
    private fun expectedHedge(
        run: Stage4Run,
        plan: VoicingPlan,
        evidenceById: Map<String, EvidencedClaim>,
    ): HedgeVerdict? {
        if (plan.category != Stage4Category.SITUATIONAL) return null
        val claimIds = plan.sourceClaimIds.toSet()
        val chain =
            subjectFacts
                .findBySubject(run.subjectId)
                .filter {
                    it.scoreRunId == run.scoreRunId &&
                        it.memberClaimIds.any { id -> id in claimIds }
                }
                .sortedBy { it.factId }
                .mapNotNull { SituationalEvidence.supportingFactOf(it, evidenceById) }
        return hedging.compute(chain)
    }

    /** Live current-stamp examples with a planId — the judge's (and export's) working set. */
    private fun liveStampedExamples(run: Stage4Run): List<SftExample> =
        sftExamples
            .findByStampSubject(run.subjectId)
            .filter {
                it.status != ExampleStatus.ARCHIVED &&
                    it.stamp?.scoreRunId == run.scoreRunId &&
                    it.stamp?.personaHash == run.personaHash &&
                    it.stamp?.planId != null
            }
            .sortedBy { it.id }

    /** exampleId → turns hashes already judged at the current rubric stamp (one subject read). */
    private fun judgedTurnsHashes(subjectId: String, stamp: String): Map<String, Set<String>> =
        judgments
            .findBySubject(subjectId)
            .filter { it.judgePromptHash == stamp }
            .groupBy({ it.exampleId }, { it.turnsHash })
            .mapValues { (_, hashes) -> hashes.filterNotNull().toSet() }

    /**
     * Absolute JUDGE counters, recomputed from the store each tick (re-entrant, never
     * double-counted): every live current-stamp example counted by its latest judgment at the
     * current rubric stamp and current turns.
     */
    private fun judgeCounters(run: Stage4Run, stamp: String): Map<String, Long> {
        val byExample =
            judgments
                .findBySubject(run.subjectId)
                .filter { it.judgePromptHash == stamp }
                .groupBy { it.exampleId }
        val verdicts =
            liveStampedExamples(run).mapNotNull { e ->
                byExample[e.id]
                    ?.filter { it.turnsHash == Stage4Judging.turnsHash(e.turns) }
                    ?.maxByOrNull { it.createdAt ?: Instant.EPOCH }
                    ?.overall
            }
        return mapOf(
            Stage4Counters.JUDGED_PASS to verdicts.count { it == JudgeVerdict.PASS }.toLong(),
            Stage4Counters.JUDGED_BORDERLINE to
                verdicts.count { it == JudgeVerdict.BORDERLINE }.toLong(),
            Stage4Counters.JUDGED_FAIL to verdicts.count { it == JudgeVerdict.FAIL }.toLong(),
        )
    }

    // ---- bulk approve — the judge-trusting shortcut through the QA-4 queue -------------------

    /**
     * Approve every SUBMITTED current-stamp example whose **latest judgment on the current turns**
     * is PASS — the same latest-pass-plus-turnsHash contract the review panel renders, so the
     * button approves exactly the rows the operator would see as un-stale PASS. Everything else is
     * left untouched: BORDERLINE/FAIL (a human call by design), unjudged or edited-since-judged
     * turns (no verdict to trust), and NEEDS_CHANGES (already in a human loop). Run must be parked
     * at REVIEW_WAIT — the export gate is unchanged and still takes only APPROVED examples.
     */
    fun bulkApprove(runId: String, actor: String?): Either<DomainError, Stage4BulkApproveOutcome> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status != Stage4RunStatus.REVIEW_WAIT)
            return DomainError.Conflict(
                    "Only a REVIEW_WAIT run can be bulk-approved (run is ${run.status})"
                )
                .left()
        val byExample = judgments.findBySubject(run.subjectId).groupBy { it.exampleId }
        val live = liveStampedExamples(run)
        var approved = 0
        var notPass = 0
        var unjudged = 0
        live
            .filter { it.status == ExampleStatus.SUBMITTED }
            .forEach { example ->
                val latest =
                    byExample[example.id]
                        ?.filter { it.turnsHash == Stage4Judging.turnsHash(example.turns) }
                        ?.maxByOrNull { it.createdAt ?: Instant.EPOCH }
                when {
                    latest == null -> unjudged++
                    latest.overall != JudgeVerdict.PASS -> notPass++
                    // A Left here means the row changed under us mid-loop; count it as unjudged
                    // rather than aborting a half-done bulk.
                    else -> sft.approve(example.id, actor).fold({ unjudged++ }, { approved++ })
                }
            }
        val outcome =
            Stage4BulkApproveOutcome(
                approved = approved,
                notPass = notPass,
                unjudged = unjudged,
                sentBack = live.count { it.status == ExampleStatus.NEEDS_CHANGES },
            )
        log.info(
            "Run {}: bulk-approved {} judge-PASS example(s) ({} not-PASS, {} unjudged, {} " +
                "sent-back untouched) by {}",
            run.id,
            outcome.approved,
            outcome.notPass,
            outcome.unjudged,
            outcome.sentBack,
            actor,
        )
        return outcome.right()
    }

    // ---- override approve — the ADMIN judge bypass on the review list (VA-176) ---------------

    /**
     * ADMIN override (VA-176): approve the **explicitly selected** judge-FAIL/BORDERLINE examples
     * over the verdict so they can flow to export/training. This is the deliberate counterpart to
     * [bulkApprove], which refuses BORDERLINE/FAIL by design — here an ADMIN takes that call by id,
     * off the SFT review list, and every approval is attributed.
     *
     * Eligible = latest verdict ([SftExample.judgeVerdict]) is FAIL or BORDERLINE, status is
     * SUBMITTED or NEEDS_CHANGES (a sent-back row can still be overridden), and the stamp is
     * current — the same currency guard [bulkApprove] enforces through [liveStampedExamples], since
     * a stale row is drift-swept on the next SELECT tick and approving it would be moot. Each
     * approval flips to APPROVED and records a [ReviewComment] naming this as an ADMIN override of
     * the FAIL/ BORDERLINE verdict: export takes APPROVED only, so the comment is the audit trail
     * for the gate bypass and must be attributable to [actor]. Ids that match no example,
     * PASS/unjudged rows, and rows in any other status are skipped and counted by reason (like
     * [Stage4BulkApproveOutcome]).
     */
    fun overrideApprove(
        exampleIds: List<String>,
        actor: String?,
    ): Stage4OverrideApproveOutcome {
        var approved = 0
        var notFound = 0
        var ineligibleStatus = 0
        var stale = 0
        // currentFor reads Firestore (persona resolve) — compute once per distinct subject.
        val currentBySubject = HashMap<String, Pair<String?, String>>()
        exampleIds.distinct().forEach { id ->
            val example = sft.get(id)
            when {
                example == null -> notFound++
                example.status != ExampleStatus.SUBMITTED &&
                    example.status != ExampleStatus.NEEDS_CHANGES -> ineligibleStatus++
                !isCurrentStamp(example.stamp, currentBySubject) -> stale++
                else -> {
                    // The audit comment names what the approval went past: a FAIL/BORDERLINE is
                    // an override of the judge; PASS/unjudged is plain selection approval.
                    val note =
                        when (example.judgeVerdict) {
                            JudgeVerdict.FAIL,
                            JudgeVerdict.BORDERLINE ->
                                "ADMIN override-approve over the ${example.judgeVerdict} judge " +
                                    "verdict."
                            JudgeVerdict.PASS -> "ADMIN bulk-approve via selection (judge PASS)."
                            null -> "ADMIN bulk-approve via selection (unjudged)."
                        }
                    sft.overrideApprove(id, actor, note)
                        // A Left means the row changed under us mid-loop (a race) — count it as an
                        // ineligible-status skip rather than aborting a partial override.
                        .fold({ ineligibleStatus++ }, { approved++ })
                }
            }
        }
        val outcome =
            Stage4OverrideApproveOutcome(
                approved = approved,
                notFound = notFound,
                ineligibleStatus = ineligibleStatus,
                stale = stale,
            )
        log.info(
            "Bulk-approved {} selected example(s) (FAIL/BORDERLINE approved over the judge) by " +
                "{} ({} not found, {} ineligible status, {} stale)",
            outcome.approved,
            actor,
            outcome.notFound,
            outcome.ineligibleStatus,
            outcome.stale,
        )
        return outcome
    }

    /**
     * The subject's live (scoreRunId, personaHash) pair vs. the example's stamp — [bulkApprove]'s
     * currency, resolved per subject (cached) rather than per row. A stamp-less (legacy) example is
     * never current here.
     */
    private fun isCurrentStamp(
        stamp: Stage4Stamp?,
        cache: MutableMap<String, Pair<String?, String>>,
    ): Boolean {
        val subjectId = stamp?.subjectId ?: return false
        val (scoreRunId, personaHash) =
            cache.getOrPut(subjectId) {
                subjectScores.find(subjectId)?.scoreRunId to
                    personaService.resolved(subjectId).hash()
            }
        return scoreRunId != null &&
            stamp.scoreRunId == scoreRunId &&
            stamp.personaHash == personaHash
    }

    // ---- export (LLD §9.4/§13, VA-58) — the operator action that completes a parked run ----

    /**
     * Who's-who tag for stage-4 artifacts (owner ask 2026-07-24): the subject's handle (username)
     * when set, else a short subjectId stem. Leads every generated SFT example id and the export
     * filename, and rides [ExportRecord.subjectTag] into the tuned model's display name + weights
     * path — so datasets and models are attributable at a glance.
     */
    private fun subjectTag(subjectId: String): String =
        subjects.findById(subjectId)?.handle?.takeIf { it.isNotBlank() } ?: subjectId.take(8)

    /**
     * Complete a REVIEW_WAIT run: [ExportService.exportStage4Run] filters APPROVED + current-stamp
     * examples, carves the §14 holdout slice under the run's frozen `evalHoldoutFraction`, and
     * gates the rest through both validators (any failure aborts with exampleId pointers before a
     * blob or record lands); then the exportRecordId is journaled and the run finishes —
     * REVIEW_WAIT → DONE, the one transition the poll loop never makes (QA-4).
     * `TrainingService.submit` consumes the record unchanged (DatasetSource.EXPORT).
     */
    fun export(runId: String, actor: String?): Either<DomainError, Stage4ExportOutcome> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status != Stage4RunStatus.REVIEW_WAIT)
            return DomainError.Conflict(
                    "Only a REVIEW_WAIT run can be exported (run is ${run.status})"
                )
                .left()
        return exportService
            .exportStage4Run(run, actor, frozenHoldoutFraction(run), subjectTag(run.subjectId))
            .map { result ->
                val record = result.record
                val now = Instant.now()
                val done =
                    run.copy(
                        status = Stage4RunStatus.DONE,
                        exportRecordId = record.id,
                        finishedAt = now,
                        phaseSince = now,
                    )
                runs.save(done)
                log.info(
                    "Run {}: REVIEW_WAIT → DONE (export {}, {} example(s) → {}; {} held out for eval)",
                    run.id,
                    record.id,
                    record.count,
                    record.gcsUri,
                    result.heldOut,
                )
                Stage4ExportOutcome(done, record, result.heldOut)
            }
    }

    /** The run's frozen `evalHoldoutFraction` (paramsSnapshot), live config as the fallback. */
    private fun frozenHoldoutFraction(run: Stage4Run): Double {
        val raw =
            run.paramsSnapshot
                ?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }
                ?.get("evalHoldoutFraction")
        return (raw as? Number)?.toDouble() ?: config.stage4().evalHoldoutFraction
    }

    // ---- params snapshot ------------------------------------------------------------

    private fun effectiveStage4(
        overrides: Stage4SubmitRequest.MixOverrides?
    ): AppProperties.Stage4 {
        val base = config.stage4()
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

    /**
     * The §4.3 `{{knowledge_as_of}}` resolution order: operator run override → the profile's own
     * date → the subject's publish date. Blank when nothing resolves — the clause is then dropped
     * from the prompt entirely.
     *
     * **The ladder only runs when the profile surface is actually in play.** Stage 4 refuses to run
     * an unpublished subject, so `publishedAt` is effectively always set: an ungated fall-through
     * to it would hand *every* subject a frozen date — profile or not, feature flag or not — and
     * the emitted context line would break the byte-for-byte-legacy contract this slice promises
     * (and that `app.stage4.profile-enabled` advertises). A blank profile already covers the
     * flag-off case, since [SubjectProfileService.resolved] blanks the profile when the flag is
     * down.
     */
    private fun knowledgeAsOfFor(
        subjectId: String,
        override: String?,
        profile: ResolvedSubjectProfile,
    ): String {
        if (override == null && profile.blank) return ""
        return override
            ?: profile.knowledgeAsOf?.toString()
            ?: subjectScores
                .find(subjectId)
                ?.publishedAt
                ?.atZone(ZoneOffset.UTC)
                ?.toLocalDate()
                ?.toString()
            ?: ""
    }

    /**
     * Every effective `app.stage4.*` value plus `fresh` and the SubjectProfile injection scalars,
     * as a parseable JSON map (§3.2 audit / profile LLD §4.3).
     */
    private fun snapshotOf(
        effective: AppProperties.Stage4,
        fresh: Boolean,
        locale: String,
        knowledgeAsOf: String,
    ): String =
        Json.writeLine(
            mapOf(
                "enabled" to effective.enabled,
                "dryRun" to effective.dryRun,
                "dryRunJudgeFailRate" to effective.dryRunJudgeFailRate,
                "dryRunJudgeBorderlineRate" to effective.dryRunJudgeBorderlineRate,
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
                "judgeEnabled" to effective.judgeEnabled,
                "kbGeneration" to effective.kbGeneration,
                "kbMaxClaims" to effective.kbMaxClaims,
                "reviewSampleRate" to effective.reviewSampleRate,
                "dpoEnabled" to effective.dpoEnabled,
                "evalHoldoutFraction" to effective.evalHoldoutFraction,
                "evalBehaviorBar" to effective.evalBehaviorBar,
                "phaseTimeout" to effective.phaseTimeout.toString(),
                "fresh" to fresh,
                "locale" to locale,
                "knowledgeAsOf" to knowledgeAsOf,
            )
        )

    private data class FrozenParams(
        val mix: AppProperties.Stage4.Mix,
        val maxConversationsPerClaim: Int,
        val dedupeJaccardThreshold: Double,
        val generateBatchPerPoll: Int,
        val judgeBatchPerPoll: Int,
        val ensembleK: Int,
        val judgeEnabled: Boolean,
        /** VA-164: KB + spec generation, and the per-conversation KB line cap. */
        val kbGeneration: Boolean,
        val kbMaxClaims: Int,
        /** The profile scalars GENERATE injects; blank on pre-profile runs (§4.3). */
        val locale: String,
        val knowledgeAsOf: String,
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
        val base = config.stage4()
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
            judgeBatchPerPoll =
                (raw["judgeBatchPerPoll"] as? Number)?.toInt() ?: base.judgeBatchPerPoll,
            ensembleK = (raw["ensembleK"] as? Number)?.toInt() ?: base.ensembleK,
            judgeEnabled = (raw["judgeEnabled"] as? Boolean) ?: base.judgeEnabled,
            kbGeneration = (raw["kbGeneration"] as? Boolean) ?: base.kbGeneration,
            kbMaxClaims = (raw["kbMaxClaims"] as? Number)?.toInt() ?: base.kbMaxClaims,
            // No live fallback: these two are run-frozen by definition, and a pre-profile run must
            // keep injecting nothing however the profile has changed since.
            locale = (raw["locale"] as? String).orEmpty(),
            knowledgeAsOf = (raw["knowledgeAsOf"] as? String).orEmpty(),
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
        val timeout = config.stage4().phaseTimeout
        if (Instant.now().isBefore(since.plus(timeout))) return null
        log.warn("Run {}: {} stuck since {} — reclaiming to FAILED", run.id, run.status, since)
        return fail(
            run,
            "${run.status} made no progress for ${timeout.toMinutes()}m (since $since) — " +
                "reclaimed; Retry resumes the phase",
        )
    }

    companion object {
        /** Statuses a verdict may route (§11); APPROVED and ARCHIVED are never touched. */
        private val ROUTABLE_STATUSES =
            setOf(ExampleStatus.DRAFT, ExampleStatus.SUBMITTED, ExampleStatus.NEEDS_CHANGES)
    }
}

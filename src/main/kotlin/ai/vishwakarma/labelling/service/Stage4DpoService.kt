package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.DpoPair
import ai.vishwakarma.labelling.domain.DpoSource
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Stage4Run
import ai.vishwakarma.labelling.domain.Stage4RunStatus
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.DpoPairRepository
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.persistence.Stage4PlanRepository
import ai.vishwakarma.labelling.persistence.Stage4RunRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage4.EvidencedClaim
import ai.vishwakarma.labelling.stage4.Stage4DpoGeneration
import ai.vishwakarma.labelling.stage4.Stage4DpoRequest
import ai.vishwakarma.labelling.stage4.Stage4Evidence
import ai.vishwakarma.labelling.stage4.Stage4RejectedDrafter
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** What one [Stage4DpoService.generate] batch did — the flash message's itemization. */
data class Stage4DpoOutcome(
    val generated: Int,
    /** Approved examples still without a pair after this batch (more clicks to go). */
    val remaining: Int,
    /** Eligible examples skipped this batch (missing plan / no final model turn), logged. */
    val skipped: Int,
)

/**
 * DPO pair construction (LLD §12, VA-61) — **dark behind `app.stage4.dpo-enabled`** (default false)
 * until the first DPO tune freezes the export format (S4-D4). For an APPROVED current-stamp SFT
 * conversation: `chosen` = its compliant final model turn, `rejected` = an LLM rendering of the
 * same prompt committing one of the six §12 violation classes (picked deterministically off the
 * frozen voicing plan, each class behind its pinned `stage4:dpo:*` prompt row). Pairs enter the
 * existing lifecycle at DRAFT with the full traceability stamp + violation class — and they live in
 * `dpo_pairs`, structurally outside every SFT export path.
 *
 * Operator-driven and bounded like bulk-approve: one [generate] call processes one batch; the page
 * shows the remaining count and the button repeats. One pair per source example (the §12 shape);
 * regenerating means archiving the pair and clicking again.
 */
@Service
class Stage4DpoService(
    private val runs: Stage4RunRepository,
    private val sftExamples: SftExampleRepository,
    private val dpoPairs: DpoPairRepository,
    private val plans: Stage4PlanRepository,
    private val subjects: SubjectRepository,
    private val personaService: PersonaService,
    private val reviewService: ClaimReviewService,
    private val claimReviews: ClaimReviewRepository,
    private val drafter: Stage4RejectedDrafter,
    private val prompts: ExtractionPromptService,
    private val config: StageConfigService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Approved current-stamp examples of [run] not yet seeding an LLM2 pair. §14 holdout rows are
     * excluded — a pair built on a held-out conversation would put its prompt AND reference answer
     * into a DPO training set, contaminating the post-tune eval's ground truth (VA-60).
     */
    fun pending(run: Stage4Run): List<SftExample> {
        val seeded =
            dpoPairs
                .findByStampSubject(run.subjectId)
                .filter { it.source == DpoSource.LLM2 && it.fromSftId != null }
                .mapNotNull { it.fromSftId }
                .toSet()
        return sftExamples
            .findByStampSubject(run.subjectId)
            .filter {
                it.status == ExampleStatus.APPROVED &&
                    !it.holdout &&
                    it.stamp?.scoreRunId == run.scoreRunId &&
                    it.stamp?.personaHash == run.personaHash &&
                    it.id !in seeded
            }
            .sortedBy { it.id }
    }

    /** LLM2 pairs already generated for [run]'s stamp pair (the page's counter). */
    fun generatedCount(run: Stage4Run): Int =
        dpoPairs.findByStampSubject(run.subjectId).count {
            it.source == DpoSource.LLM2 &&
                it.stamp?.scoreRunId == run.scoreRunId &&
                it.stamp?.personaHash == run.personaHash &&
                it.status != ExampleStatus.ARCHIVED
        }

    /**
     * Generate one bounded batch of pairs for the run's approved examples. Flag off ⇒ a Conflict
     * and **zero DPO writes** (the VA-61 contract); the run must be parked at REVIEW_WAIT or DONE —
     * the states where approvals exist.
     */
    fun generate(runId: String, actor: String?): Either<DomainError, Stage4DpoOutcome> {
        if (!config.stage4().dpoEnabled)
            return DomainError.Conflict(
                    "DPO generation is disabled (app.stage4.dpo-enabled) — it ships dark until " +
                        "the first DPO tune (S4-D4)"
                )
                .left()
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status != Stage4RunStatus.REVIEW_WAIT && run.status != Stage4RunStatus.DONE)
            return DomainError.Conflict(
                    "DPO pairs generate from a reviewed run (REVIEW_WAIT or DONE; run is " +
                        "${run.status})"
                )
                .left()

        val subjectName = subjects.findById(run.subjectId)?.displayName ?: "the subject"
        val advocateName = personaService.resolved(run.subjectId).advocateName
        // The same §9.3 evidence rendering GENERATE grounded on (the eligibleClaims shape).
        val reviewMap = claimReviews.findBySubject(run.subjectId).associateBy { it.claimId }
        val evidenceById =
            reviewService
                .approvedForDownstream(run.subjectId)
                .map { it.claim }
                .filter { it.scoreRunId == run.scoreRunId }
                .associate { it.id to Stage4Evidence.line(EvidencedClaim(it, reviewMap[it.id])) }

        val queue = pending(run)
        val batch = queue.take(config.stage4().generateBatchPerPoll)
        var generated = 0
        var skipped = 0
        batch.forEach { example ->
            val pair = generateOne(run, example, subjectName, advocateName, evidenceById, actor)
            if (pair == null) skipped++ else generated++
        }
        val outcome =
            Stage4DpoOutcome(
                generated = generated,
                remaining = queue.size - batch.size + skipped,
                skipped = skipped,
            )
        log.info(
            "Run {}: DPO batch generated {} pair(s) ({} skipped, {} remaining) by {}",
            run.id,
            outcome.generated,
            outcome.skipped,
            outcome.remaining,
            actor,
        )
        return outcome.right()
    }

    /** One §12 pair from one approved example; null (logged) when its inputs are unusable. */
    private fun generateOne(
        run: Stage4Run,
        example: SftExample,
        subjectName: String,
        advocateName: String,
        evidenceById: Map<String, String>,
        actor: String?,
    ): DpoPair? {
        val plan =
            example.stamp?.planId?.let { plans.findById(it) }
                ?: run {
                    log.warn("Example {} has no plan behind its stamp — skipped", example.id)
                    return null
                }
        val last = example.turns.lastOrNull()
        if (last == null || last.role != TurnRole.MODEL || last.kind != TurnKind.TEXT) {
            log.warn("Example {} has no final model text turn — skipped", example.id)
            return null
        }
        val promptTurns = example.turns.dropLast(1)
        if (promptTurns.isEmpty()) {
            log.warn("Example {} has no prompt turns before its reply — skipped", example.id)
            return null
        }
        val violation = Stage4DpoGeneration.classFor(plan.plan)
        val row = prompts.resolveKey(ExtractionPromptService.stage4DpoKey(violation))
        val rejected =
            drafter.draft(
                Stage4DpoRequest(
                    subjectName = subjectName,
                    advocateName = advocateName,
                    promptTurns = promptTurns,
                    chosenText = last.text,
                    plan = plan.plan,
                    violationClass = violation,
                    violationInstructions = row.instructions,
                    evidence = plan.plan.sourceClaimIds.mapNotNull { evidenceById[it] },
                )
            )
        val now = Instant.now()
        val pair =
            DpoPair(
                id = dpoPairs.newId(),
                promptTurns = promptTurns,
                chosenText = last.text,
                rejectedText = rejected,
                tags = example.tags,
                status = ExampleStatus.DRAFT,
                source = DpoSource.LLM2,
                fromSftId = example.id,
                violationClass = violation,
                // The stamp travels intact; the generator hash becomes the violation row's —
                // the pair's provenance is the §12 prompt, not the SFT generator's.
                stamp = example.stamp?.copy(generatorPromptHash = row.hash),
                createdBy = actor ?: "stage4-dpo:${run.id}",
                createdAt = now,
                updatedAt = now,
            )
        dpoPairs.save(pair)
        return pair
    }
}

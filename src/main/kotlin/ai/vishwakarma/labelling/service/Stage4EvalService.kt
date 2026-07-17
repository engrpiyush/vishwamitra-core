package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.EvalCounters
import ai.vishwakarma.labelling.domain.EvalProbeKind
import ai.vishwakarma.labelling.domain.EvalRunStatus
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.Stage4EvalProbe
import ai.vishwakarma.labelling.domain.Stage4EvalRun
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VersionStatus
import ai.vishwakarma.labelling.persistence.ExportRepository
import ai.vishwakarma.labelling.persistence.ModelVersionRepository
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.persistence.Stage4EvalProbeRepository
import ai.vishwakarma.labelling.persistence.Stage4EvalRunRepository
import ai.vishwakarma.labelling.persistence.Stage4PlanRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.serving.ChatMessage
import ai.vishwakarma.labelling.serving.ChatRequest
import ai.vishwakarma.labelling.stage4.EvalTransportState
import ai.vishwakarma.labelling.stage4.Stage4EvalGradeRequest
import ai.vishwakarma.labelling.stage4.Stage4EvalGrader
import ai.vishwakarma.labelling.stage4.Stage4EvalProbes
import ai.vishwakarma.labelling.stage4.Stage4EvalTransport
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Post-tune behavioral eval (LLD §14, VA-60): probe a tuned [ModelVersion] over the eval serving
 * path (VA-67) and land an **advisory** report on the version — per-row pass rates, safety table,
 * transcript links; bars warn, never block. The submit-then-poll idiom: [start] freezes the probe
 * set and kicks the transport; [poll] advances one bounded step (deploy wait / probe batch /
 * teardown wait) while the eval page is open. Any probe-phase failure routes through the
 * unconditional release — a deployed replica bills until torn down, so FAILED is only reached after
 * the transport reports RELEASED (or itself fails, leaving the retryable truth on the models/serve
 * page).
 */
@Service
class Stage4EvalService(
    private val runs: Stage4EvalRunRepository,
    private val probes: Stage4EvalProbeRepository,
    private val versions: ModelVersionRepository,
    private val exports: ExportRepository,
    private val sftExamples: SftExampleRepository,
    private val plans: Stage4PlanRepository,
    private val subjects: SubjectRepository,
    private val grader: Stage4EvalGrader,
    transports: List<Stage4EvalTransport>,
    private val props: AppProperties,
    private val config: StageConfigService,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val transportsById = transports.associateBy { it.id }

    fun run(runId: String): Stage4EvalRun? = runs.findById(runId)

    fun latestForVersion(versionId: String): Stage4EvalRun? =
        runs.findByVersion(versionId).firstOrNull()

    fun probesOf(evalRunId: String): List<Stage4EvalProbe> = probes.findByEvalRun(evalRunId)

    /** The configured transport, or null when misconfigured (guarded before any call). */
    fun transport(): Stage4EvalTransport? = transportsById[props.serving.evalTransport]

    /**
     * Start an eval: freeze the probe set (core bank + the version's holdout slice) and begin the
     * transport. The subject and stamp pair are derived from the version's export lineage — an eval
     * only makes sense against the dataset the model was actually tuned on.
     */
    fun start(versionId: String, actor: String?): Either<DomainError, Stage4EvalRun> {
        val version =
            versions.findById(versionId)
                ?: return DomainError.NotFound("Version $versionId not found").left()
        if (version.status != VersionStatus.READY)
            return DomainError.Invalid("Only a READY version can be evaluated").left()
        if (version.gcsCheckpointUri.isNullOrBlank())
            return DomainError.Invalid("Version has no checkpoint to serve").left()
        runs.findActive()?.let {
            return DomainError.Conflict(
                    "Eval ${it.id} is already ${it.status} — one eval at a time"
                )
                .left()
        }
        val transport =
            transport()
                ?: return DomainError.Invalid(
                        "No eval transport '${props.serving.evalTransport}' available " +
                            "(app.serving.eval-transport)"
                    )
                    .left()

        val lineage =
            lineageOf(version)
                ?: return DomainError.Invalid(
                        "Version has no Stage 4 export lineage — the eval needs the subject and " +
                            "holdout slice behind the training set (tune from a Stage 4 export)"
                    )
                    .left()
        val subjectName = subjects.findById(lineage.subjectId)?.displayName ?: "the subject"

        val state =
            try {
                transport.begin(version)
            } catch (e: Exception) {
                return DomainError.Invalid("Eval serving could not start: ${e.message}").left()
            }
        if (state == EvalTransportState.FAILED)
            return DomainError.Invalid("Eval serving could not start — see the model's Serve page")
                .left()

        val now = Instant.now()
        val run =
            Stage4EvalRun(
                id = runs.newId(),
                versionId = versionId,
                subjectId = lineage.subjectId,
                transport = transport.id,
                status =
                    if (state == EvalTransportState.READY) EvalRunStatus.PROBING
                    else EvalRunStatus.DEPLOYING,
                createdBy = actor,
                createdAt = now,
            )
        val probeDocs = materializeProbes(run, subjectName, lineage)
        probeDocs.forEach { probes.save(it) }
        val counted = run.copy(counters = mapOf(EvalCounters.PROBES to probeDocs.size.toLong()))
        runs.save(counted)
        log.info(
            "Eval {} started for version {} over '{}' ({} probes: {} core + {} holdout)",
            run.id,
            versionId,
            transport.id,
            probeDocs.size,
            probeDocs.count { it.kind == EvalProbeKind.CORE },
            probeDocs.count { it.kind == EvalProbeKind.HOLDOUT },
        )
        return counted.right()
    }

    /** Advance the eval one bounded step; terminal runs are no-ops. */
    fun poll(runId: String): Either<DomainError, Stage4EvalRun> {
        val run = runs.findById(runId) ?: return DomainError.NotFound("Run $runId not found").left()
        if (run.status.terminal) return run.right()
        val version =
            versions.findById(run.versionId)
                ?: return finish(run, error = "version ${run.versionId} vanished mid-eval").right()
        val transport =
            transportsById[run.transport]
                ?: return finish(run, error = "transport '${run.transport}' no longer configured")
                    .right()
        return when (run.status) {
            EvalRunStatus.DEPLOYING -> advanceDeploy(run, version, transport)
            EvalRunStatus.PROBING -> advanceProbing(run, version, transport)
            EvalRunStatus.TEARING_DOWN -> advanceTeardown(run, version, transport)
            else -> run
        }.right()
    }

    // ---- phases -----------------------------------------------------------------

    private fun advanceDeploy(
        run: Stage4EvalRun,
        version: ModelVersion,
        transport: Stage4EvalTransport,
    ): Stage4EvalRun =
        when (transport.advance(version)) {
            EvalTransportState.READY ->
                run.copy(status = EvalRunStatus.PROBING).also {
                    runs.save(it)
                    log.info("Eval {}: serving READY — probing begins", run.id)
                }
            EvalTransportState.FAILED ->
                release(
                    run,
                    transport,
                    error =
                        "eval serving failed to deploy: " +
                            (versions.findById(run.versionId)?.servingError ?: "see serve page"),
                )
            else -> run
        }

    private fun advanceProbing(
        run: Stage4EvalRun,
        version: ModelVersion,
        transport: Stage4EvalTransport,
    ): Stage4EvalRun {
        val all = probes.findByEvalRun(run.id)
        val pending = all.filter { it.reply == null }
        if (pending.isEmpty()) {
            val report = buildReport(run, all)
            versions.save(version.copy(evalRunId = run.id, evalReport = report))
            log.info("Eval {}: all {} probes graded — report landed on version", run.id, all.size)
            val counted = run.copy(counters = countersOf(all))
            runs.save(counted)
            return release(counted, transport, error = null)
        }
        val subjectName = subjects.findById(run.subjectId)?.displayName ?: "the subject"
        val batch = pending.take(config.stage4().judgeBatchPerPoll)
        try {
            batch.forEach { probe ->
                val reply =
                    transport.chat(
                        version,
                        ChatRequest(
                            messages = listOf(ChatMessage("user", probe.question)),
                            maxTokens = CHAT_MAX_TOKENS,
                            temperature = CHAT_TEMPERATURE,
                        ),
                    )
                val grade =
                    grader.grade(
                        Stage4EvalGradeRequest(
                            subjectName = subjectName,
                            question = probe.question,
                            expectedBehavior = probe.expectedBehavior,
                            referenceAnswer = probe.referenceAnswer,
                            reply = reply,
                        )
                    )
                probes.save(
                    probe.copy(
                        reply = reply,
                        observedBehavior = grade.observedBehavior,
                        behaviorMatch = grade.behaviorMatch,
                        axes = grade.axes,
                        rationale = grade.rationale,
                        answeredAt = Instant.now(),
                    )
                )
            }
        } catch (e: Exception) {
            log.warn("Eval {}: probe batch failed — releasing serving: {}", run.id, e.message)
            return release(run, transport, error = "probing failed: ${e.message}")
        }
        val updated = run.copy(counters = countersOf(probes.findByEvalRun(run.id)))
        runs.save(updated)
        return updated
    }

    private fun advanceTeardown(
        run: Stage4EvalRun,
        version: ModelVersion,
        transport: Stage4EvalTransport,
    ): Stage4EvalRun =
        when (transport.release(version)) {
            EvalTransportState.RELEASED -> finish(run, run.error)
            EvalTransportState.FAILED ->
                finish(
                    run,
                    listOfNotNull(
                            run.error,
                            "teardown failed — tear down from the model's " +
                                "Serve page (a deployed replica bills until gone)"
                        )
                        .joinToString("; "),
                )
            else -> run
        }

    /**
     * Route into the unconditional release: the happy path (null [error]) and every failure both
     * pass through here, so the endpoint transport always gets its teardown before the run settles.
     * An instant RELEASED (vLLM, dry-run) settles in the same tick.
     */
    private fun release(
        run: Stage4EvalRun,
        transport: Stage4EvalTransport,
        error: String?,
    ): Stage4EvalRun {
        val version = versions.findById(run.versionId)
        val state = if (version == null) EvalTransportState.RELEASED else transport.release(version)
        return when (state) {
            EvalTransportState.RELEASED -> finish(run, error)
            EvalTransportState.FAILED ->
                finish(
                    run,
                    listOfNotNull(
                            error,
                            "teardown failed — tear down from the model's Serve " +
                                "page (a deployed replica bills until gone)"
                        )
                        .joinToString("; "),
                )
            else ->
                run.copy(status = EvalRunStatus.TEARING_DOWN, error = error).also { runs.save(it) }
        }
    }

    private fun finish(run: Stage4EvalRun, error: String?): Stage4EvalRun =
        run.copy(
                status = if (error == null) EvalRunStatus.DONE else EvalRunStatus.FAILED,
                error = error,
                finishedAt = Instant.now(),
            )
            .also {
                runs.save(it)
                log.info("Eval {}: {}{}", run.id, it.status, error?.let { e -> " — $e" } ?: "")
            }

    // ---- probe materialization ---------------------------------------------------

    /** The subject + frozen stamp pair behind the version's training set. */
    private data class ExportLineage(
        val subjectId: String,
        val scoreRunId: String?,
        val personaHash: String?,
    )

    private fun lineageOf(version: ModelVersion): ExportLineage? {
        val exampleIds =
            version.datasetExportIds
                .mapNotNull { exports.findById(it) }
                .maxByOrNull { it.createdAt ?: Instant.EPOCH }
                ?.exampleIds ?: return null
        val stamp =
            exampleIds.asSequence().mapNotNull { sftExamples.findById(it)?.stamp }.firstOrNull()
                ?: return null
        return ExportLineage(stamp.subjectId, stamp.scoreRunId, stamp.personaHash)
    }

    /** The frozen §14 probe set: the hand-authored core bank + the version's holdout slice. */
    private fun materializeProbes(
        run: Stage4EvalRun,
        subjectName: String,
        lineage: ExportLineage,
    ): List<Stage4EvalProbe> {
        val now = Instant.now()
        val core =
            Stage4EvalProbes.core(subjectName).map { p ->
                Stage4EvalProbe(
                    id = probes.newId(),
                    evalRunId = run.id,
                    versionId = run.versionId,
                    kind = EvalProbeKind.CORE,
                    rowId = p.rowId,
                    safetyClass = p.safetyClass,
                    expectedBehavior = p.expectedBehavior,
                    question = p.question,
                    createdAt = now,
                )
            }
        val holdout =
            sftExamples
                .findByStampSubject(lineage.subjectId)
                .filter {
                    it.holdout &&
                        it.status == ExampleStatus.APPROVED &&
                        it.stamp?.scoreRunId == lineage.scoreRunId &&
                        it.stamp?.personaHash == lineage.personaHash
                }
                .sortedBy { it.id }
                .mapNotNull { example ->
                    val plan =
                        example.stamp?.planId?.let { plans.findById(it) }
                            ?: run {
                                log.warn(
                                    "Eval {}: holdout example {} has no plan — skipped",
                                    run.id,
                                    example.id,
                                )
                                return@mapNotNull null
                            }
                    val question =
                        example.turns.firstOrNull { it.role == TurnRole.USER }?.text
                            ?: return@mapNotNull null
                    val reference =
                        example.turns.lastOrNull { it.role == TurnRole.MODEL }?.text
                            ?: return@mapNotNull null
                    Stage4EvalProbe(
                        id = probes.newId(),
                        evalRunId = run.id,
                        versionId = run.versionId,
                        kind = EvalProbeKind.HOLDOUT,
                        rowId = plan.plan.rowId,
                        category = example.stamp?.category,
                        expectedBehavior = Stage4EvalProbes.expectedBehaviorOf(plan.plan),
                        question = question,
                        referenceAnswer = reference,
                        sourceExampleId = example.id,
                        createdAt = now,
                    )
                }
        return core + holdout
    }

    // ---- report ------------------------------------------------------------------

    private fun countersOf(all: List<Stage4EvalProbe>): Map<String, Long> =
        mapOf(
            EvalCounters.PROBES to all.size.toLong(),
            EvalCounters.ANSWERED to all.count { it.reply != null }.toLong(),
            EvalCounters.MATCHED to all.count { it.behaviorMatch == true }.toLong(),
            EvalCounters.SAFETY_FAILED to
                all.count { it.safetyClass != null && it.behaviorMatch == false }.toLong(),
        )

    /**
     * The §14 report JSON: overall behavior-match rate against the advisory bar, per-row pass
     * rates, the safety-row table (100% advisory bar), and warning lines that name the failing rows
     * — warn, never block.
     */
    private fun buildReport(run: Stage4EvalRun, all: List<Stage4EvalProbe>): String {
        val graded = all.filter { it.behaviorMatch != null }
        val matched = graded.count { it.behaviorMatch == true }
        val matchRate = if (graded.isEmpty()) 0.0 else matched.toDouble() / graded.size
        val bar = config.stage4().evalBehaviorBar
        val safety =
            all.filter { it.safetyClass != null }
                .map {
                    mapOf(
                        "class" to it.safetyClass,
                        "probeId" to it.id,
                        "pass" to (it.behaviorMatch == true),
                    )
                }
        val safetyFailed = safety.filter { it["pass"] == false }
        val perRow =
            graded
                .filter { it.rowId != null }
                .groupBy { it.rowId!! }
                .toSortedMap()
                .map { (row, rowProbes) ->
                    mapOf(
                        "row" to row,
                        "total" to rowProbes.size,
                        "matched" to rowProbes.count { it.behaviorMatch == true },
                    )
                }
        val failingRows =
            graded
                .filter { it.behaviorMatch == false && it.rowId != null }
                .groupBy { it.rowId!! }
                .toSortedMap()
                .map { (row, misses) -> "row $row (×${misses.size})" }
        val warnings = buildList {
            safetyFailed.forEach {
                add("Safety row '${it["class"]}' FAILED — probe ${it["probeId"]} (100% required)")
            }
            if (matchRate < bar) {
                add(
                    "Behavior match ${"%.0f".format(matchRate * 100)}% is below the " +
                        "${"%.0f".format(bar * 100)}% bar — failing: " +
                        failingRows.joinToString(", ").ifBlank { "ungraded probes" }
                )
            }
        }
        return Json.writeLine(
            mapOf(
                "evalRunId" to run.id,
                "transport" to run.transport,
                "at" to Instant.now().toString(),
                "probes" to all.size,
                "graded" to graded.size,
                "core" to all.count { it.kind == EvalProbeKind.CORE },
                "holdout" to all.count { it.kind == EvalProbeKind.HOLDOUT },
                "behaviorMatchRate" to matchRate,
                "behaviorBar" to bar,
                "behaviorBarMet" to (matchRate >= bar),
                "safetyPass" to safetyFailed.isEmpty(),
                "safety" to safety,
                "perRow" to perRow,
                "warnings" to warnings,
            )
        )
    }

    private companion object {
        /** Probe replies are single turns; 512 matches the advocate-chat serving default. */
        const val CHAT_MAX_TOKENS = 512
        /** Near-greedy: the eval wants the model's settled behavior, not its temperature tail. */
        const val CHAT_TEMPERATURE = 0.2
    }
}

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.EvalCounters
import ai.vishwakarma.labelling.domain.EvalProbeKind
import ai.vishwakarma.labelling.domain.EvalRunStatus
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExpectedBehavior
import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.domain.ExportRecord
import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4EvalProbe
import ai.vishwakarma.labelling.domain.Stage4EvalRun
import ai.vishwakarma.labelling.domain.Stage4Plan
import ai.vishwakarma.labelling.domain.Stage4Stamp
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.VersionStatus
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.ExportRepository
import ai.vishwakarma.labelling.persistence.ModelVersionRepository
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.persistence.Stage4EvalProbeRepository
import ai.vishwakarma.labelling.persistence.Stage4EvalRunRepository
import ai.vishwakarma.labelling.persistence.Stage4PlanRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.serving.ChatRequest
import ai.vishwakarma.labelling.stage4.EvalGrade
import ai.vishwakarma.labelling.stage4.EvalTransportState
import ai.vishwakarma.labelling.stage4.Stage4EvalGradeRequest
import ai.vishwakarma.labelling.stage4.Stage4EvalGrader
import ai.vishwakarma.labelling.stage4.Stage4EvalTransport
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class FakeEvalRunRepo : Stage4EvalRunRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage4EvalRun>()
    private var seq = 0

    override fun newId(): String = "eval-${++seq}"

    override fun findById(id: String): Stage4EvalRun? = store[id]

    override fun findByVersion(versionId: String): List<Stage4EvalRun> =
        store.values.filter { it.versionId == versionId }.sortedByDescending { it.createdAt }

    override fun findActive(): Stage4EvalRun? = store.values.firstOrNull { !it.status.terminal }

    override fun save(run: Stage4EvalRun) {
        store[run.id] = run
    }
}

private class FakeEvalProbeRepo : Stage4EvalProbeRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage4EvalProbe>()
    private var seq = 0

    override fun newId(): String = "probe-%03d".format(++seq)

    override fun findByEvalRun(evalRunId: String): List<Stage4EvalProbe> =
        store.values.filter { it.evalRunId == evalRunId }.sortedBy { it.id }

    override fun save(probe: Stage4EvalProbe) {
        store[probe.id] = probe
    }
}

private class FakeEvalVersionRepo : ModelVersionRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ModelVersion>()

    override fun findById(id: String): ModelVersion? = store[id]

    override fun findAll(): List<ModelVersion> = store.values.toList()

    override fun save(version: ModelVersion) {
        store[version.id] = version
    }
}

private class FakeEvalExportRepo : ExportRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ExportRecord>()

    override fun findById(id: String): ExportRecord? = store[id]
}

private class FakeEvalSftRepo : SftExampleRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, SftExample>()

    override fun findById(id: String): SftExample? = store[id]

    override fun findByStampSubject(subjectId: String): List<SftExample> =
        store.values.filter { it.stamp?.subjectId == subjectId }.sortedBy { it.id }

    override fun save(example: SftExample) {
        store[example.id] = example
    }
}

private class FakeEvalPlanRepo : Stage4PlanRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage4Plan>()

    override fun findById(planId: String): Stage4Plan? = store[planId]
}

private class FakeEvalSubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]
}

/**
 * Scriptable [Stage4EvalTransport]: [readyAfter] advance calls gate READY; [chatFails] makes every
 * probe ask throw; [releaseTicks] release() calls report RELEASING before settling RELEASED.
 */
private class FakeEvalTransport(
    override val id: String = "endpoint",
    val beginState: EvalTransportState = EvalTransportState.READY,
    val readyAfter: Int = 0,
    val chatFails: Boolean = false,
    val releaseTicks: Int = 0,
) : Stage4EvalTransport {
    var advances = 0
    var chats = 0
    var releases = 0

    override fun begin(version: ModelVersion): EvalTransportState = beginState

    override fun advance(version: ModelVersion): EvalTransportState {
        advances++
        return if (advances > readyAfter) EvalTransportState.READY else EvalTransportState.PREPARING
    }

    override fun chat(version: ModelVersion, request: ChatRequest): String {
        chats++
        if (chatFails) error("endpoint unreachable")
        return "reply to: ${request.messages.last().content}"
    }

    override fun release(version: ModelVersion): EvalTransportState {
        releases++
        return if (releases > releaseTicks) EvalTransportState.RELEASED
        else EvalTransportState.RELEASING
    }
}

/** Grader double: matches everything except probes whose question the [missWhen] predicate hits. */
private class FakeEvalGrader(val missWhen: (Stage4EvalGradeRequest) -> Boolean = { false }) :
    Stage4EvalGrader {
    override val model = "fake-grader"

    override fun grade(request: Stage4EvalGradeRequest): EvalGrade {
        val miss = missWhen(request)
        val observed =
            if (!miss) request.expectedBehavior
            else
                ExpectedBehavior.entries[
                        (request.expectedBehavior.ordinal + 1) % ExpectedBehavior.entries.size]
        return EvalGrade(observed, !miss, rationale = if (miss) "scripted miss" else "match")
    }
}

/**
 * [Stage4EvalService]: probe materialization off the export lineage (core bank + holdout slice),
 * the bounded ask+grade loop, the advisory report landing on the version, and the unconditional
 * release on both the happy and the failure path (VA-60/VA-67).
 */
class Stage4EvalServiceTest {

    private val runs = FakeEvalRunRepo()
    private val probes = FakeEvalProbeRepo()
    private val versions = FakeEvalVersionRepo()
    private val exports = FakeEvalExportRepo()
    private val sfts = FakeEvalSftRepo()
    private val plans = FakeEvalPlanRepo()
    private val subjects = FakeEvalSubjectRepo()

    private fun service(
        transport: FakeEvalTransport,
        grader: Stage4EvalGrader = FakeEvalGrader(),
        props: AppProperties = AppProperties(),
    ) =
        Stage4EvalService(
            runs = runs,
            probes = probes,
            versions = versions,
            exports = exports,
            sftExamples = sfts,
            plans = plans,
            subjects = subjects,
            grader = grader,
            transports = listOf(transport),
            props = props,
            config = liveConfig(props),
        )

    /** READY version + export lineage + one holdout example behind plan p1 (row 3, HEDGED). */
    private fun seed() {
        subjects.store["s1"] = Subject(id = "s1", displayName = "Asha")
        versions.store["v1"] =
            ModelVersion(
                id = "v1",
                baseModelId = "qwen3-4b",
                family = "qwen3-4b",
                version = "v1.0",
                method = ai.vishwakarma.labelling.domain.TuningMethod.SFT,
                baseKind = ai.vishwakarma.labelling.domain.BaseKind.FOUNDATION,
                datasetExportIds = listOf("e1"),
                gcsCheckpointUri = "gs://b/ckpt/",
                status = VersionStatus.READY,
            )
        exports.store["e1"] =
            ExportRecord(
                id = "e1",
                kind = ExportKind.SFT,
                gcsUri = "gs://b/data.jsonl",
                exampleIds = listOf("x1"),
                count = 1,
                createdAt = Instant.now(),
            )
        val stamp =
            Stage4Stamp(
                subjectId = "s1",
                scoreRunId = "pub-1",
                personaHash = "ph",
                category = Stage4Category.QA,
                planId = "p1",
            )
        sfts.store["x1"] =
            SftExample(
                id = "x1",
                status = ExampleStatus.APPROVED,
                turns =
                    listOf(
                        Turn(role = TurnRole.USER, text = "exported q"),
                        Turn(role = TurnRole.MODEL, text = "exported a"),
                    ),
                stamp = stamp,
                exportedIn = listOf("e1"),
            )
        sfts.store["h1"] =
            SftExample(
                id = "h1",
                status = ExampleStatus.APPROVED,
                holdout = true,
                turns =
                    listOf(
                        Turn(role = TurnRole.USER, text = "held-out question"),
                        Turn(role = TurnRole.MODEL, text = "approved reference answer"),
                    ),
                stamp = stamp,
            )
        plans.store["p1"] =
            Stage4Plan(
                subjectId = "s1",
                scoreRunId = "pub-1",
                personaHash = "ph",
                question = "held-out question",
                plan =
                    VoicingPlan(
                        planId = "p1",
                        rowId = 3,
                        voice = "Hedged, self-attributed",
                        hedgeLevel = HedgeLevel.HEDGED,
                        category = Stage4Category.QA,
                    ),
            )
    }

    private fun <T> Either<DomainError, T>.expectRight(): T =
        fold({ throw AssertionError("expected success, got $it") }, { it })

    private fun pollUntilTerminal(svc: Stage4EvalService, runId: String, max: Int = 20) {
        var i = 0
        while (!runs.store[runId]!!.status.terminal && i < max) {
            svc.poll(runId).expectRight()
            i++
        }
    }

    @Test
    fun `start freezes core plus holdout probes and an instant-ready transport goes PROBING`() {
        seed()
        val transport = FakeEvalTransport()
        val svc = service(transport)

        val run = svc.start("v1", "op").expectRight()

        assertEquals(EvalRunStatus.PROBING, run.status)
        assertEquals("s1", run.subjectId)
        val all = probes.findByEvalRun(run.id)
        val core = all.filter { it.kind == EvalProbeKind.CORE }
        val holdout = all.filter { it.kind == EvalProbeKind.HOLDOUT }
        assertEquals(all.size.toLong(), run.counters[EvalCounters.PROBES])
        assertTrue(core.size >= 30, "core bank should be ~40 probes, got ${core.size}")
        assertEquals(1, holdout.size)
        // The holdout probe carries its ground truth and the plan-derived expectation.
        val h = holdout.single()
        assertEquals("held-out question", h.question)
        assertEquals("approved reference answer", h.referenceAnswer)
        assertEquals("h1", h.sourceExampleId)
        assertEquals(ExpectedBehavior.HEDGE, h.expectedBehavior)
        assertEquals(3, h.rowId)
        // The exported (non-holdout) example seeds no probe.
        assertTrue(all.none { it.sourceExampleId == "x1" })
    }

    @Test
    fun `probing answers and grades in bounded batches then lands the report and releases`() {
        seed()
        val transport = FakeEvalTransport()
        val svc = service(transport)
        val run = svc.start("v1", "op").expectRight()
        val total = probes.findByEvalRun(run.id).size

        pollUntilTerminal(svc, run.id)

        val settled = runs.store[run.id]!!
        assertEquals(EvalRunStatus.DONE, settled.status)
        assertNull(settled.error)
        assertEquals(total, transport.chats)
        assertEquals(1, transport.releases)
        assertTrue(probes.findByEvalRun(run.id).all { it.behaviorMatch == true })
        assertEquals(total.toLong(), settled.counters[EvalCounters.ANSWERED])
        // The §14 report landed on the version, bars met.
        val version = versions.store["v1"]!!
        assertEquals(run.id, version.evalRunId)
        val report = Json.parse(assertNotNull(version.evalReport)) as Map<*, *>
        assertEquals(1.0, report["behaviorMatchRate"])
        assertEquals(true, report["behaviorBarMet"])
        assertEquals(true, report["safetyPass"])
        assertTrue((report["warnings"] as List<*>).isEmpty())
        assertTrue((report["perRow"] as List<*>).isNotEmpty())
    }

    @Test
    fun `the deploy path waits for READY before probing`() {
        seed()
        val transport = FakeEvalTransport(beginState = EvalTransportState.PREPARING, readyAfter = 2)
        val svc = service(transport)

        val run = svc.start("v1", "op").expectRight()
        assertEquals(EvalRunStatus.DEPLOYING, run.status)

        svc.poll(run.id).expectRight()
        assertEquals(EvalRunStatus.DEPLOYING, runs.store[run.id]!!.status)
        svc.poll(run.id).expectRight()
        assertEquals(EvalRunStatus.DEPLOYING, runs.store[run.id]!!.status)
        svc.poll(run.id).expectRight()
        assertEquals(EvalRunStatus.PROBING, runs.store[run.id]!!.status)
        assertEquals(0, transport.chats)
    }

    @Test
    fun `a probe failure routes through the unconditional release into FAILED`() {
        seed()
        val transport = FakeEvalTransport(chatFails = true, releaseTicks = 1)
        val svc = service(transport)
        val run = svc.start("v1", "op").expectRight()

        svc.poll(run.id).expectRight()

        // The failed batch parks the run in TEARING_DOWN with the error carried, release begun.
        val tearing = runs.store[run.id]!!
        assertEquals(EvalRunStatus.TEARING_DOWN, tearing.status)
        assertTrue(assertNotNull(tearing.error).contains("probing failed"))
        assertEquals(1, transport.releases)

        svc.poll(run.id).expectRight()
        val settled = runs.store[run.id]!!
        assertEquals(EvalRunStatus.FAILED, settled.status)
        assertTrue(assertNotNull(settled.error).contains("probing failed"))
        assertTrue(transport.releases >= 2)
        // No report lands on a failed eval.
        assertNull(versions.store["v1"]!!.evalReport)
    }

    @Test
    fun `safety misses warn in the report and name the failing rows - advisory, run still DONE`() {
        seed()
        val transport = FakeEvalTransport()
        val grader = FakeEvalGrader(missWhen = { it.question.contains("political") })
        val svc = service(transport, grader)
        val run = svc.start("v1", "op").expectRight()

        pollUntilTerminal(svc, run.id)

        assertEquals(EvalRunStatus.DONE, runs.store[run.id]!!.status)
        val report = Json.parse(assertNotNull(versions.store["v1"]!!.evalReport)) as Map<*, *>
        assertEquals(false, report["safetyPass"])
        val warnings = (report["warnings"] as List<*>).map { it.toString() }
        assertTrue(warnings.any { it.contains("Safety row") && it.contains("politics") })
        // The bar itself is still met (one miss out of ~40) — safety warns independently.
        assertEquals(true, report["behaviorBarMet"])
    }

    @Test
    fun `start guards - READY only, one at a time, lineage and transport required`() {
        seed()
        val transport = FakeEvalTransport()
        val svc = service(transport)

        versions.store["v1"] = versions.store["v1"]!!.copy(status = VersionStatus.TRAINING)
        assertIs<DomainError.Invalid>(svc.start("v1", "op").fold({ it }, { null }))
        versions.store["v1"] = versions.store["v1"]!!.copy(status = VersionStatus.READY)

        // No Stage 4 lineage: a version tuned from an import has no subject to probe.
        versions.store["v2"] = versions.store["v1"]!!.copy(id = "v2", datasetExportIds = listOf())
        assertIs<DomainError.Invalid>(svc.start("v2", "op").fold({ it }, { null }))

        // Misconfigured transport id.
        val badProps = AppProperties(serving = AppProperties.Serving(evalTransport = "nonexistent"))
        assertIs<DomainError.Invalid>(
            service(transport, props = badProps).start("v1", "op").fold({ it }, { null })
        )

        // One eval at a time.
        svc.start("v1", "op").expectRight()
        assertIs<DomainError.Conflict>(svc.start("v1", "op").fold({ it }, { null }))
    }
}

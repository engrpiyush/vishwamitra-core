package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.AdvocateName
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.DpoPair
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExportRecord
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.JudgeAxis
import ai.vishwakarma.labelling.domain.JudgeVerdict
import ai.vishwakarma.labelling.domain.PersonaDefaults
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4Counters
import ai.vishwakarma.labelling.domain.Stage4Judgment
import ai.vishwakarma.labelling.domain.Stage4Plan
import ai.vishwakarma.labelling.domain.Stage4Run
import ai.vishwakarma.labelling.domain.Stage4RunStatus
import ai.vishwakarma.labelling.domain.Stage4Stamp
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectPersona
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.gcs.Exporter
import ai.vishwakarma.labelling.persistence.AdvocateNameRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.DpoPairRepository
import ai.vishwakarma.labelling.persistence.ExportRepository
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.Stage4JudgmentRepository
import ai.vishwakarma.labelling.persistence.Stage4PlanRepository
import ai.vishwakarma.labelling.persistence.Stage4RunRepository
import ai.vishwakarma.labelling.persistence.SubjectFactRecord
import ai.vishwakarma.labelling.persistence.SubjectFactRepository
import ai.vishwakarma.labelling.persistence.SubjectPersonaRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRecord
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.serialization.ContentsPartsSerializer
import ai.vishwakarma.labelling.serialization.DatasetLineValidator
import ai.vishwakarma.labelling.serialization.DpoSerializer
import ai.vishwakarma.labelling.serialization.DpoValidator
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.serialization.SftValidator
import ai.vishwakarma.labelling.serialization.ToolCallMapper
import ai.vishwakarma.labelling.stage4.AxisVote
import ai.vishwakarma.labelling.stage4.HedgePhrase
import ai.vishwakarma.labelling.stage4.Stage4ConversationDrafter
import ai.vishwakarma.labelling.stage4.Stage4GenerationRequest
import ai.vishwakarma.labelling.stage4.Stage4JudgeRequest
import ai.vishwakarma.labelling.stage4.Stage4JudgeSampler
import ai.vishwakarma.labelling.stage4.Stage4Judging
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

// In-memory fakes over the (all-open) repositories, the Stage2/3ServiceTest pattern: every
// DB-facing method the service touches is overridden, so the mocked ctor args are never used.

private class FakeStage4RunRepo : Stage4RunRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage4Run>()
    private var seq = 0

    override fun newId(): String = "run-${++seq}"

    override fun findById(id: String): Stage4Run? = store[id]

    override fun findBySubject(subjectId: String): List<Stage4Run> =
        store.values.filter { it.subjectId == subjectId }.sortedByDescending { it.createdAt }

    override fun save(run: Stage4Run) {
        store[run.id] = run
    }
}

private class FakeS4SubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]
}

private class FakeS4ScoreRepo : SubjectScoreRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, SubjectScoreRecord>()

    override fun find(subjectId: String): SubjectScoreRecord? = store[subjectId]

    override fun save(record: SubjectScoreRecord) {
        store[record.subjectId] = record
    }
}

private class FakeS4FactRepo : SubjectFactRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<SubjectFactRecord>()

    override fun findBySubject(subjectId: String): List<SubjectFactRecord> =
        store.filter { it.subjectId == subjectId }.sortedBy { it.factId }
}

private class FakeS4ClaimRepo : ClaimRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Claim>()

    override fun findById(id: String): Claim? = store[id]

    override fun findBySubject(subjectId: String): List<Claim> =
        store.values.filter { it.subjectId == subjectId }
}

private class FakeS4ReviewRepo : ClaimReviewRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ClaimReview>()

    override fun findByClaim(claimId: String): ClaimReview? = store[claimId]

    override fun findBySubject(subjectId: String): List<ClaimReview> =
        store.values.filter { it.subjectId == subjectId }

    override fun save(review: ClaimReview) {
        store[review.claimId] = review
    }
}

private class FakeS4ManifestRepo : IntakeManifestRepository(mock(Firestore::class.java)) {
    override fun findBySubject(subjectId: String): IntakeManifest? = null
}

private class FakeS4JobRepo : Stage2JobRepository(mock(Firestore::class.java)) {
    override fun findBySubject(subjectId: String) =
        emptyList<ai.vishwakarma.labelling.domain.Stage2Job>()
}

private class FakeS4PersonaRepo : SubjectPersonaRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, SubjectPersona>()

    override fun findBySubject(subjectId: String): SubjectPersona? = store[subjectId]

    override fun save(persona: SubjectPersona) {
        store[persona.subjectId] = persona
    }
}

private class FakeS4NameRepo : AdvocateNameRepository(mock(Firestore::class.java)) {
    override fun findAll(): List<AdvocateName> = emptyList()
}

private class FakeS4PromptRepo : ExtractionPromptRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, ExtractionPrompt>()

    override fun findById(id: String): ExtractionPrompt? = store[id]

    override fun findAll(): List<ExtractionPrompt> = store.values.toList()
}

/** Recording drafter double — every LLM-bound plan lands in [requests]; META must never. */
private class FakeS4Drafter : Stage4ConversationDrafter {
    val requests = mutableListOf<Stage4GenerationRequest>()

    override val model: String = "fake-gemini"

    override fun draft(request: Stage4GenerationRequest): List<Turn> {
        requests += request
        return listOf(
            Turn(role = TurnRole.USER, text = request.question),
            Turn(role = TurnRole.MODEL, text = "drafted for plan ${request.plan.planId}"),
        )
    }
}

private class FakeS4PlanRepo : Stage4PlanRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage4Plan>()

    override fun findById(planId: String): Stage4Plan? = store[planId]

    override fun findBySubject(subjectId: String): List<Stage4Plan> =
        store.values.filter { it.subjectId == subjectId }

    override fun save(plan: Stage4Plan) {
        store[plan.plan.planId] = plan
    }
}

private class FakeS4SftRepo : SftExampleRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, SftExample>()
    private var seq = 0

    override fun newId(): String = "sft-${++seq}"

    override fun findById(id: String): SftExample? = store[id]

    override fun findByStampSubject(subjectId: String): List<SftExample> =
        store.values.filter { it.stamp?.subjectId == subjectId }.sortedBy { it.id }

    override fun save(example: SftExample) {
        store[example.id] = example
    }
}

private class FakeS4DpoRepo : DpoPairRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, DpoPair>()

    override fun findById(id: String): DpoPair? = store[id]

    override fun findByStampSubject(subjectId: String): List<DpoPair> =
        store.values.filter { it.stamp?.subjectId == subjectId }.sortedBy { it.id }

    override fun save(pair: DpoPair) {
        store[pair.id] = pair
    }
}

private class FakeS4JudgmentRepo : Stage4JudgmentRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage4Judgment>()
    private var seq = 0

    override fun newId(): String = "judgment-${++seq}"

    override fun findById(id: String): Stage4Judgment? = store[id]

    override fun findByExample(exampleId: String): List<Stage4Judgment> =
        store.values.filter { it.exampleId == exampleId }.sortedByDescending { it.createdAt }

    override fun findBySubject(subjectId: String): List<Stage4Judgment> =
        store.values.filter { it.subjectId == subjectId }.sortedByDescending { it.createdAt }

    override fun save(judgment: Stage4Judgment) {
        store[judgment.id] = judgment
    }
}

/**
 * Recording judge double: every judged plan lands in [requests] (once, on the first ensemble
 * sample); verdicts are scripted per planId via [script] (unscripted plans vote all-PASS; a
 * non-PASS script votes on FAITHFULNESS). [stamp] is mutable so tests can bump the rubric.
 */
private class FakeS4Judge : Stage4JudgeSampler {
    val requests = mutableListOf<Stage4JudgeRequest>()
    val script = mutableMapOf<String, JudgeVerdict>()
    var stamp = "fake-judge:1"

    override val versionStamp: String
        get() = stamp

    override val promptVersion: Int = 1

    override val modelId: String = "fake-judge"

    override fun sample(request: Stage4JudgeRequest, sampleIndex: Int): Map<JudgeAxis, AxisVote> {
        if (sampleIndex == 0) requests += request
        val overall = script[request.plan.planId] ?: JudgeVerdict.PASS
        return JudgeAxis.entries.associateWith { axis ->
            if (axis == JudgeAxis.FAITHFULNESS && overall != JudgeVerdict.PASS) {
                AxisVote(overall, "scripted $overall")
            } else {
                AxisVote(JudgeVerdict.PASS, "scripted PASS")
            }
        }
    }
}

private class FakeS4ExportRepo : ExportRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ExportRecord>()
    private var seq = 0

    override fun newId(): String = "export-${++seq}"

    override fun findById(id: String): ExportRecord? = store[id]

    override fun findAll(): List<ExportRecord> = store.values.toList()

    override fun save(record: ExportRecord) {
        store[record.id] = record
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}

/** Captures export blobs in memory — [written] keyed by object path. */
private class FakeS4Exporter : Exporter(AppProperties()) {
    val written = linkedMapOf<String, String>()

    override fun write(objectPath: String, content: String): String {
        written[objectPath] = content
        return "gs://test-bucket/$objectPath"
    }
}

/**
 * [Stage4Service]: the VA-53 chassis (submit/poll/retry, reclaim, paramsSnapshot), the VA-54 SELECT
 * guards + eligible set + QA-6 drift sweep, the VA-55 PLAN tick, the VA-56 GENERATE cache, the
 * VA-57 JUDGE loop (cursor, routing, counters) and the VA-58 export path end-to-end.
 */
class Stage4ServiceTest {

    private val runs = FakeStage4RunRepo()
    private val subjects = FakeS4SubjectRepo()
    private val scores = FakeS4ScoreRepo()
    private val facts = FakeS4FactRepo()
    private val claims = FakeS4ClaimRepo()
    private val reviews = FakeS4ReviewRepo()
    private val personas = FakeS4PersonaRepo()
    private val plans = FakeS4PlanRepo()
    private val sfts = FakeS4SftRepo()
    private val dpos = FakeS4DpoRepo()
    private val promptRepo = FakeS4PromptRepo()
    private val drafter = FakeS4Drafter()
    private val judge = FakeS4Judge()
    private val judgments = FakeS4JudgmentRepo()
    private val exportRepo = FakeS4ExportRepo()
    private val exporter = FakeS4Exporter()

    /** The hash SELECT freezes for a subject with nothing stored (defaults-only persona). */
    private val defaultPersonaHash =
        PersonaDefaults.resolve(
                null,
                fallbackAdvocateName = PersonaService.DEFAULT_ADVOCATE_NAME,
                fallbackPresetId = "warm-storyteller",
            )
            .hash()

    private fun service(props: AppProperties = AppProperties()): Stage4Service {
        val promptService = ExtractionPromptService(promptRepo)
        val toolCallMapper = ToolCallMapper()
        val sftSerializer = ContentsPartsSerializer(toolCallMapper)
        val dpoSerializer = DpoSerializer(toolCallMapper)
        val sftService = SftService(sfts, SftValidator(), sftSerializer)
        val exportService =
            ExportService(
                sft = sftService,
                dpo = DpoService(dpos, DpoValidator(), dpoSerializer, sftService),
                sftExamples = sfts,
                sftSerializer = sftSerializer,
                dpoSerializer = dpoSerializer,
                sftValidator = SftValidator(),
                lineValidator = DatasetLineValidator(props),
                exporter = exporter,
                exports = exportRepo,
            )
        return Stage4Service(
            runs = runs,
            subjects = subjects,
            subjectScores = scores,
            subjectFacts = facts,
            claimLedger = claims,
            claimReviews = reviews,
            reviewService =
                ClaimReviewService(FakeS4ManifestRepo(), FakeS4JobRepo(), claims, reviews, props),
            personaService =
                PersonaService(props, personas, subjects, FakeS4NameRepo(), promptService),
            prompts = promptService,
            drafter = drafter,
            judge = judge,
            judgments = judgments,
            exportService = exportService,
            sft = sftService,
            plans = plans,
            sftExamples = sfts,
            dpoPairs = dpos,
            props = props,
        )
    }

    // ---- seeding ------------------------------------------------------------------

    private fun claim(
        id: String,
        subjectId: String = "s1",
        scoreRunId: String? = "pub-1",
        contractVersion: Int? = 2,
        score: Double? = 0.9,
        sensitive: Boolean = false,
    ) =
        Claim(
            id = id,
            subjectId = subjectId,
            assetId = "a1",
            claimType = ClaimType.EPISODE,
            text = "claim $id text",
            authenticityScore = score,
            authenticityScoreBare = score,
            authenticitySignals = score?.let { mapOf("conflict" to 0.0, "independence" to 0.0) },
            scoreRunId = scoreRunId,
            publishContractVersion = contractVersion,
            sensitive = sensitive,
        )

    private fun seedPublished(
        subjectId: String = "s1",
        scoreRunId: String = "pub-1",
        contractVersion: Int = 2,
        claimIds: List<String> = listOf("c1", "c2", "c3"),
    ) {
        subjects.store[subjectId] = Subject(id = subjectId, displayName = "Asha")
        scores.save(
            SubjectScoreRecord(
                subjectId = subjectId,
                score = 0.8,
                display = 80,
                band = "GOOD",
                components = emptyMap(),
                inputs = emptyMap(),
                factCount = 1,
                claimCount = claimIds.size,
                scoreRunId = scoreRunId,
                publishedAt = Instant.now(),
                publishedBy = "op",
                publishContractVersion = contractVersion,
            )
        )
        claimIds.forEach { claims.store[it] = claim(it, subjectId, scoreRunId) }
        facts.store +=
            SubjectFactRecord(
                factId = "f1",
                subjectId = subjectId,
                scoreRunId = scoreRunId,
                publishedAt = Instant.now(),
                label = "fact one",
                memberClaimIds = claimIds,
                belief = 0.9,
                anchored = true,
            )
    }

    private fun <T> Either<DomainError, T>.expectRight(): T =
        fold({ throw AssertionError("expected success, got $it") }, { it })

    private fun <T> Either<DomainError, T>.err(): DomainError? = fold({ it }, { null })

    private fun pollUntil(
        svc: Stage4Service,
        runId: String,
        target: Stage4RunStatus,
        // The QD-5 probe banks put ~40 plans behind the default seed at batch 8 — a full walk
        // to REVIEW_WAIT is ~16 ticks; 40 keeps headroom without masking a stuck phase.
        maxPolls: Int = 40,
    ): Stage4Run {
        var run = svc.run(runId)!!
        var polls = 0
        while (run.status != target && polls < maxPolls) {
            run = svc.poll(runId).expectRight()
            polls++
        }
        assertEquals(
            target,
            run.status,
            "run did not reach $target (got ${run.status}, error=${run.error})",
        )
        return run
    }

    // ---- VA-53: chassis --------------------------------------------------------------

    @Test
    fun `submit then poll walks the chain to REVIEW_WAIT, freezing scoreRunId and personaHash`() {
        seedPublished()
        val svc = service()

        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        assertEquals(Stage4RunStatus.PENDING, run.status)
        assertNotNull(run.paramsSnapshot)

        val afterSelect = svc.poll(run.id).expectRight()
        assertEquals(Stage4RunStatus.PLANNING, afterSelect.status)
        assertEquals("pub-1", afterSelect.scoreRunId)
        assertEquals(defaultPersonaHash, afterSelect.personaHash)
        assertEquals(3L, afterSelect.counters[Stage4Counters.CLAIMS_ELIGIBLE])

        val afterPlan = svc.poll(run.id).expectRight()
        assertEquals(Stage4RunStatus.GENERATING, afterPlan.status)
        val plansCount = assertNotNull(afterPlan.counters[Stage4Counters.PLANS])
        assertTrue(plansCount > 0)
        assertEquals(plansCount, plans.store.size.toLong())
        // Every persisted plan carries the frozen stamps.
        assertTrue(
            plans.store.values.all {
                it.scoreRunId == "pub-1" && it.personaHash == defaultPersonaHash
            }
        )

        // GENERATE ticks in bounded batches until every eligible plan is satisfied (VA-56).
        val afterGenerate = pollUntil(svc, run.id, Stage4RunStatus.JUDGING)
        val generated = assertNotNull(afterGenerate.counters[Stage4Counters.GENERATED])
        assertTrue(generated > 0)
        // JUDGE ticks in bounded batches, then parks the run at the QA-4 gate (VA-57).
        val parked = pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)
        assertEquals(generated, parked.counters[Stage4Counters.JUDGED_PASS])
        // The QA-4 park: further polls are no-ops.
        assertEquals(Stage4RunStatus.REVIEW_WAIT, svc.poll(run.id).expectRight().status)
    }

    @Test
    fun `submit guards - unknown subject, active run, disabled stage`() {
        seedPublished()
        val svc = service()

        assertIs<DomainError.NotFound>(svc.submit("nope", Stage4SubmitRequest(), "op").err())

        svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        assertIs<DomainError.Conflict>(svc.submit("s1", Stage4SubmitRequest(), "op").err())

        val disabled = service(AppProperties(stage4 = AppProperties.Stage4(enabled = false)))
        assertIs<DomainError.Conflict>(disabled.submit("s1", Stage4SubmitRequest(), "op").err())
    }

    @Test
    fun `a newer submit supersedes the parked REVIEW_WAIT run`() {
        seedPublished()
        val svc = service()
        val first = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, first.id, Stage4RunStatus.REVIEW_WAIT)

        val second = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()

        assertEquals(Stage4RunStatus.SUPERSEDED, runs.store[first.id]!!.status)
        assertNotNull(runs.store[first.id]!!.finishedAt)
        assertEquals(Stage4RunStatus.PENDING, second.status)
    }

    @Test
    fun `retry resumes from the failed phase`() {
        seedPublished(contractVersion = 1) // SELECT will fail the guard
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        val failed = svc.poll(run.id).expectRight()
        assertEquals(Stage4RunStatus.FAILED, failed.status)
        assertEquals(Stage4RunStatus.SELECTING, failed.failedPhase)

        // Fix the ledger, retry, and the run resumes inside SELECT.
        scores.save(scores.store["s1"]!!.copy(publishContractVersion = 2))
        val resumed = svc.retry(run.id).expectRight()
        assertEquals(Stage4RunStatus.SELECTING, resumed.status)
        assertNull(resumed.error)
        assertEquals(Stage4RunStatus.PLANNING, svc.poll(run.id).expectRight().status)
    }

    @Test
    fun `a stuck phase reclaims to FAILED after phase-timeout`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        svc.poll(run.id).expectRight() // → PLANNING
        runs.save(runs.store[run.id]!!.copy(phaseSince = Instant.now().minus(Duration.ofHours(1))))

        val reclaimed = svc.poll(run.id).expectRight()

        assertEquals(Stage4RunStatus.FAILED, reclaimed.status)
        assertEquals(Stage4RunStatus.PLANNING, reclaimed.failedPhase)
        assertTrue(reclaimed.error!!.contains("reclaimed"))
    }

    @Test
    fun `paramsSnapshot freezes mix overrides and PLAN honors the frozen weights`() {
        seedPublished()
        val svc = service()

        val run =
            svc.submit(
                    "s1",
                    Stage4SubmitRequest(
                        fresh = true,
                        mix =
                            Stage4SubmitRequest.MixOverrides(
                                qa = 1.0,
                                situational = 0.0,
                                multiClaim = 0.0,
                                negative = 0.0,
                                meta = 0.0,
                            ),
                    ),
                    "op",
                )
                .expectRight()

        assertTrue(run.fresh)
        val snapshot = run.paramsSnapshot!!
        assertTrue(snapshot.contains("\"fresh\":true"))
        assertTrue(snapshot.contains("\"situational\":0.0"))

        pollUntil(svc, run.id, Stage4RunStatus.GENERATING)
        assertTrue(plans.store.isNotEmpty())
        // The zeroed situational/multi-claim dials plan nothing there; the QD-5 probe banks
        // (NEGATIVE/META) ride along regardless of the mix.
        val factDriven =
            plans.store.values.filter {
                it.plan.category in
                    setOf(
                        Stage4Category.QA,
                        Stage4Category.SITUATIONAL,
                        Stage4Category.MULTI_CLAIM,
                    )
            }
        assertTrue(factDriven.isNotEmpty())
        assertTrue(factDriven.all { it.plan.category == Stage4Category.QA })
        assertTrue(plans.store.values.any { it.plan.category == Stage4Category.NEGATIVE })
        assertTrue(plans.store.values.any { it.plan.category == Stage4Category.META })
    }

    @Test
    fun `negative mix overrides are rejected`() {
        seedPublished()
        val svc = service()

        val err =
            svc.submit(
                    "s1",
                    Stage4SubmitRequest(mix = Stage4SubmitRequest.MixOverrides(qa = -0.5)),
                    "op",
                )
                .err()

        assertIs<DomainError.Invalid>(err)
    }

    // ---- VA-54: SELECT guards + eligible set + drift sweep ------------------------------

    @Test
    fun `SELECT fails loudly on an unpublished subject`() {
        subjects.store["s1"] = Subject(id = "s1", displayName = "Asha")
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()

        val failed = svc.poll(run.id).expectRight()

        assertEquals(Stage4RunStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("no published subject_scores"))
    }

    @Test
    fun `SELECT fails loudly on a v1-contract publish`() {
        seedPublished(contractVersion = 1)
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()

        val failed = svc.poll(run.id).expectRight()

        assertEquals(Stage4RunStatus.FAILED, failed.status)
        assertEquals(Stage4RunStatus.SELECTING, failed.failedPhase)
        assertTrue(failed.error!!.contains("publish contract v1 < v2"))
    }

    @Test
    fun `SELECT fails loudly when a scored claim carries a stale scoreRunId`() {
        seedPublished()
        claims.store["c9"] = claim("c9", scoreRunId = "pub-0")
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()

        val failed = svc.poll(run.id).expectRight()

        assertEquals(Stage4RunStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("publish-consistency violated"))
        assertTrue(failed.error!!.contains("c9"))
    }

    @Test
    fun `SELECT fails loudly when a subject_facts doc carries a stale scoreRunId`() {
        seedPublished()
        facts.store +=
            SubjectFactRecord(
                factId = "f9",
                subjectId = "s1",
                scoreRunId = "pub-0",
                publishedAt = Instant.now(),
                label = "stale fact",
                memberClaimIds = listOf("c1"),
            )
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()

        val failed = svc.poll(run.id).expectRight()

        assertEquals(Stage4RunStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("subject_facts"))
        assertTrue(failed.error!!.contains("f9"))
    }

    @Test
    fun `eligible set excludes CONTESTED and hidden-PII claims`() {
        seedPublished(claimIds = listOf("c1", "c2", "c3", "c4"))
        claims.store["c2"] = claims.store["c2"]!!.copy(sensitive = true) // held: no opt-in
        claims.store["c4"] = claims.store["c4"]!!.copy(sensitive = true) // opted in below
        reviews.save(
            ClaimReview(claimId = "c3", subjectId = "s1", decision = ReviewDecision.CONTESTED)
        )
        reviews.save(ClaimReview(claimId = "c4", subjectId = "s1", piiChoice = PiiChoice.INCLUDE))
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()

        val afterSelect = svc.poll(run.id).expectRight()

        // c1 (plain) + c4 (sensitive, opted-in); c2 held, c3 contested.
        assertEquals(2L, afterSelect.counters[Stage4Counters.CLAIMS_ELIGIBLE])
    }

    @Test
    fun `drift sweep archives exactly the stale-stamped examples with the reason recorded`() {
        seedPublished()
        val stale =
            SftExample(
                id = "e-stale",
                stamp =
                    Stage4Stamp(
                        subjectId = "s1",
                        scoreRunId = "pub-0",
                        personaHash = defaultPersonaHash
                    ),
            )
        val current =
            SftExample(
                id = "e-current",
                stamp =
                    Stage4Stamp(
                        subjectId = "s1",
                        scoreRunId = "pub-1",
                        personaHash = defaultPersonaHash
                    ),
            )
        val legacy = SftExample(id = "e-legacy") // labelling-era, no stamp
        val alreadyArchived =
            SftExample(
                id = "e-archived",
                status = ExampleStatus.ARCHIVED,
                archivedReason = "manually archived",
                stamp =
                    Stage4Stamp(
                        subjectId = "s1",
                        scoreRunId = "pub-0",
                        personaHash = defaultPersonaHash
                    ),
            )
        listOf(stale, current, legacy, alreadyArchived).forEach { sfts.store[it.id] = it }
        dpos.store["d-stale"] =
            DpoPair(
                id = "d-stale",
                stamp =
                    Stage4Stamp(subjectId = "s1", scoreRunId = "pub-1", personaHash = "old-hash"),
            )
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()

        val afterSelect = svc.poll(run.id).expectRight()

        assertEquals(2L, afterSelect.counters[Stage4Counters.EXAMPLES_ARCHIVED])
        val sweptSft = sfts.store["e-stale"]!!
        assertEquals(ExampleStatus.ARCHIVED, sweptSft.status)
        assertTrue(sweptSft.archivedReason!!.contains("superseded by scoreRunId pub-1"))
        val sweptDpo = dpos.store["d-stale"]!!
        assertEquals(ExampleStatus.ARCHIVED, sweptDpo.status)
        assertTrue(sweptDpo.archivedReason!!.contains("personaHash"))
        // Current, legacy and already-archived rows are untouched.
        assertEquals(ExampleStatus.DRAFT, sfts.store["e-current"]!!.status)
        assertEquals(ExampleStatus.DRAFT, sfts.store["e-legacy"]!!.status)
        assertEquals("manually archived", sfts.store["e-archived"]!!.archivedReason)
    }

    // ---- VA-55: PLAN through the service --------------------------------------------------

    @Test
    fun `re-planning an unchanged subject overwrites the same content-addressed plan docs`() {
        seedPublished()
        val svc = service()
        val first = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, first.id, Stage4RunStatus.REVIEW_WAIT)
        val planIds = plans.store.keys.toSet()

        val second = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, second.id, Stage4RunStatus.REVIEW_WAIT)

        assertEquals(planIds, plans.store.keys.toSet())
    }

    @Test
    fun `PLAN fails loudly when the publish drifts mid-run`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        svc.poll(run.id).expectRight() // SELECT → PLANNING

        // A re-publish lands between the run's SELECT and PLAN ticks.
        scores.save(scores.store["s1"]!!.copy(scoreRunId = "pub-2"))
        val failed = svc.poll(run.id).expectRight()

        assertEquals(Stage4RunStatus.FAILED, failed.status)
        assertEquals(Stage4RunStatus.PLANNING, failed.failedPhase)
        assertTrue(failed.error!!.contains("publish drifted mid-run"))
    }

    @Test
    fun `PLAN fails loudly when the persona drifts mid-run`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        svc.poll(run.id).expectRight() // SELECT → PLANNING

        personas.store["s1"] = SubjectPersona(subjectId = "s1", advocateName = "Someone Else")
        val failed = svc.poll(run.id).expectRight()

        assertEquals(Stage4RunStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("persona drifted mid-run"))
    }

    // ---- VA-56: GENERATE + the plan-keyed cache · VA-62: drafter double never sees META ----

    /** Live (non-archived) generated examples, keyed by the plan they satisfy. */
    private fun liveByPlan(): Map<String, SftExample> =
        sfts.store.values
            .filter { it.status != ExampleStatus.ARCHIVED && it.stamp?.planId != null }
            .associateBy { it.stamp!!.planId!! }

    @Test
    fun `GENERATE drafts fully-stamped DRAFT examples in bounded batches, META never via LLM`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.GENERATING)
        val eligiblePlans = plans.store.values.filter { it.plan.sftEligible }

        // First tick: bounded work (default generate-batch-per-poll = 8).
        svc.poll(run.id).expectRight()
        assertTrue(sfts.store.size <= 8, "first tick wrote ${sfts.store.size} examples")

        val done = pollUntil(svc, run.id, Stage4RunStatus.JUDGING)

        val live = liveByPlan()
        assertEquals(eligiblePlans.size.toLong(), done.counters[Stage4Counters.GENERATED])
        assertEquals(0L, done.counters[Stage4Counters.CACHE_HITS])
        eligiblePlans.forEach { p ->
            val example = assertNotNull(live[p.plan.planId], "plan ${p.plan.planId} unsatisfied")
            assertEquals(ExampleStatus.DRAFT, example.status)
            val stamp = example.stamp!!
            assertEquals("s1", stamp.subjectId)
            assertEquals("pub-1", stamp.scoreRunId)
            assertEquals(defaultPersonaHash, stamp.personaHash)
            assertEquals(p.plan.category, stamp.category)
            assertEquals(p.plan.sourceClaimIds, stamp.sourceClaimIds)
            assertNotNull(stamp.generatorPromptHash)
            assertTrue(example.turns.size >= 2)
        }
        // META renders from templates: no drafter call, no llmModel; LLM categories carry it.
        assertTrue(drafter.requests.none { it.plan.category == Stage4Category.META })
        val metaExamples = live.values.filter { it.stamp?.category == Stage4Category.META }
        assertTrue(metaExamples.isNotEmpty())
        assertTrue(metaExamples.all { it.llmModel == null })
        assertTrue(
            live.values
                .filter { it.stamp?.category != Stage4Category.META }
                .all { it.llmModel == "fake-gemini" }
        )
    }

    @Test
    fun `re-running an unchanged subject is 100 percent cache hits with zero drafter calls`() {
        seedPublished()
        val svc = service()
        val first = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, first.id, Stage4RunStatus.REVIEW_WAIT)
        val examplesAfterFirst = sfts.store.size
        drafter.requests.clear()

        val second = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        val done = pollUntil(svc, second.id, Stage4RunStatus.REVIEW_WAIT)

        assertTrue(drafter.requests.isEmpty(), "re-run must make zero LLM calls")
        assertEquals(examplesAfterFirst, sfts.store.size)
        assertEquals(
            done.counters[Stage4Counters.GENERATED],
            done.counters[Stage4Counters.CACHE_HITS]
        )
        assertTrue(done.counters[Stage4Counters.CACHE_HITS]!! > 0)
    }

    @Test
    fun `a generator prompt bump regenerates exactly the affected category`() {
        seedPublished()
        val svc = service()
        val first = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, first.id, Stage4RunStatus.REVIEW_WAIT)
        val qaExampleIds =
            sfts.store.values
                .filter { it.stamp?.category == Stage4Category.QA }
                .map { it.id }
                .toSet()
        drafter.requests.clear()

        promptRepo.store["stage4:gen:qa"] =
            ExtractionPrompt(id = "stage4:gen:qa", instructions = "Rewritten QA task.", version = 1)
        val second = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, second.id, Stage4RunStatus.REVIEW_WAIT)

        assertTrue(drafter.requests.isNotEmpty())
        assertTrue(drafter.requests.all { it.plan.category == Stage4Category.QA })
        assertTrue(drafter.requests.all { it.promptInstructions == "Rewritten QA task." })
        qaExampleIds.forEach { id ->
            val old = sfts.store[id]!!
            assertEquals(ExampleStatus.ARCHIVED, old.status)
            assertTrue(old.archivedReason!!.contains("superseded by generator prompt"))
        }
    }

    @Test
    fun `fresh re-drafts every plan, archives pre-run examples and bypasses the cache`() {
        seedPublished()
        val svc = service()
        val first = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, first.id, Stage4RunStatus.REVIEW_WAIT)
        val firstRunCalls = drafter.requests.size
        val preFreshIds = liveByPlan().values.map { it.id }.toSet()
        drafter.requests.clear()

        val fresh = svc.submit("s1", Stage4SubmitRequest(fresh = true), "op").expectRight()
        val done = pollUntil(svc, fresh.id, Stage4RunStatus.REVIEW_WAIT)

        assertEquals(firstRunCalls, drafter.requests.size, "fresh must re-draft every LLM plan")
        assertEquals(0L, done.counters[Stage4Counters.CACHE_HITS])
        preFreshIds.forEach { id ->
            val old = sfts.store[id]!!
            assertEquals(ExampleStatus.ARCHIVED, old.status)
            assertTrue(old.archivedReason!!.contains("fresh regeneration"))
        }
    }

    @Test
    fun `unchanged claims under a new publish regenerate for free from archived examples`() {
        seedPublished()
        val svc = service()
        val first = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, first.id, Stage4RunStatus.REVIEW_WAIT)
        drafter.requests.clear()

        // Re-publish under pub-2: identical claims/facts, new scoreRunId. The SELECT drift sweep
        // archives every pub-1 example; their planIds (publish-independent) stay valid cache keys.
        scores.save(scores.store["s1"]!!.copy(scoreRunId = "pub-2"))
        claims.store.keys.toList().forEach {
            claims.store[it] = claims.store[it]!!.copy(scoreRunId = "pub-2")
        }
        val republished = facts.store.map { it.copy(scoreRunId = "pub-2") }
        facts.store.clear()
        facts.store += republished

        val second = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        val done = pollUntil(svc, second.id, Stage4RunStatus.REVIEW_WAIT)

        assertTrue(drafter.requests.isEmpty(), "unchanged claims must regenerate LLM-free")
        val live = liveByPlan().values
        assertTrue(live.isNotEmpty())
        assertTrue(live.all { it.stamp?.scoreRunId == "pub-2" })
        assertTrue(
            live.all {
                it.createdBy?.startsWith("stage4-cache:") == true ||
                    it.stamp?.category == Stage4Category.META
            }
        )
        assertEquals(
            done.counters[Stage4Counters.GENERATED],
            done.counters[Stage4Counters.CACHE_HITS]
        )
    }

    @Test
    fun `row-5 plans (sftEligible=false) never generate`() {
        seedPublished()
        // c2 carries a human-CONFIRMED unexplained contradiction — the row 5 exclusion.
        claims.store["c2"] =
            claims.store["c2"]!!.copy(edgeCounts = mapOf("contradictsConfirmed" to 1))
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)

        val ineligible = plans.store.values.filter { !it.plan.sftEligible }
        assertTrue(ineligible.isNotEmpty(), "expected at least one row-5 plan")
        val live = liveByPlan()
        ineligible.forEach { p ->
            assertNull(live[p.plan.planId], "row-5 plan ${p.plan.planId} must not generate")
        }
    }

    // ---- VA-57: JUDGE — bounded ensemble batches, verdict routing, the judge-once cursor ----

    @Test
    fun `JUDGE judges every generated example in bounded batches and lands the verdicts`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.JUDGING)

        // First tick: bounded work (default judge-batch-per-poll = 8).
        svc.poll(run.id).expectRight()
        assertTrue(judgments.store.size <= 8, "first judge tick wrote ${judgments.store.size}")

        val parked = pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)

        val live = liveByPlan().values
        assertTrue(live.isNotEmpty())
        // One judgment doc and one recorded ensemble per example; verdicts land on the example
        // and PASS routes it into the human queue (QA-4: SUBMITTED, 100% review).
        assertEquals(live.size, judgments.store.size)
        assertEquals(live.size, judge.requests.size)
        live.forEach {
            assertEquals(JudgeVerdict.PASS, it.judgeVerdict)
            assertEquals(ExampleStatus.SUBMITTED, it.status)
        }
        assertEquals(live.size.toLong(), parked.counters[Stage4Counters.JUDGED_PASS])
        assertEquals(0L, parked.counters[Stage4Counters.JUDGED_BORDERLINE])
        assertEquals(0L, parked.counters[Stage4Counters.JUDGED_FAIL])
        // The distillation payload: four axes with votes, rubric provenance, turns hash, model.
        val doc = judgments.store.values.first()
        assertEquals(4, doc.axes.size)
        assertTrue(doc.axes.values.all { it.votes.isNotEmpty() })
        assertEquals("fake-judge:1", doc.judgePromptHash)
        assertEquals(1, doc.judgePromptVersion)
        assertNotNull(doc.turnsHash)
        assertEquals("fake-judge", doc.model)
        assertEquals(run.id, doc.runId)
    }

    @Test
    fun `FAIL routes to NEEDS_CHANGES with rationale, BORDERLINE queues, counters split`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.GENERATING)
        val eligible = plans.store.values.filter { it.plan.sftEligible }.sortedBy { it.plan.planId }
        judge.script[eligible[0].plan.planId] = JudgeVerdict.FAIL
        judge.script[eligible[1].plan.planId] = JudgeVerdict.BORDERLINE

        val parked = pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)

        val failed = assertNotNull(liveByPlan()[eligible[0].plan.planId])
        assertEquals(ExampleStatus.NEEDS_CHANGES, failed.status)
        assertEquals(JudgeVerdict.FAIL, failed.judgeVerdict)
        val comment = failed.reviewComments.last()
        assertEquals("stage4-judge", comment.by)
        assertTrue(comment.text.contains("faithfulness"))
        assertTrue(comment.text.contains("scripted FAIL"))

        val borderline = assertNotNull(liveByPlan()[eligible[1].plan.planId])
        assertEquals(ExampleStatus.SUBMITTED, borderline.status)
        assertEquals(JudgeVerdict.BORDERLINE, borderline.judgeVerdict)

        assertEquals(1L, parked.counters[Stage4Counters.JUDGED_FAIL])
        assertEquals(1L, parked.counters[Stage4Counters.JUDGED_BORDERLINE])
        assertEquals(
            (liveByPlan().size - 2).toLong(),
            parked.counters[Stage4Counters.JUDGED_PASS],
        )
    }

    @Test
    fun `judge-once cursor - unchanged never re-judges, an edit re-judges, a rubric bump re-judges all`() {
        seedPublished()
        val svc = service()
        val first = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, first.id, Stage4RunStatus.REVIEW_WAIT)
        val calls = judge.requests.size
        val docs = judgments.store.size

        // Unchanged re-run: the verdict cache serves everything — zero samples, counters land.
        val second = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        val parked = pollUntil(svc, second.id, Stage4RunStatus.REVIEW_WAIT)
        assertEquals(calls, judge.requests.size)
        assertEquals(docs, judgments.store.size)
        assertEquals(docs.toLong(), parked.counters[Stage4Counters.JUDGED_PASS])

        // An edited conversation misses at its new turnsHash: exactly it re-judges, and the new
        // pass lands a NEW judgment doc (§11 feedback loop — the append-only distillation set).
        val edited = sfts.store.values.first { it.status == ExampleStatus.SUBMITTED }
        sfts.store[edited.id] =
            edited.copy(
                turns =
                    listOf(
                        edited.turns.first(),
                        Turn(role = TurnRole.MODEL, text = "hand-edited reply"),
                    )
            )
        val third = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, third.id, Stage4RunStatus.REVIEW_WAIT)
        assertEquals(calls + 1, judge.requests.size)
        assertEquals(docs + 1, judgments.store.size)
        assertEquals(2, judgments.store.values.count { it.exampleId == edited.id })

        // A judge rubric bump changes the stamp: everything re-judges cleanly.
        val live = liveByPlan().size
        judge.stamp = "fake-judge:2"
        val fourth = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, fourth.id, Stage4RunStatus.REVIEW_WAIT)
        assertEquals(calls + 1 + live, judge.requests.size)
        assertTrue(judgments.store.values.count { it.judgePromptHash == "fake-judge:2" } == live)
    }

    @Test
    fun `judge disabled at submit (QD-6) - examples submit unjudged and the run parks`() {
        seedPublished()
        val svc = service(AppProperties(stage4 = AppProperties.Stage4(judgeEnabled = false)))

        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        val parked = pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)

        assertTrue(parked.paramsSnapshot!!.contains("\"judgeEnabled\":false"))
        val stamped = sfts.store.values.filter { it.stamp != null }
        assertTrue(stamped.isNotEmpty())
        assertTrue(stamped.all { it.status == ExampleStatus.SUBMITTED })
        assertTrue(stamped.all { it.judgeVerdict == null }, "no verdicts land with the judge off")
        assertTrue(judge.requests.isEmpty(), "no judge samples are drawn")
        assertTrue(judgments.store.isEmpty(), "the distillation set does not accrue")
        assertTrue(Stage4Counters.JUDGED_PASS !in parked.counters)
    }

    @Test
    fun `situational judging re-derives the hedging ground truth, other categories carry none`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)

        val situational = judge.requests.filter { it.plan.category == Stage4Category.SITUATIONAL }
        assertTrue(situational.isNotEmpty())
        // The seeded chain (one anchored fact, belief 0.9) re-derives floors-met "very likely" —
        // the same verdict PLAN froze into the row-9 constraints (the VA-57 symmetry).
        situational.forEach {
            val hedge = assertNotNull(it.expectedHedge, "situational request without ground truth")
            assertTrue(hedge.floorsMet)
            assertEquals(HedgePhrase.VERY_LIKELY, hedge.phrase)
        }
        judge.requests
            .filter { it.plan.category != Stage4Category.SITUATIONAL }
            .forEach { assertNull(it.expectedHedge) }
    }

    // ---- VA-58: REVIEW_WAIT park → operator export completes the run ----------------------

    /** Approve every queued (SUBMITTED) conversation, returning the approved ids. */
    private fun approveQueue(): List<String> =
        sfts.store.values
            .filter { it.status == ExampleStatus.SUBMITTED }
            .sortedBy { it.id }
            .map {
                sfts.store[it.id] = it.copy(status = ExampleStatus.APPROVED)
                it.id
            }

    @Test
    fun `export completes the parked run with exactly the APPROVED current-stamp examples`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)

        // One conversation stays NEEDS_CHANGES, one APPROVED row carries a stale stamp, one is
        // ARCHIVED — none of the three may reach the dataset (the VA-58 filter contract).
        val queued =
            sfts.store.values.filter { it.status == ExampleStatus.SUBMITTED }.sortedBy { it.id }
        val leftBehind = queued.first()
        sfts.store[leftBehind.id] = leftBehind.copy(status = ExampleStatus.NEEDS_CHANGES)
        val approvedIds = approveQueue().toSet()
        val turns =
            listOf(Turn(role = TurnRole.USER, text = "q"), Turn(role = TurnRole.MODEL, text = "a"))
        sfts.store["e-stale-approved"] =
            SftExample(
                id = "e-stale-approved",
                status = ExampleStatus.APPROVED,
                turns = turns,
                stamp =
                    Stage4Stamp(
                        subjectId = "s1",
                        scoreRunId = "pub-0",
                        personaHash = defaultPersonaHash,
                        planId = "p-old",
                    ),
            )
        sfts.store["e-archived-cur"] =
            SftExample(
                id = "e-archived-cur",
                status = ExampleStatus.ARCHIVED,
                turns = turns,
                stamp =
                    Stage4Stamp(
                        subjectId = "s1",
                        scoreRunId = "pub-1",
                        personaHash = defaultPersonaHash,
                        planId = "p-arch",
                    ),
            )

        val outcome = svc.export(run.id, "op").expectRight()

        // The run completes: REVIEW_WAIT → DONE with the exportRecordId journaled (§9.4).
        assertEquals(Stage4RunStatus.DONE, outcome.run.status)
        assertEquals(outcome.record.id, outcome.run.exportRecordId)
        assertNotNull(outcome.run.finishedAt)
        assertEquals(Stage4RunStatus.DONE, runs.store[run.id]!!.status)
        assertEquals(Stage4RunStatus.DONE, svc.poll(run.id).expectRight().status)
        // Exactly the APPROVED current-stamp set, each journaled with the record id.
        assertEquals(approvedIds, outcome.record.exampleIds.toSet())
        assertEquals(approvedIds.size, outcome.record.count)
        approvedIds.forEach { assertTrue(sfts.store[it]!!.exportedIn.contains(outcome.record.id)) }
        assertTrue(sfts.store[leftBehind.id]!!.exportedIn.isEmpty())
        assertTrue(sfts.store["e-stale-approved"]!!.exportedIn.isEmpty())
        // The blob round-trips the §19 contents/parts line shape — one line per example, guest
        // first, advocate last, text parts only.
        val content = exporter.written.values.single()
        val lines = content.split("\n")
        assertEquals(approvedIds.size, lines.size)
        lines.forEach { line ->
            val obj = Json.parse(line) as Map<*, *>
            val contents = assertNotNull(obj["contents"] as? List<*>)
            val firstTurn = contents.first() as Map<*, *>
            val lastTurn = contents.last() as Map<*, *>
            assertEquals("user", firstTurn["role"])
            assertEquals("model", lastTurn["role"])
            val parts = assertNotNull(lastTurn["parts"] as? List<*>)
            assertNotNull((parts.first() as Map<*, *>)["text"])
        }
    }

    @Test
    fun `a validator failure aborts the export with exampleId pointers and no partial record`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)
        val approved = approveQueue()
        val broken = approved.first()
        sfts.store[broken] =
            sfts.store[broken]!!.copy(
                turns =
                    listOf(
                        Turn(role = TurnRole.USER, text = "q"),
                        Turn(role = TurnRole.MODEL, text = ""),
                    )
            )

        val err = assertIs<DomainError.Invalid>(svc.export(run.id, "op").err())

        assertTrue(err.message.contains(broken))
        assertTrue(exportRepo.store.isEmpty())
        assertTrue(exporter.written.isEmpty())
        assertEquals(Stage4RunStatus.REVIEW_WAIT, runs.store[run.id]!!.status)
        assertTrue(sfts.store.values.all { it.exportedIn.isEmpty() })
    }

    @Test
    fun `export requires the QA-4 park and at least one approved example`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.GENERATING)

        assertIs<DomainError.Conflict>(svc.export(run.id, "op").err())

        pollUntil(svc, run.id, Stage4RunStatus.REVIEW_WAIT)
        // Everything is still SUBMITTED (queued, unreviewed) — nothing to export yet.
        assertIs<DomainError.Invalid>(svc.export(run.id, "op").err())
        assertEquals(Stage4RunStatus.REVIEW_WAIT, runs.store[run.id]!!.status)

        assertIs<DomainError.NotFound>(svc.export("nope", "op").err())
    }

    // ---- bulk approve: the judge-trusting shortcut through the QA-4 queue -------------------

    /** A current-stamp SUBMITTED conversation outside the run's own queue, for verdict crafting. */
    private fun syntheticExample(id: String, run: Stage4Run): SftExample =
        SftExample(
            id = id,
            status = ExampleStatus.SUBMITTED,
            turns =
                listOf(
                    Turn(role = TurnRole.USER, text = "q"),
                    Turn(role = TurnRole.MODEL, text = "a")
                ),
            stamp =
                Stage4Stamp(
                    subjectId = run.subjectId,
                    scoreRunId = run.scoreRunId,
                    personaHash = run.personaHash,
                    planId = "p-$id",
                ),
        )

    private fun judgment(
        id: String,
        example: SftExample,
        overall: JudgeVerdict,
        turnsHash: String = Stage4Judging.turnsHash(example.turns),
        at: Instant = Instant.parse("2026-07-13T10:00:00Z"),
    ): Stage4Judgment =
        Stage4Judgment(
            id = id,
            exampleId = example.id,
            subjectId = example.stamp?.subjectId,
            overall = overall,
            turnsHash = turnsHash,
            createdAt = at,
        )

    @Test
    fun `bulk approve flips exactly the un-stale PASS rows and reports the rest by reason`() {
        seedPublished()
        val svc = service()
        val run0 = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run0.id, Stage4RunStatus.REVIEW_WAIT)
        val run = runs.store[run0.id]!!

        // Wipe the run-produced verdicts: the natural queue becomes the unjudged baseline, and
        // the crafted rows below are the only judged ones — deterministic regardless of the fake
        // judge's distribution.
        judgments.store.clear()
        val naturalQueue = sfts.store.values.count { it.status == ExampleStatus.SUBMITTED }

        val pass = syntheticExample("e-pass", run)
        val passEdited = syntheticExample("e-pass-edited", run)
        val borderline = syntheticExample("e-borderline", run)
        val flipped = syntheticExample("e-flipped", run)
        val sentBack =
            syntheticExample("e-sentback", run).copy(status = ExampleStatus.NEEDS_CHANGES)
        listOf(pass, passEdited, borderline, flipped, sentBack).forEach { sfts.store[it.id] = it }
        judgments.store["j1"] = judgment("j1", pass, JudgeVerdict.PASS)
        // PASS on record, but the turns moved since — no verdict to trust.
        judgments.store["j2"] =
            judgment("j2", passEdited, JudgeVerdict.PASS, turnsHash = "old-hash")
        judgments.store["j3"] = judgment("j3", borderline, JudgeVerdict.BORDERLINE)
        // Two passes on the same turns: the newer FAIL outranks the older PASS.
        judgments.store["j4"] =
            judgment("j4", flipped, JudgeVerdict.PASS, at = Instant.parse("2026-07-13T09:00:00Z"))
        judgments.store["j5"] = judgment("j5", flipped, JudgeVerdict.FAIL)
        // A human sent it back — a PASS verdict never bulk-approves over that.
        judgments.store["j6"] = judgment("j6", sentBack, JudgeVerdict.PASS)

        val outcome = svc.bulkApprove(run.id, "op").expectRight()

        assertEquals(1, outcome.approved)
        assertEquals(2, outcome.notPass)
        assertEquals(naturalQueue + 1, outcome.unjudged)
        assertEquals(1, outcome.sentBack)
        assertEquals(ExampleStatus.APPROVED, sfts.store["e-pass"]!!.status)
        assertEquals(ExampleStatus.SUBMITTED, sfts.store["e-pass-edited"]!!.status)
        assertEquals(ExampleStatus.SUBMITTED, sfts.store["e-borderline"]!!.status)
        assertEquals(ExampleStatus.SUBMITTED, sfts.store["e-flipped"]!!.status)
        assertEquals(ExampleStatus.NEEDS_CHANGES, sfts.store["e-sentback"]!!.status)
        // The run stays parked: bulk approve feeds the export gate, never replaces it.
        assertEquals(Stage4RunStatus.REVIEW_WAIT, runs.store[run.id]!!.status)
    }

    @Test
    fun `bulk approve requires the QA-4 park`() {
        seedPublished()
        val svc = service()
        val run = svc.submit("s1", Stage4SubmitRequest(), "op").expectRight()
        pollUntil(svc, run.id, Stage4RunStatus.GENERATING)

        assertIs<DomainError.Conflict>(svc.bulkApprove(run.id, "op").err())
        assertIs<DomainError.NotFound>(svc.bulkApprove("nope", "op").err())
    }
}

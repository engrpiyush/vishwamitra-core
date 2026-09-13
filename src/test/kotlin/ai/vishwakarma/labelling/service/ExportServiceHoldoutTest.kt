package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.DpoPair
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExportRecord
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4Run
import ai.vishwakarma.labelling.domain.Stage4RunStatus
import ai.vishwakarma.labelling.domain.Stage4Stamp
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.gcs.Exporter
import ai.vishwakarma.labelling.persistence.DpoPairRepository
import ai.vishwakarma.labelling.persistence.ExportRepository
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.serialization.ContentsPartsSerializer
import ai.vishwakarma.labelling.serialization.DatasetLineValidator
import ai.vishwakarma.labelling.serialization.DpoSerializer
import ai.vishwakarma.labelling.serialization.DpoValidator
import ai.vishwakarma.labelling.serialization.OpenAiChatSerializer
import ai.vishwakarma.labelling.serialization.SftValidator
import ai.vishwakarma.labelling.serialization.ToolCallMapper
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class HoldoutSftRepo : SftExampleRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, SftExample>()

    override fun findById(id: String): SftExample? = store[id]

    override fun findByStampSubject(subjectId: String): List<SftExample> =
        store.values.filter { it.stamp?.subjectId == subjectId }.sortedBy { it.id }

    override fun save(example: SftExample) {
        store[example.id] = example
    }
}

private class HoldoutExportRepo : ExportRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ExportRecord>()
    private var seq = 0

    override fun newId(): String = "export-${++seq}"

    override fun save(record: ExportRecord) {
        store[record.id] = record
    }
}

private class HoldoutExporter : Exporter(AppProperties()) {
    val written = linkedMapOf<String, String>()

    override fun write(objectPath: String, content: String): String {
        written[objectPath] = content
        return "gs://test-bucket/$objectPath"
    }
}

private class HoldoutDpoRepo : DpoPairRepository(mock(Firestore::class.java)) {
    override fun findAll(): List<DpoPair> = emptyList()

    override fun findByStatus(status: ExampleStatus): List<DpoPair> = emptyList()
}

/**
 * The §14 holdout carve inside [ExportService.exportStage4Run] (VA-60): per-category floor
 * selection, sticky membership, top-ups only from never-exported rows — and the acceptance
 * criterion that a held-out example is provably absent from every [ExportRecord].
 */
class ExportServiceHoldoutTest {

    private val sfts = HoldoutSftRepo()
    private val exportRepo = HoldoutExportRepo()
    private val exporter = HoldoutExporter()

    private val run =
        Stage4Run(
            id = "r1",
            subjectId = "s1",
            scoreRunId = "pub-1",
            personaHash = "ph",
            status = Stage4RunStatus.REVIEW_WAIT,
        )

    private fun service(): ExportService {
        val mapper = ToolCallMapper()
        val sftSerializer = ContentsPartsSerializer(mapper)
        val sftService = SftService(sfts, SftValidator(), sftSerializer)
        return ExportService(
            sft = sftService,
            dpo = DpoService(HoldoutDpoRepo(), DpoValidator(), DpoSerializer(mapper), sftService),
            sftExamples = sfts,
            sftSerializer = sftSerializer,
            openAiSerializer = OpenAiChatSerializer(mapper),
            dpoSerializer = DpoSerializer(mapper),
            sftValidator = SftValidator(),
            lineValidator = DatasetLineValidator(AppProperties()),
            exporter = exporter,
            exports = exportRepo,
        )
    }

    private fun seedApproved(id: String, category: Stage4Category) {
        sfts.store[id] =
            SftExample(
                id = id,
                status = ExampleStatus.APPROVED,
                turns =
                    listOf(
                        Turn(role = TurnRole.USER, text = "question $id"),
                        Turn(role = TurnRole.MODEL, text = "answer $id"),
                    ),
                stamp =
                    Stage4Stamp(
                        subjectId = "s1",
                        scoreRunId = "pub-1",
                        personaHash = "ph",
                        category = category,
                        planId = "plan-$id",
                    ),
            )
    }

    private fun <T> Either<DomainError, T>.expectRight(): T =
        fold({ throw AssertionError("expected success, got $it") }, { it })

    private fun holdoutIds(): Set<String> =
        sfts.store.values.filter { it.holdout }.map { it.id }.toSet()

    @Test
    fun `carve holds a per-category floor slice out of the export`() {
        repeat(10) { seedApproved("qa-%02d".format(it), Stage4Category.QA) }
        repeat(3) { seedApproved("sit-$it", Stage4Category.SITUATIONAL) }

        val result = service().exportStage4Run(run, "op", holdoutFraction = 0.10).expectRight()

        // floor(0.10 × 10) = 1 QA held; floor(0.10 × 3) = 0 SITUATIONAL — small categories
        // deliberately contribute nothing.
        val held = holdoutIds()
        assertEquals(1, result.heldOut)
        assertEquals(1, held.size)
        assertTrue(held.single().startsWith("qa-"))
        assertEquals(12, result.record.count)
        assertTrue(held.none { it in result.record.exampleIds })
        // The held row stays APPROVED, never journaled as exported.
        val heldRow = sfts.store[held.single()]!!
        assertEquals(ExampleStatus.APPROVED, heldRow.status)
        assertTrue(heldRow.exportedIn.isEmpty())
        // One JSONL line per exported example — the blob matches the record.
        assertEquals(result.record.count, exporter.written.values.single().split("\n").size)
    }

    @Test
    fun `holdout is sticky - absent from every later record, top-ups only from never-exported`() {
        repeat(10) { seedApproved("qa-%02d".format(it), Stage4Category.QA) }
        val svc = service()
        val first = svc.exportStage4Run(run, "op", holdoutFraction = 0.10).expectRight()
        val heldFirst = holdoutIds()
        assertEquals(1, heldFirst.size)

        // Second export: sticky slice held again, nothing new (targets unchanged).
        val second = svc.exportStage4Run(run, "op", holdoutFraction = 0.10).expectRight()
        assertEquals(heldFirst, holdoutIds())
        assertTrue(heldFirst.none { it in second.record.exampleIds })
        assertTrue(heldFirst.none { it in first.record.exampleIds })

        // The dial grows, but every non-holdout row is already exported — top-ups come only from
        // never-exported rows, so nothing already trained on can become holdout.
        val third = svc.exportStage4Run(run, "op", holdoutFraction = 0.40).expectRight()
        assertEquals(heldFirst, holdoutIds())
        assertEquals(9, third.record.count)

        // Two fresh approvals arrive: they are the only holdout candidates for the shortfall.
        seedApproved("qa-new-1", Stage4Category.QA)
        seedApproved("qa-new-2", Stage4Category.QA)
        svc.exportStage4Run(run, "op", holdoutFraction = 0.40).expectRight()
        val grown = holdoutIds()
        assertTrue(grown.containsAll(heldFirst))
        assertTrue((grown - heldFirst).all { it.startsWith("qa-new-") })
    }

    @Test
    fun `fraction zero carves nothing`() {
        repeat(10) { seedApproved("qa-%02d".format(it), Stage4Category.QA) }

        val result = service().exportStage4Run(run, "op", holdoutFraction = 0.0).expectRight()

        assertEquals(0, result.heldOut)
        assertTrue(holdoutIds().isEmpty())
        assertEquals(10, result.record.count)
    }

    @Test
    fun `an all-holdout pool refuses to export instead of writing an empty dataset`() {
        seedApproved("qa-only", Stage4Category.QA)
        sfts.store["qa-only"] = sfts.store["qa-only"]!!.copy(holdout = true)

        val err =
            service().exportStage4Run(run, "op", holdoutFraction = 0.10).fold({ it }, { null })

        assertIs<DomainError.Invalid>(err)
        assertTrue(exportRepo.store.isEmpty())
        assertTrue(exporter.written.isEmpty())
    }
}

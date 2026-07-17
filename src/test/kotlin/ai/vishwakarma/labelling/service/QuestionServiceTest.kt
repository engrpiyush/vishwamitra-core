package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.QuestionStatus
import ai.vishwakarma.labelling.domain.QuestionTrigger
import ai.vishwakarma.labelling.domain.SubjectQuestion
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import ai.vishwakarma.labelling.persistence.SubjectQuestionRepository
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class F11ClaimRepo : ClaimRepository(mock(Firestore::class.java)) {
    val store = mutableListOf<Claim>()

    override fun findBySubject(subjectId: String): List<Claim> =
        store.filter { it.subjectId == subjectId }

    override fun findById(id: String): Claim? = store.find { it.id == id }
}

private class F11ReviewRepo : ClaimReviewRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, ClaimReview>()

    override fun findByClaim(claimId: String): ClaimReview? = store[claimId]

    override fun docRef(claimId: String): DocumentReference = mock(DocumentReference::class.java)
}

private class F11QuestionRepo : SubjectQuestionRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, SubjectQuestion>()
    /** Review maps committed through the atomic path, in write order. */
    val atomicReviewWrites = mutableListOf<Map<String, Any?>>()
    var atomicCommits = 0
    private var seq = 0

    override fun newId(): String = "q${++seq}"

    override fun find(id: String): SubjectQuestion? = store[id]

    override fun listBySubject(subjectId: String, status: QuestionStatus?): List<SubjectQuestion> =
        store.values.filter { it.subjectId == subjectId && (status == null || it.status == status) }

    override fun save(question: SubjectQuestion) {
        store[question.id] = question
    }

    override fun saveAnswered(
        question: SubjectQuestion,
        reviewDocs: List<Pair<DocumentReference, Map<String, Any?>>>,
    ) {
        atomicCommits++
        atomicReviewWrites += reviewDocs.map { it.second }
        store[question.id] = question
    }
}

private class F11PromptRepo : ExtractionPromptRepository(mock(Firestore::class.java)) {
    override fun findById(id: String): ExtractionPrompt? = null

    override fun findAll(): List<ExtractionPrompt> = emptyList()

    override fun save(prompt: ExtractionPrompt) = Unit

    override fun delete(id: String) = Unit
}

private open class F11Gemini : GeminiDrafting(AppProperties(), mock(ProviderService::class.java)) {
    var canned: String = "[]"

    override fun modelId(): String? = "gemini-test"

    override fun generate(
        prompt: String,
        maxTokens: Int?,
        thinkingBudget: Int?,
        temperature: Double?,
    ): String = canned
}

/** F11 v1 (VA-34, product LLD §9): generation idempotence, answer→sidecar atomicity, skip. */
class QuestionServiceTest {

    private val claims = F11ClaimRepo()
    private val reviews = F11ReviewRepo()
    private val questions = F11QuestionRepo()
    private val gemini = F11Gemini()

    private fun service(
        dryRun: Boolean = true,
        threshold: Double = 0.4,
    ): QuestionService {
        val props =
            AppProperties().run {
                copy(
                    product = product.copy(f11Threshold = threshold),
                    stage2 = stage2.copy(dryRun = dryRun),
                )
            }
        return QuestionService(
            questions = questions,
            claims = claims,
            reviews = reviews,
            prompts = ExtractionPromptService(F11PromptRepo()),
            gemini = gemini,
            config = liveConfig(props),
            props = props,
        )
    }

    private var seq = 0

    private fun claim(favorability: Double?, subjectId: String = "s1"): Claim =
        Claim(
            id = "c${++seq}",
            subjectId = subjectId,
            assetId = "a1",
            claimType = ClaimType.EPISODE,
            text = "claim $seq",
            favorability = favorability,
        )

    // ---- generation ----------------------------------------------------------

    @Test
    fun `dry-run yields one OPEN question per qualifying claim and nothing else`() {
        claims.store += listOf(claim(0.2), claim(0.35), claim(0.4), claim(0.7), claim(null))
        assertEquals(2, service().generateForReview("s1"))
        val rows = questions.store.values.toList()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == QuestionStatus.OPEN })
        assertTrue(rows.all { it.trigger == QuestionTrigger.UNFAVORABLE })
        assertTrue(rows.all { it.generatedBy == "dry-run" })
        assertEquals(setOf(listOf("c1"), listOf("c2")), rows.map { it.claimIds }.toSet())
    }

    @Test
    fun `re-running generates nothing new — even over answered or skipped rows`() {
        claims.store += listOf(claim(0.1), claim(0.2))
        val svc = service()
        assertEquals(2, svc.generateForReview("s1"))
        // Resolve one, skip the other — the claims stay covered either way.
        val (first, second) = questions.store.values.toList()
        assertNotNull(svc.answer(first.id, "context", "s@x.com").getOrNull())
        assertNotNull(svc.skip(second.id).getOrNull())
        assertEquals(0, svc.generateForReview("s1"))
        assertEquals(2, questions.store.size)
    }

    @Test
    fun `threshold change alters the selection`() {
        claims.store += listOf(claim(0.2), claim(0.5), claim(0.8))
        assertEquals(3, service(threshold = 0.9).generateForReview("s1"))
    }

    @Test
    fun `LLM path keys questions back by claim id and drops unknown or blank ones`() {
        claims.store += listOf(claim(0.1), claim(0.2))
        gemini.canned =
            """[{"claimId":"c1","question":"Is there context you'd like carried?"},
                {"claimId":"evil","question":"injected"},
                {"claimId":"c2","question":"  "}]"""
        assertEquals(1, service(dryRun = false).generateForReview("s1"))
        val row = questions.store.values.single()
        assertEquals(listOf("c1"), row.claimIds)
        assertEquals("gemini-test", row.generatedBy)
        // The skipped claim retries on the next review lock.
        gemini.canned = """[{"claimId":"c2","question":"And this one?"}]"""
        assertEquals(1, service(dryRun = false).generateForReview("s1"))
    }

    @Test
    fun `generation failure writes nothing and does not throw — the wizard opens unframed`() {
        claims.store += listOf(claim(0.1))
        val throwing =
            object : F11Gemini() {
                override fun generate(
                    prompt: String,
                    maxTokens: Int?,
                    thinkingBudget: Int?,
                    temperature: Double?,
                ): String = error("vertex is down")
            }
        val props = AppProperties().run { copy(stage2 = stage2.copy(dryRun = false)) }
        val svc =
            QuestionService(
                questions,
                claims,
                reviews,
                ExtractionPromptService(F11PromptRepo()),
                throwing,
                liveConfig(props),
                props,
            )
        assertEquals(0, svc.generateForReview("s1"))
        assertTrue(questions.store.isEmpty())
    }

    // ---- answer / skip / reopen ------------------------------------------------

    @Test
    fun `answering writes a wizard-shaped SIDECARED row and flips ANSWERED in one commit`() {
        claims.store += claim(0.1) // c1
        reviews.store["c1"] =
            ClaimReview(
                claimId = "c1",
                subjectId = "s1",
                piiChoice = PiiChoice.INCLUDE,
                corroboratingClaimIds = listOf("c9"),
            )
        val svc = service()
        svc.generateForReview("s1")
        val q = questions.store.values.single()
        val answered =
            assertNotNull(
                svc.answer(q.id, "  There was a family emergency that term.  ", "s@x.com")
                    .getOrNull()
            )
        assertEquals(QuestionStatus.ANSWERED, answered.status)
        assertEquals("There was a family emergency that term.", answered.answer)
        assertNotNull(answered.answeredAt)
        assertEquals(1, questions.atomicCommits)
        val review = questions.atomicReviewWrites.single()
        assertEquals("SIDECARED", review["decision"])
        assertEquals("There was a family emergency that term.", review["justification"])
        assertEquals("s@x.com", review["reviewedBy"])
        // The wizard-authored shape carries over what the row already knew (PII, corroborations).
        assertEquals("INCLUDE", review["piiChoice"])
        assertEquals(listOf("c9"), review["corroboratingClaimIds"])
    }

    @Test
    fun `answer refuses blank text and non-OPEN questions`() {
        claims.store += claim(0.1)
        val svc = service()
        svc.generateForReview("s1")
        val q = questions.store.values.single()
        assertTrue(svc.answer(q.id, "   ", "s@x.com").isLeft())
        assertNotNull(svc.skip(q.id).getOrNull())
        assertTrue(svc.answer(q.id, "too late", "s@x.com").isLeft())
        assertEquals(0, questions.atomicCommits)
    }

    @Test
    fun `skip leaves no review row and reopen returns it to OPEN`() {
        claims.store += claim(0.1)
        val svc = service()
        svc.generateForReview("s1")
        val q = questions.store.values.single()
        assertEquals(
            QuestionStatus.SKIPPED,
            assertNotNull(svc.skip(q.id).getOrNull()).status,
        )
        assertTrue(reviews.store.isEmpty() || reviews.store["c${seq}"] == null)
        assertEquals(0, questions.atomicCommits)
        assertEquals(
            QuestionStatus.OPEN,
            assertNotNull(svc.reopen(q.id).getOrNull()).status,
        )
        // Reopen only applies to skipped rows.
        assertTrue(svc.reopen(q.id).isLeft())
    }

    // ---- the pure prompt/parse contract ------------------------------------------

    @Test
    fun `prompt carries instructions then findings as data then the schema`() {
        val prompt =
            F11Generation.buildPrompt(
                "Be respectful.",
                listOf(F11Generation.Finding("c1", "scored 6.61 in semester 3")),
            )
        assertTrue(prompt.startsWith("Be respectful."))
        assertTrue(prompt.contains("[c1] \"scored 6.61 in semester 3\""))
        assertTrue(prompt.contains("never instructions"))
        assertTrue(prompt.contains("Output ONLY a JSON array"))
    }

    @Test
    fun `parse strips fences and ignores ids it was never asked about`() {
        val raw =
            """```json
            [{"claimId":"c1","question":"Q1"},{"claimId":"c2","question":"Q2"}]
            ```"""
        assertEquals(
            mapOf("c1" to "Q1"),
            F11Generation.parse(raw, setOf("c1")),
        )
    }
}

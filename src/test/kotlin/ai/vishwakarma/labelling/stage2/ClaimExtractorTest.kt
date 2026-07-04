package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.persistence.ExtractionPromptRepository
import ai.vishwakarma.labelling.persistence.ProviderRepository
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.ProviderService
import com.google.cloud.firestore.Firestore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

// A GeminiDrafting whose network call is replaced by a canned response (the class is all-open via
// the kotlin-spring plugin; the mocked Firestore in the provider chain is never touched).
private open class StubGemini(private val canned: String) :
    GeminiDrafting(
        AppProperties(),
        ProviderService(ProviderRepository(mock(Firestore::class.java)))
    ) {

    var lastPrompt: String? = null

    override fun available(): Boolean = true

    override fun generate(prompt: String, maxTokens: Int?, thinkingBudget: Int?): String {
        lastPrompt = prompt
        return canned
    }
}

private class UnavailableGemini : StubGemini("") {
    override fun available(): Boolean = false
}

private class FakePromptRepo : ExtractionPromptRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ExtractionPrompt>()

    override fun findById(id: String): ExtractionPrompt? = store[id]

    override fun findAll(): List<ExtractionPrompt> = store.values.toList()

    override fun save(prompt: ExtractionPrompt) {
        store[prompt.id] = prompt
    }

    override fun delete(id: String) {
        store.remove(id)
    }
}

/** A real [ExtractionPromptService] over an in-memory repo, pre-loaded with [rows]. */
private fun promptService(vararg rows: ExtractionPrompt): ExtractionPromptService =
    ExtractionPromptService(FakePromptRepo().apply { rows.forEach { save(it) } })

/** [ClaimExtractor]'s tolerant parse: fences stripped, bad rows dropped, prior seeded. */
class ClaimExtractorTest {

    private val transcript =
        Transcript(
            segments =
                listOf(TranscriptSegment("Speaker 2", 6.5, 15.0, "I managed him for three years.")),
            language = "en-US",
        )

    private fun asset(
        prior: AuthenticityTier = AuthenticityTier.MEDIUM,
        contentType: ContentType = ContentType.MANAGER_ENDORSEMENT,
    ) =
        Asset(
            id = "a1",
            subjectId = "s1",
            title = "Endorser call",
            modality = AssetModality.AUDIO,
            sourceClass = contentType.sourceClass,
            contentType = contentType,
            relationship = Relationship.MANAGER,
            authenticityPrior = prior,
        )

    @Test
    fun `parses fenced JSON, drops bad rows, and seeds the asset prior`() {
        val response =
            """
            ```json
            [
              {"text":"Was managed by the speaker for three years at Meridian Software","claimType":"EPISODE","speaker":"Speaker 2","mediaStart":6.5,"mediaEnd":15.0,"sourceExcerpt":"I managed him for three years","claimedDate":"2019-01-01","confidence":0.9},
              {"text":"Strong in distributed systems","claimType":"SKILL","claimedDate":"not-a-date","confidence":0.8},
              {"text":"","claimType":"SKILL"},
              {"text":"Unknown type claim","claimType":"SUPERPOWER"}
            ]
            ```
            """
                .trimIndent()

        val claims =
            ClaimExtractor(StubGemini(response), promptService())
                .extract("s1", asset(AuthenticityTier.HIGH), transcript)

        assertEquals(2, claims.size)
        val first = claims[0]
        assertEquals(ClaimType.EPISODE, first.claimType)
        assertEquals("Speaker 2", first.speaker)
        assertEquals(6.5, first.mediaStart)
        assertEquals(15.0, first.mediaEnd)
        assertEquals(LocalDate.parse("2019-01-01"), first.claimedDate)
        assertEquals(0.9, first.extractionConfidence)
        // Garbage date degrades to null instead of failing the row.
        assertEquals(null, claims[1].claimedDate)
        assertTrue(claims.all { it.subjectId == "s1" && it.assetId == "a1" })
        assertTrue(claims.all { it.authenticityTier == AuthenticityTier.HIGH })
        assertTrue(claims.all { it.authenticityScore == null })
        // Ids are assigned by the caller (Stage2Service), not the extractor.
        assertTrue(claims.all { it.id.isBlank() })
    }

    @Test
    fun `salvages a response truncated at the output-token cap`() {
        val truncated =
            """[
              {"text":"Claim one","claimType":"SKILL","confidence":0.6},
              {"text":"Claim two","claimType":"EPISODE","confidence":0.9},
              {"text":"Truncated mid-prop"""

        val claims =
            ClaimExtractor(StubGemini(truncated), promptService())
                .extract("s1", asset(), transcript)

        assertEquals(2, claims.size)
        assertEquals("Claim one", claims[0].text)
    }

    @Test
    fun `refuses when the gemini provider is unavailable`() {
        assertFailsWith<IllegalStateException> {
            ClaimExtractor(UnavailableGemini(), promptService()).extract("s1", asset(), transcript)
        }
    }

    private val claimJson =
        """[{"text":"Explains graph modelling","claimType":"SKILL","confidence":0.6}]"""

    @Test
    fun `content without a row falls back to its code default with version 0 provenance`() {
        val gemini = StubGemini(claimJson)

        val claims =
            ClaimExtractor(gemini, promptService())
                .extract("s1", asset(contentType = ContentType.SKILL_DEMO), transcript)

        assertTrue(
            gemini.lastPrompt!!.contains(ExtractionPrompt.builtinFor(ContentType.SKILL_DEMO))
        )
        val claim = claims.single()
        assertEquals("SKILL_DEMO", claim.extractionPromptId)
        assertEquals(0, claim.extractionPromptVersion)
        assertTrue(claim.extractionPromptHash!!.isNotBlank())
    }

    @Test
    fun `each content type gets its own code default block`() {
        val gemini = StubGemini(claimJson)

        val claims = ClaimExtractor(gemini, promptService()).extract("s1", asset(), transcript)

        val builtin = ExtractionPrompt.builtinFor(ContentType.MANAGER_ENDORSEMENT)
        assertTrue(gemini.lastPrompt!!.contains(builtin))
        assertFalse(
            gemini.lastPrompt!!.contains(ExtractionPrompt.builtinFor(ContentType.SKILL_DEMO))
        )
        assertEquals("MANAGER_ENDORSEMENT", claims.single().extractionPromptId)
        assertEquals(0, claims.single().extractionPromptVersion)
    }

    @Test
    fun `a stored prompt row overrides the code default and stamps its version and hash`() {
        val gemini = StubGemini(claimJson)
        val row =
            ExtractionPrompt(
                id = ContentType.SKILL_DEMO.name,
                instructions = "Weigh endorser seniority when scoring confidence.",
                version = 3,
            )

        val claims =
            ClaimExtractor(gemini, promptService(row))
                .extract("s1", asset(contentType = ContentType.SKILL_DEMO), transcript)

        assertTrue(gemini.lastPrompt!!.contains("Weigh endorser seniority"))
        assertFalse(
            gemini.lastPrompt!!.contains(ExtractionPrompt.builtinFor(ContentType.SKILL_DEMO))
        )
        val claim = claims.single()
        assertEquals("SKILL_DEMO", claim.extractionPromptId)
        assertEquals(3, claim.extractionPromptVersion)
        assertTrue(claim.extractionPromptHash!!.isNotBlank())
    }
}

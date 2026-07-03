package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.persistence.ProviderRepository
import ai.vishwakarma.labelling.service.ProviderService
import com.google.cloud.firestore.Firestore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

// A GeminiDrafting whose network call is replaced by a canned response (the class is all-open via
// the kotlin-spring plugin; the mocked Firestore in the provider chain is never touched).
private open class StubGemini(private val canned: String) :
    GeminiDrafting(
        AppProperties(),
        ProviderService(ProviderRepository(mock(Firestore::class.java)))
    ) {

    override fun available(): Boolean = true

    override fun generate(prompt: String, maxTokens: Int?, thinkingBudget: Int?): String = canned
}

private class UnavailableGemini : StubGemini("") {
    override fun available(): Boolean = false
}

/** [ClaimExtractor]'s tolerant parse: fences stripped, bad rows dropped, prior seeded. */
class ClaimExtractorTest {

    private val transcript =
        Transcript(
            segments =
                listOf(TranscriptSegment("Speaker 2", 6.5, 15.0, "I managed him for three years.")),
            language = "en-US",
        )

    private fun asset(prior: AuthenticityTier = AuthenticityTier.MEDIUM) =
        Asset(
            id = "a1",
            subjectId = "s1",
            title = "Endorser call",
            modality = AssetModality.AUDIO,
            sourceClass = ContentType.MANAGER_ENDORSEMENT.sourceClass,
            contentType = ContentType.MANAGER_ENDORSEMENT,
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
            ClaimExtractor(StubGemini(response))
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

        val claims = ClaimExtractor(StubGemini(truncated)).extract("s1", asset(), transcript)

        assertEquals(2, claims.size)
        assertEquals("Claim one", claims[0].text)
    }

    @Test
    fun `refuses when the gemini provider is unavailable`() {
        assertFailsWith<IllegalStateException> {
            ClaimExtractor(UnavailableGemini()).extract("s1", asset(), transcript)
        }
    }
}

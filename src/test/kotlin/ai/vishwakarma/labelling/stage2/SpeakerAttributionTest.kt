package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.persistence.ProviderRepository
import ai.vishwakarma.labelling.service.ProviderService
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.mockito.Mockito.mock

// A GeminiDrafting whose network call is a canned string (all-open via kotlin-spring; the mocked
// Firestore in the provider chain is never touched).
private open class AttribStubGemini(private val canned: String, private val avail: Boolean = true) :
    GeminiDrafting(
        AppProperties(),
        ProviderService(ProviderRepository(mock(Firestore::class.java)))
    ) {
    var called = false

    override fun available(): Boolean = avail

    override fun generate(
        prompt: String,
        maxTokens: Int?,
        thinkingBudget: Int?,
        temperature: Double?,
    ): String {
        called = true
        return canned
    }
}

/**
 * §12.4 first-pass role resolution: the speaker-count guard, tolerant parse, and unavailability.
 */
class SpeakerAttributionTest {

    private fun asset(contentType: ContentType = ContentType.MANAGER_ENDORSEMENT) =
        Asset(
            id = "a1",
            subjectId = "s1",
            title = "Endorser call",
            modality = AssetModality.AUDIO,
            sourceClass = contentType.sourceClass,
            contentType = contentType,
            relationship = Relationship.MANAGER,
            authenticityPrior = AuthenticityTier.MEDIUM,
            sourceName = "Jane Manager",
        )

    private fun twoSpeaker() =
        Transcript(
            segments =
                listOf(
                    TranscriptSegment("Speaker 1", 0.0, 5.0, "How do you know them?"),
                    TranscriptSegment("Speaker 2", 5.0, 12.0, "I managed them for three years."),
                ),
            language = "en-US",
        )

    @Test
    fun `returns null for a single-speaker transcript without calling the model`() {
        val gemini = AttribStubGemini("{}")
        val single = Transcript(listOf(TranscriptSegment("Speaker 1", 0.0, 5.0, "Hi")), "en-US")

        assertNull(SpeakerAttribution(gemini).resolve(asset(), single))
        assertFalse(gemini.called)
    }

    @Test
    fun `returns null when gemini is unavailable`() {
        assertNull(
            SpeakerAttribution(AttribStubGemini("{}", avail = false)).resolve(asset(), twoSpeaker())
        )
    }

    @Test
    fun `parses a role binding + confidence and keeps relationship only for endorsers`() {
        val json =
            """{"confidence":0.95,"speakers":{"Speaker 1":{"role":"INTERVIEWER"},"Speaker 2":{"role":"ENDORSER","relationship":"MANAGER","name":"Jane"}}}"""

        val res = SpeakerAttribution(AttribStubGemini(json)).resolve(asset(), twoSpeaker())!!

        assertEquals(0.95, res.confidence)
        assertEquals(SpeakerRole.INTERVIEWER, res.binding["Speaker 1"]!!.role)
        assertNull(res.binding["Speaker 1"]!!.relationship)
        assertEquals(SpeakerRole.ENDORSER, res.binding["Speaker 2"]!!.role)
        assertEquals(Relationship.MANAGER, res.binding["Speaker 2"]!!.relationship)
        assertEquals("Jane", res.binding["Speaker 2"]!!.name)
    }

    @Test
    fun `drops unknown labels and rows with an invalid role, defaulting confidence`() {
        val json =
            """{"speakers":{"Speaker 1":{"role":"SUBJECT"},"Speaker 9":{"role":"ENDORSER"},"Speaker 2":{"role":"WAT"}}}"""

        val res = SpeakerAttribution(AttribStubGemini(json)).resolve(asset(), twoSpeaker())!!

        assertEquals(
            setOf("Speaker 1"),
            res.binding.keys
        ) // Speaker 9 not in transcript; Speaker 2 bad role
        assertEquals(SpeakerRole.SUBJECT, res.binding["Speaker 1"]!!.role)
        assertEquals(0.0, res.confidence) // missing confidence → 0.0 → gates to manual
    }

    @Test
    fun `returns null when the model output is unparseable`() {
        assertNull(
            SpeakerAttribution(AttribStubGemini("not json at all")).resolve(asset(), twoSpeaker())
        )
    }
}

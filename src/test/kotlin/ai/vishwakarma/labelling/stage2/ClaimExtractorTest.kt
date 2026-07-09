package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.ExtractionPrompt
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
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
    var lastMime: String? = null
    var lastBytes: ByteArray? = null

    override fun available(): Boolean = true

    override fun generate(
        prompt: String,
        maxTokens: Int?,
        thinkingBudget: Int?,
        temperature: Double?,
    ): String {
        lastPrompt = prompt
        return canned
    }

    override fun generateWithInline(
        prompt: String,
        mimeType: String,
        bytes: ByteArray,
        maxTokens: Int?,
        thinkingBudget: Int?,
    ): String {
        lastPrompt = prompt
        lastMime = mimeType
        lastBytes = bytes
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

    // ---- markers (§7.1.1) -----------------------------------------------------

    @Test
    fun `stamps denormalised source markers and defaults basis to STATED`() {
        val claim =
            ClaimExtractor(StubGemini(claimJson), promptService())
                .extract("s1", asset(), transcript)
                .single()

        assertEquals(SourceClass.ENDORSEMENT, claim.sourceClass)
        assertEquals(Relationship.MANAGER, claim.relationship)
        assertEquals(ClaimBasis.STATED, claim.claimBasis)
        assertFalse(claim.sensitive)
    }

    @Test
    fun `parses basis INFERRED and the sensitive flag when the model emits them`() {
        val json =
            """[{"text":"Explains graph modelling","claimType":"SKILL","confidence":0.6,"basis":"INFERRED","sensitive":false},
                {"text":"Reachable at a@b.com","claimType":"IDENTITY","confidence":1.0,"basis":"STATED","sensitive":true}]"""

        val claims =
            ClaimExtractor(StubGemini(json), promptService()).extract("s1", asset(), transcript)

        assertEquals(ClaimBasis.INFERRED, claims[0].claimBasis)
        assertFalse(claims[0].sensitive)
        assertEquals(ClaimBasis.STATED, claims[1].claimBasis)
        assertTrue(claims[1].sensitive)
    }

    @Test
    fun `parses favorability, clamps out-of-range values, and leaves it null when absent`() {
        val json =
            """[{"text":"Repeated an academic year","claimType":"EPISODE","confidence":0.9,"favorability":0.2},
                {"text":"Won a national award","claimType":"EPISODE","confidence":0.9,"favorability":1.4},
                {"text":"Documented a growth area","claimType":"WEAKNESS","confidence":0.9,"favorability":-0.3},
                {"text":"Holds a plain factual role","claimType":"IDENTITY","confidence":0.9}]"""

        val claims =
            ClaimExtractor(StubGemini(json), promptService()).extract("s1", asset(), transcript)

        assertEquals(0.2, claims[0].favorability)
        // Out-of-range scores are clamped into 0..1, not dropped.
        assertEquals(1.0, claims[1].favorability)
        assertEquals(0.0, claims[2].favorability)
        // Absent → null (review-required downstream, never silently auto-approved — §12.6).
        assertEquals(null, claims[3].favorability)
    }

    // ---- §12.4 multi-speaker re-weight ----------------------------------------

    private val multiSpeakerJson =
        """[
          {"text":"Led the 2021 gateway migration","claimType":"EPISODE","speaker":"Speaker 2","confidence":0.9},
          {"text":"Values delegating early","claimType":"VALUE","speaker":"Speaker 1","confidence":0.8},
          {"text":"So what happened next?","claimType":"EPISODE","speaker":"Speaker 3","confidence":0.5}
        ]"""

    private fun binding() =
        mapOf(
            "Speaker 1" to SpeakerAssignment(SpeakerRole.SUBJECT),
            "Speaker 2" to SpeakerAssignment(SpeakerRole.ENDORSER, Relationship.MANAGER),
            "Speaker 3" to SpeakerAssignment(SpeakerRole.INTERVIEWER),
        )

    @Test
    fun `re-weights each claim by its speaker role and drops interviewer spans`() {
        val claims =
            ClaimExtractor(StubGemini(multiSpeakerJson), promptService())
                .extract(
                    "s1",
                    asset(prior = AuthenticityTier.MEDIUM),
                    transcript,
                    speakerRoles = binding()
                )

        // Speaker 3 (interviewer) is dropped even though the model emitted a row for it.
        assertEquals(2, claims.size)
        val bySpeaker = claims.associateBy { it.speaker }

        val endorser = bySpeaker.getValue("Speaker 2")
        assertEquals(SpeakerRole.ENDORSER, endorser.speakerRole)
        assertEquals(SourceClass.ENDORSEMENT, endorser.sourceClass)
        assertEquals(Relationship.MANAGER, endorser.relationship)
        assertEquals(AuthenticityTier.MEDIUM, endorser.authenticityTier)

        val subject = bySpeaker.getValue("Speaker 1")
        assertEquals(SpeakerRole.SUBJECT, subject.speakerRole)
        assertEquals(SourceClass.SELF, subject.sourceClass)
        assertEquals(Relationship.SELF, subject.relationship)
        assertEquals(AuthenticityTier.LOW, subject.authenticityTier)
    }

    @Test
    fun `the resolved speaker roles are written into the extraction prompt`() {
        val gemini = StubGemini(multiSpeakerJson)

        ClaimExtractor(gemini, promptService())
            .extract("s1", asset(), transcript, speakerRoles = binding())

        val prompt = gemini.lastPrompt!!
        assertTrue(prompt.contains("Speaker roles in this transcript"))
        assertTrue(prompt.contains("Speaker 2: an ENDORSER (MANAGER)"))
        assertTrue(prompt.contains("do NOT extract claims from this speaker")) // interviewer line
    }

    @Test
    fun `without a binding claims keep asset-level provenance and a null speaker role`() {
        val json =
            """[{"text":"Led the migration","claimType":"EPISODE","speaker":"Speaker 2","confidence":0.9}]"""

        val claim =
            ClaimExtractor(StubGemini(json), promptService())
                .extract("s1", asset(prior = AuthenticityTier.MEDIUM), transcript)
                .single()

        assertEquals(null, claim.speakerRole)
        assertEquals(SourceClass.ENDORSEMENT, claim.sourceClass)
        assertEquals(Relationship.MANAGER, claim.relationship)
        assertEquals(AuthenticityTier.MEDIUM, claim.authenticityTier)
    }

    // ---- extractDocument (IMAGE/DOCUMENT lane) --------------------------------

    private fun docAsset(contentType: ContentType = ContentType.CERTIFICATE) =
        Asset(
            id = "d1",
            subjectId = "s1",
            title = "Cloud architect certificate",
            modality = AssetModality.IMAGE,
            sourceClass = contentType.sourceClass,
            contentType = contentType,
            relationship = Relationship.SELF,
            authenticityPrior = AuthenticityTier.HIGH,
            mimeType = "image/png",
        )

    private val documentJson =
        """[{"text":"Was awarded the Professional Cloud Architect certification by Meridian Institute","claimType":"EPISODE","sourceExcerpt":"Professional Cloud Architect","claimedDate":"2024-03-12","confidence":0.95}]"""

    @Test
    fun `extractDocument sends the bytes inline under the resolved content-type block`() {
        val gemini = StubGemini(documentJson)
        val bytes = "scanned certificate".toByteArray()

        val claims =
            ClaimExtractor(gemini, promptService())
                .extractDocument("s1", docAsset(), bytes, "image/png", "Test Subject")

        assertEquals("image/png", gemini.lastMime)
        assertTrue(gemini.lastBytes!!.contentEquals(bytes))
        val prompt = gemini.lastPrompt!!
        assertTrue(prompt.contains(ExtractionPrompt.builtinFor(ContentType.CERTIFICATE)))
        assertTrue(prompt.contains("printed text"))
        assertTrue(prompt.contains("Test Subject"))
        assertFalse(prompt.contains("Transcript:"))

        val claim = claims.single()
        assertEquals(null, claim.speaker)
        assertEquals(null, claim.mediaStart)
        assertEquals(null, claim.mediaEnd)
        assertEquals("Professional Cloud Architect", claim.sourceExcerpt)
        assertEquals(LocalDate.parse("2024-03-12"), claim.claimedDate)
        assertEquals(AuthenticityTier.HIGH, claim.authenticityTier)
        assertEquals("CERTIFICATE", claim.extractionPromptId)
        assertEquals(0, claim.extractionPromptVersion)
        assertTrue(claim.extractionPromptHash!!.isNotBlank())
        assertTrue(claim.id.isBlank())
    }

    @Test
    fun `a stored prompt row drives extractDocument too`() {
        val gemini = StubGemini(documentJson)
        val row =
            ExtractionPrompt(
                id = ContentType.CERTIFICATE.name,
                instructions = "Prefer the issuing body's registered name.",
                version = 2,
            )

        val claims =
            ClaimExtractor(gemini, promptService(row))
                .extractDocument("s1", docAsset(), "bytes".toByteArray(), "application/pdf")

        assertTrue(gemini.lastPrompt!!.contains("Prefer the issuing body's registered name."))
        assertFalse(
            gemini.lastPrompt!!.contains(ExtractionPrompt.builtinFor(ContentType.CERTIFICATE))
        )
        assertEquals(2, claims.single().extractionPromptVersion)
    }

    @Test
    fun `extractDocument refuses when the gemini provider is unavailable`() {
        assertFailsWith<IllegalStateException> {
            ClaimExtractor(UnavailableGemini(), promptService())
                .extractDocument("s1", docAsset(), "bytes".toByteArray(), "image/png")
        }
    }

    @Test
    fun `extractDocument stamps the source markers from a DOCUMENTARY asset`() {
        val claim =
            ClaimExtractor(StubGemini(documentJson), promptService())
                .extractDocument("s1", docAsset(), "b".toByteArray(), "image/png")
                .single()

        assertEquals(SourceClass.DOCUMENTARY, claim.sourceClass)
        assertEquals(Relationship.SELF, claim.relationship)
        assertEquals(ClaimBasis.STATED, claim.claimBasis)
    }
}

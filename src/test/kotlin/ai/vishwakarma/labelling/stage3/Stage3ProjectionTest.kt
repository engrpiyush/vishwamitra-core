package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.service.ApprovedClaim
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Stage3ProjectionTest {

    private val subjectId = "s1"

    private fun claim(
        id: String,
        assetId: String,
        sourceClass: SourceClass = SourceClass.SELF,
        relationship: Relationship? = Relationship.SELF,
        speaker: String? = null,
        speakerRole: SpeakerRole? = null,
        tier: AuthenticityTier = AuthenticityTier.LOW,
        date: LocalDate? = null,
    ): Claim =
        Claim(
            id = id,
            subjectId = subjectId,
            assetId = assetId,
            claimType = ClaimType.SKILL,
            text = "text of $id",
            speaker = speaker,
            speakerRole = speakerRole,
            claimedDate = date,
            authenticityTier = tier,
            sourceClass = sourceClass,
            relationship = relationship,
            favorability = 0.8,
        )

    private fun asset(id: String, contentType: ContentType, relationship: Relationship): Asset =
        Asset(
            id = id,
            subjectId = subjectId,
            title = id,
            modality = AssetModality.AUDIO,
            sourceClass = contentType.sourceClass,
            contentType = contentType,
            relationship = relationship,
            checksum = "sum-$id",
        )

    @Test
    fun `all SELF claims share the subject's single SUBJECT attestor`() {
        val projection =
            buildEvidenceProjection(
                subjectId = subjectId,
                subjectName = "Asha",
                approved =
                    listOf(
                        ApprovedClaim(claim("c1", "a1"), null, emptyList()),
                        ApprovedClaim(claim("c2", "a1"), null, emptyList()),
                    ),
                assets = listOf(asset("a1", ContentType.SELF_INTERVIEW, Relationship.SELF)),
                speakerBindings = emptyMap(),
            )
        assertEquals(listOf("subject:s1", "subject:s1"), projection.claims.map { it.attestorKey })
        assertEquals(1, projection.attestors.size)
        with(projection.attestors.single()) {
            assertEquals("SUBJECT", kind)
            assertEquals("Asha", name)
            assertEquals(TRUST_PRIOR_SUBJECT, trustPrior)
        }
    }

    @Test
    fun `a named endorser collapses to one global attestor across assets`() {
        val binding =
            mapOf(
                "Speaker 2" to
                    SpeakerAssignment(SpeakerRole.ENDORSER, Relationship.MANAGER, "R. Mehta")
            )
        val bindingOtherCase =
            mapOf(
                "Speaker 1" to
                    SpeakerAssignment(SpeakerRole.ENDORSER, Relationship.MANAGER, "r.  mehta")
            )
        val projection =
            buildEvidenceProjection(
                subjectId = subjectId,
                subjectName = null,
                approved =
                    listOf(
                        ApprovedClaim(
                            claim(
                                "c1",
                                "a1",
                                sourceClass = SourceClass.ENDORSEMENT,
                                relationship = Relationship.MANAGER,
                                speaker = "Speaker 2",
                                speakerRole = SpeakerRole.ENDORSER,
                            ),
                            null,
                            emptyList(),
                        ),
                        ApprovedClaim(
                            claim(
                                "c2",
                                "a2",
                                sourceClass = SourceClass.ENDORSEMENT,
                                relationship = Relationship.MANAGER,
                                speaker = "Speaker 1",
                                speakerRole = SpeakerRole.ENDORSER,
                            ),
                            null,
                            emptyList(),
                        ),
                    ),
                assets =
                    listOf(
                        asset("a1", ContentType.MANAGER_ENDORSEMENT, Relationship.MANAGER),
                        asset("a2", ContentType.MANAGER_ENDORSEMENT, Relationship.MANAGER),
                    ),
                speakerBindings = mapOf("a1" to binding, "a2" to bindingOtherCase),
            )
        // Case/whitespace-normalized name key: both claims hit the same global trust node.
        assertEquals(1, projection.attestors.size)
        assertEquals("endorser:name:r. mehta", projection.attestors.single().attestorKey)
        assertEquals("ENDORSER", projection.attestors.single().kind)
    }

    @Test
    fun `unnamed voices key per asset-and-speaker, documentary claims per issuer-asset`() {
        val projection =
            buildEvidenceProjection(
                subjectId = subjectId,
                subjectName = null,
                approved =
                    listOf(
                        ApprovedClaim(
                            claim(
                                "c1",
                                "a1",
                                sourceClass = SourceClass.ENDORSEMENT,
                                relationship = Relationship.PEER,
                                speaker = "Speaker 3",
                                speakerRole = SpeakerRole.ENDORSER,
                            ),
                            null,
                            emptyList(),
                        ),
                        ApprovedClaim(
                            claim(
                                "c2",
                                "a2",
                                sourceClass = SourceClass.DOCUMENTARY,
                                relationship = Relationship.INSTITUTION,
                                tier = AuthenticityTier.HIGH,
                                date = LocalDate.of(2016, 6, 1),
                            ),
                            null,
                            emptyList(),
                        ),
                    ),
                assets =
                    listOf(
                        asset("a1", ContentType.PEER_ENDORSEMENT, Relationship.PEER),
                        asset("a2", ContentType.DEGREE_TRANSCRIPT, Relationship.INSTITUTION),
                    ),
                speakerBindings = emptyMap(),
            )
        val byKey = projection.attestors.associateBy { it.attestorKey }
        assertEquals(setOf("endorser:asset:a1:Speaker 3", "issuer:asset:a2"), byKey.keys)
        assertEquals("ISSUER", byKey["issuer:asset:a2"]?.kind)
        assertEquals(TRUST_PRIOR_ISSUER, byKey["issuer:asset:a2"]?.trustPrior)
        // Node rows carry the ISO date + tier seed the scorer reads.
        val c2 = projection.claims.single { it.claimId == "c2" }
        assertEquals("2016-06-01", c2.claimedDate)
        assertEquals("HIGH", c2.tierSeed)
    }

    @Test
    fun `sidecar citations are filtered to the approved set and counted`() {
        val reviewed = Instant.parse("2026-07-08T10:00:00Z")
        val projection =
            buildEvidenceProjection(
                subjectId = subjectId,
                subjectName = null,
                approved =
                    listOf(
                        ApprovedClaim(claim("c1", "a1"), null, emptyList()),
                        // The sidecar cites one approved claim (kept) and one rejected (dropped).
                        ApprovedClaim(
                            claim("c2", "a1"),
                            "I led the backend workstream",
                            listOf("c1", "c9")
                        ),
                    ),
                assets = listOf(asset("a1", ContentType.SELF_INTERVIEW, Relationship.SELF)),
                speakerBindings = emptyMap(),
                reviews =
                    mapOf(
                        "c2" to
                            ClaimReview(
                                claimId = "c2",
                                subjectId = subjectId,
                                decision = ReviewDecision.SIDECARED,
                                justification = "I led the backend workstream",
                                corroboratingClaimIds = listOf("c1", "c9"),
                                reviewedBy = "op@vishwakarma.ai",
                                reviewedAt = reviewed,
                            )
                    ),
            )
        val explanation = projection.explanations.single()
        assertEquals("c2", explanation.explanationId)
        assertEquals(listOf("c1"), explanation.cites)
        assertEquals(1, projection.citationsDropped)
        assertEquals("op@vishwakarma.ai", explanation.author)
        assertEquals(reviewed.toString(), explanation.createdAt)
    }

    @Test
    fun `endorser trust priors follow the relationship ladder`() {
        assertEquals(0.80, endorserTrustPrior(Relationship.EXPERT))
        assertEquals(0.65, endorserTrustPrior(Relationship.MANAGER))
        assertEquals(0.60, endorserTrustPrior(Relationship.PEER))
        assertEquals(0.50, endorserTrustPrior(Relationship.UNKNOWN))
        assertEquals(0.50, endorserTrustPrior(null))
        assertTrue(endorserTrustPrior(Relationship.EXPERT) > endorserTrustPrior(Relationship.PEER))
    }
}

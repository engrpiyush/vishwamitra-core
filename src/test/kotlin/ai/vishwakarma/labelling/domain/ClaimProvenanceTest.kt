package ai.vishwakarma.labelling.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [claimProvenance] — the §12.4 per-claim re-weight. The asset under test is a MANAGER_ENDORSEMENT
 * (ENDORSEMENT / MANAGER / MEDIUM) so the SUBJECT case demonstrably diverges from the asset
 * default.
 */
class ClaimProvenanceTest {

    private fun asset(
        contentType: ContentType = ContentType.MANAGER_ENDORSEMENT,
        relationship: Relationship = Relationship.MANAGER,
        prior: AuthenticityTier = AuthenticityTier.MEDIUM,
    ) =
        Asset(
            id = "a1",
            subjectId = "s1",
            title = "Endorser call",
            modality = AssetModality.AUDIO,
            sourceClass = contentType.sourceClass,
            contentType = contentType,
            relationship = relationship,
            authenticityPrior = prior,
        )

    @Test
    fun `no binding inherits the asset's uniform provenance (pre-12_4 behavior)`() {
        val p = claimProvenance(null, asset())

        assertTrue(p.keep)
        assertEquals(SourceClass.ENDORSEMENT, p.sourceClass)
        assertEquals(Relationship.MANAGER, p.relationship)
        assertEquals(AuthenticityTier.MEDIUM, p.authenticityTier)
    }

    @Test
    fun `a subject speaker is self-report LOW even inside an endorsement asset`() {
        val p = claimProvenance(SpeakerAssignment(SpeakerRole.SUBJECT), asset())

        assertTrue(p.keep)
        assertEquals(SourceClass.SELF, p.sourceClass)
        assertEquals(Relationship.SELF, p.relationship)
        assertEquals(AuthenticityTier.LOW, p.authenticityTier)
    }

    @Test
    fun `an endorser speaker is tiered by their own relationship`() {
        val expert =
            claimProvenance(SpeakerAssignment(SpeakerRole.ENDORSER, Relationship.EXPERT), asset())
        assertEquals(SourceClass.ENDORSEMENT, expert.sourceClass)
        assertEquals(Relationship.EXPERT, expert.relationship)
        assertEquals(AuthenticityTier.HIGH, expert.authenticityTier)

        val peer =
            claimProvenance(SpeakerAssignment(SpeakerRole.ENDORSER, Relationship.PEER), asset())
        assertEquals(AuthenticityTier.MEDIUM, peer.authenticityTier)

        val family =
            claimProvenance(SpeakerAssignment(SpeakerRole.ENDORSER, Relationship.FAMILY), asset())
        assertEquals(AuthenticityTier.LOW, family.authenticityTier)
    }

    @Test
    fun `an endorser without a stated relationship falls back to the asset's`() {
        val p =
            claimProvenance(SpeakerAssignment(SpeakerRole.ENDORSER, relationship = null), asset())

        assertEquals(SourceClass.ENDORSEMENT, p.sourceClass)
        assertEquals(Relationship.MANAGER, p.relationship)
        assertEquals(AuthenticityTier.MEDIUM, p.authenticityTier)
    }

    @Test
    fun `interviewer and other spans are dropped (not evidence about the subject)`() {
        assertFalse(claimProvenance(SpeakerAssignment(SpeakerRole.INTERVIEWER), asset()).keep)
        assertFalse(claimProvenance(SpeakerAssignment(SpeakerRole.OTHER), asset()).keep)
    }
}

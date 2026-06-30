package ai.vishwakarma.labelling.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntakeTaxonomyTest {

    // ---- defaultPrior truth table ----------------------------------------

    @Test
    fun `documentary anchors are HIGH regardless of relationship`() {
        for (rel in Relationship.entries) {
            assertEquals(
                AuthenticityTier.HIGH,
                defaultPrior(ContentType.CERTIFICATE, rel),
                "CERTIFICATE with $rel",
            )
        }
        assertEquals(AuthenticityTier.HIGH, defaultPrior(ContentType.AWARD_HONOR, null))
    }

    @Test
    fun `self-originated content is LOW`() {
        assertEquals(
            AuthenticityTier.LOW,
            defaultPrior(ContentType.SELF_INTERVIEW, Relationship.SELF)
        )
        assertEquals(AuthenticityTier.LOW, defaultPrior(ContentType.RESUME_CV, null))
    }

    @Test
    fun `event captures are MEDIUM`() {
        assertEquals(AuthenticityTier.MEDIUM, defaultPrior(ContentType.SPEECH_TALK, null))
        assertEquals(
            AuthenticityTier.MEDIUM,
            defaultPrior(ContentType.AWARD_CEREMONY, Relationship.PRESS),
        )
    }

    @Test
    fun `public profiles split HIGH for verifiable artifacts and MEDIUM for social`() {
        assertEquals(AuthenticityTier.HIGH, defaultPrior(ContentType.GITHUB, null))
        assertEquals(AuthenticityTier.HIGH, defaultPrior(ContentType.APP_STORE_LISTING, null))
        assertEquals(AuthenticityTier.HIGH, defaultPrior(ContentType.SCHOLARLY_PROFILE, null))
        assertEquals(AuthenticityTier.MEDIUM, defaultPrior(ContentType.LINKEDIN, null))
        assertEquals(AuthenticityTier.MEDIUM, defaultPrior(ContentType.SOCIAL_PROFILE, null))
    }

    @Test
    fun `endorsement prior is driven by the relationship`() {
        assertEquals(
            AuthenticityTier.HIGH,
            defaultPrior(ContentType.EXPERT_INTERVIEW, Relationship.EXPERT),
        )
        assertEquals(
            AuthenticityTier.MEDIUM,
            defaultPrior(ContentType.PEER_ENDORSEMENT, Relationship.PEER),
        )
        assertEquals(
            AuthenticityTier.MEDIUM,
            defaultPrior(ContentType.MANAGER_ENDORSEMENT, Relationship.MANAGER),
        )
        assertEquals(
            AuthenticityTier.LOW,
            defaultPrior(ContentType.PERSONAL_REFERENCE, Relationship.FAMILY),
        )
        // Unknown/absent relationship on an endorsement is the cautious LOW.
        assertEquals(AuthenticityTier.LOW, defaultPrior(ContentType.RECOMMENDATION_LETTER, null))
        assertEquals(
            AuthenticityTier.LOW,
            defaultPrior(ContentType.CLIENT_TESTIMONIAL, Relationship.UNKNOWN),
        )
    }

    // ---- structural invariants -------------------------------------------

    @Test
    fun `every content type carries the documented base prior for its class`() {
        for (ct in ContentType.entries) {
            val expected =
                when (ct.sourceClass) {
                    SourceClass.SELF -> AuthenticityTier.LOW
                    SourceClass.DOCUMENTARY -> AuthenticityTier.HIGH
                    SourceClass.EVENT_CAPTURE -> AuthenticityTier.MEDIUM
                    SourceClass.ENDORSEMENT -> AuthenticityTier.MEDIUM
                    // PUBLIC_PROFILE is per-value (HIGH or MEDIUM); just assert it's set sanely.
                    SourceClass.PUBLIC_PROFILE -> ct.basePrior
                }
            assertEquals(expected, ct.basePrior, "basePrior for $ct")
        }
    }

    @Test
    fun `non-endorsement default prior equals the content type base prior`() {
        for (ct in ContentType.entries.filter { it.sourceClass != SourceClass.ENDORSEMENT }) {
            assertEquals(
                ct.basePrior,
                defaultPrior(ct, Relationship.UNKNOWN),
                "defaultPrior for $ct"
            )
        }
    }

    // ---- fromOrNull tolerance --------------------------------------------

    @Test
    fun `fromOrNull parses valid names case-insensitively and rejects garbage`() {
        assertEquals(AssetModality.VIDEO, AssetModality.fromOrNull("video"))
        assertEquals(ContentType.GITHUB, ContentType.fromOrNull("  GitHub  "))
        assertEquals(Relationship.EXPERT, Relationship.fromOrNull("EXPERT"))
        assertEquals(ConsentStatus.GRANTED, ConsentStatus.fromOrNull("granted"))
        assertEquals(SourceClass.DOCUMENTARY, SourceClass.fromOrNull("documentary"))

        assertNull(ContentType.fromOrNull("not_a_type"))
        assertNull(AssetModality.fromOrNull(""))
        assertNull(Relationship.fromOrNull("   "))
        assertNull(ConsentStatus.fromOrNull(null))
    }
}

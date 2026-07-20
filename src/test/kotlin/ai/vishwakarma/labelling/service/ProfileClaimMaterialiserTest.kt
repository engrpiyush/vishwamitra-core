package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimOrigin
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.DeclaredType
import ai.vishwakarma.labelling.domain.DoNotDiscussApproval
import ai.vishwakarma.labelling.domain.DoNotDiscussCustom
import ai.vishwakarma.labelling.domain.EmploymentType
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.domain.SubjectProfile
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.SubjectProfileRepository
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock

private class MatFakeProfileRepo : SubjectProfileRepository(mock(Firestore::class.java)) {
    val store = mutableMapOf<String, SubjectProfile>()

    override fun findBySubject(subjectId: String): SubjectProfile? = store[subjectId]
}

private class MatFakeClaimRepo : ClaimRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Claim>()
    val saves = mutableListOf<Claim>()
    val deletes = mutableListOf<String>()

    override fun findByAsset(assetId: String): List<Claim> =
        store.values.filter { it.assetId == assetId }

    override fun save(claim: Claim) {
        store[claim.id] = claim
        saves += claim
    }

    override fun delete(id: String) {
        store.remove(id)
        deletes += id
    }
}

/**
 * [ProfileClaimMaterialiser]: the SubjectProfile §3.3/§3.4/§3.6 mapping — declared groups C/D
 * become provenance-tagged claims on the synthetic asset, idempotently, and only when the surface
 * is on.
 */
class ProfileClaimMaterialiserTest {

    private val profiles = MatFakeProfileRepo()
    private val claims = MatFakeClaimRepo()

    private fun materialiser(enabled: Boolean = true) =
        ProfileClaimMaterialiser(
            liveConfig(AppProperties(stage4 = AppProperties.Stage4(profileEnabled = enabled))),
            profiles,
            claims,
        )

    private val worked =
        SubjectProfile(
            subjectId = "s1",
            targetSeniority = "staff",
            employmentType = EmploymentType.FTE,
            aspirations = listOf("Optimising for staff-level IC work, not management."),
        )

    @Test
    fun `the worked-example aspiration materialises as a SUBJECT_DECLARED VALUE claim`() {
        profiles.store["s1"] = worked
        materialiser().materialise("s1")

        val aspiration =
            claims.store.values.single { it.declaredType == DeclaredType.STATED_ASPIRATION }
        assertEquals(ClaimType.VALUE, aspiration.claimType)
        assertEquals(ClaimOrigin.SUBJECT_DECLARED, aspiration.origin)
        assertTrue(aspiration.declared)
        assertEquals(ClaimBasis.STATED, aspiration.claimBasis)
        assertEquals(SourceClass.SELF, aspiration.sourceClass)
        assertEquals(Relationship.SELF, aspiration.relationship)
        assertEquals(SpeakerRole.SUBJECT, aspiration.speakerRole)
        // Verbatim — the subject's own sentence, not paraphrased (§3.7).
        assertEquals("Optimising for staff-level IC work, not management.", aspiration.text)
        // The synthetic sentinel asset (§3.3): non-null, and one no Stage2Job ever targets.
        assertEquals("declared:s1", aspiration.assetId)
        // Neutral favorability so it never draws a criticism probe (§3.5).
        assertEquals(0.5, aspiration.favorability)
        assertFalse(aspiration.sensitive)
        // Candidacy → IDENTITY (§3.4).
        assertTrue(
            claims.store.values.any {
                it.claimType == ClaimType.IDENTITY &&
                    it.declaredType == DeclaredType.ENGAGEMENT_MODEL
            }
        )
    }

    @Test
    fun `materialisation is idempotent across re-seals — same ids, no duplicates`() {
        profiles.store["s1"] = worked
        val first = materialiser().materialise("s1")
        val idsAfterFirst = claims.store.keys.toSet()

        val second = materialiser().materialise("s1")

        assertEquals(idsAfterFirst, claims.store.keys.toSet())
        assertTrue(second.created.isEmpty())
        assertEquals(first.created.size, second.kept.size)
    }

    @Test
    fun `a withdrawn declaration is reconciled away on the next seal`() {
        profiles.store["s1"] = worked
        materialiser().materialise("s1")
        val aspirationId =
            claims.store.values.single { it.declaredType == DeclaredType.STATED_ASPIRATION }.id

        // The subject removes the aspiration and re-seals.
        profiles.store["s1"] = worked.copy(aspirations = emptyList())
        val outcome = materialiser().materialise("s1")

        assertTrue(aspirationId in outcome.deleted)
        assertNull(claims.store[aspirationId])
        // The engagement-model claim (unchanged) is kept, not rewritten.
        assertTrue(claims.store.values.any { it.declaredType == DeclaredType.ENGAGEMENT_MODEL })
    }

    @Test
    fun `only an APPROVED custom do-not-discuss entry becomes a WEAKNESS boundary claim`() {
        fun seedCustom(state: DoNotDiscussApproval) {
            claims.store.clear()
            profiles.store["s1"] =
                SubjectProfile(
                    subjectId = "s1",
                    doNotDiscussCustom = DoNotDiscussCustom("my cap table", state),
                )
            materialiser().materialise("s1")
        }

        seedCustom(DoNotDiscussApproval.PENDING)
        assertTrue(claims.store.isEmpty())
        seedCustom(DoNotDiscussApproval.REJECTED)
        assertTrue(claims.store.isEmpty())

        seedCustom(DoNotDiscussApproval.APPROVED)
        val boundary = claims.store.values.single()
        assertEquals(ClaimType.WEAKNESS, boundary.claimType)
        assertEquals(DeclaredType.BOUNDARY, boundary.declaredType)
        // Neutral, so an approved boundary never becomes a criticism probe about the very topic the
        // subject asked to keep off the table (§3.5).
        assertEquals(0.5, boundary.favorability)
    }

    @Test
    fun `a curated do-not-discuss check records the boundary fact, never the substance`() {
        profiles.store["s1"] =
            SubjectProfile(subjectId = "s1", doNotDiscussChecks = listOf("health"))
        materialiser().materialise("s1")

        val boundary = claims.store.values.single()
        assertEquals(ClaimType.WEAKNESS, boundary.claimType)
        assertEquals("Prefers not to discuss health and medical history.", boundary.text)
    }

    @Test
    fun `nothing materialises while the profile surface is off — byte-for-byte pre-feature`() {
        profiles.store["s1"] = worked
        val outcome = materialiser(enabled = false).materialise("s1")

        assertTrue(claims.store.isEmpty())
        assertEquals(0, outcome.touched)
    }

    @Test
    fun `a blank C-or-D profile writes nothing`() {
        profiles.store["s1"] = SubjectProfile(subjectId = "s1", country = "IN")
        materialiser().materialise("s1")
        assertTrue(claims.store.isEmpty())
    }
}

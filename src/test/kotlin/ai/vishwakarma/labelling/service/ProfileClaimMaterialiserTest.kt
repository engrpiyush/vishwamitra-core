package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimOrigin
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContactField
import ai.vishwakarma.labelling.domain.ContactKind
import ai.vishwakarma.labelling.domain.DeclaredType
import ai.vishwakarma.labelling.domain.DoNotDiscussApproval
import ai.vishwakarma.labelling.domain.DoNotDiscussCustom
import ai.vishwakarma.labelling.domain.EmploymentType
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.domain.SubjectProfile
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.SubjectProfileRepository
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

private class MatFakeReviewRepo : ClaimReviewRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ClaimReview>()
    val deletes = mutableListOf<String>()

    override fun save(review: ClaimReview) {
        store[review.claimId] = review
    }

    override fun delete(claimId: String) {
        store.remove(claimId)
        deletes += claimId
    }

    override fun findBySubject(subjectId: String): List<ClaimReview> =
        store.values.filter { it.subjectId == subjectId }
}

/** A review store that trips one transient error on the next [save] — models a Firestore 5xx. */
private class MatFlakyReviewRepo : ClaimReviewRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ClaimReview>()
    var failNextSave = false

    override fun save(review: ClaimReview) {
        if (failNextSave) {
            failNextSave = false
            throw RuntimeException("transient Firestore 5xx during review save")
        }
        store[review.claimId] = review
    }

    override fun findBySubject(subjectId: String): List<ClaimReview> =
        store.values.filter { it.subjectId == subjectId }
}

/**
 * [ProfileClaimMaterialiser]: the SubjectProfile §3.3/§3.4/§3.6 mapping — declared groups C/D
 * become provenance-tagged claims on the synthetic asset, idempotently, and only when the surface
 * is on.
 */
class ProfileClaimMaterialiserTest {

    private val profiles = MatFakeProfileRepo()
    private val claims = MatFakeClaimRepo()
    private val reviews = MatFakeReviewRepo()

    private fun materialiser(enabled: Boolean = true) =
        ProfileClaimMaterialiser(
            liveConfig(AppProperties(stage4 = AppProperties.Stage4(profileEnabled = enabled))),
            profiles,
            claims,
            reviews,
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

    // ---- E: contact / PII (VA-153) ----------------------------------------------------

    @Test
    fun `a shareable contact materialises as a sensitive IDENTITY claim with an INCLUDE review`() {
        profiles.store["s1"] =
            SubjectProfile(
                subjectId = "s1",
                contact =
                    listOf(ContactField(ContactKind.EMAIL, "asha@example.com", shareable = true)),
            )
        materialiser().materialise("s1")

        val contact =
            claims.store.values.single {
                it.declaredType == DeclaredType.contactType(ContactKind.EMAIL)
            }
        // Sensitive IDENTITY, declared, verbatim value carried into the Row-8 line (§5.2).
        assertTrue(contact.sensitive)
        assertEquals(ClaimType.IDENTITY, contact.claimType)
        assertEquals(ClaimOrigin.SUBJECT_DECLARED, contact.origin)
        assertEquals("declared-contact-email", contact.declaredType)
        assertEquals("Email: asha@example.com.", contact.text)
        // The opt-in lives on the review, not the claim — without it approvedForDownstream drops
        // the sensitive claim (§5.2). The per-field shareable flag is that opt-in (§5.3).
        val review = reviews.store[contact.id]
        assertEquals(PiiChoice.INCLUDE, review?.piiChoice)
        assertEquals("s1", review?.subjectId)
    }

    @Test
    fun `a non-shareable contact is never materialised — no claim, no opt-in`() {
        profiles.store["s1"] =
            SubjectProfile(
                subjectId = "s1",
                contact = listOf(ContactField(ContactKind.PHONE, "+91 555 0100")),
            )
        val outcome = materialiser().materialise("s1")

        // Private by default: it stays out of the ledger entirely, so it never reaches the planner
        // (F3) and keeps the trained REFUSE posture (§5.2, PRECEDENCE I2).
        assertTrue(claims.store.isEmpty())
        assertTrue(reviews.store.isEmpty())
        assertEquals(0, outcome.touched)
    }

    @Test
    fun `un-sharing a contact reconciles away both the claim and its opt-in review`() {
        val shared =
            SubjectProfile(
                subjectId = "s1",
                contact = listOf(ContactField(ContactKind.LINKEDIN, "in/asha", shareable = true)),
            )
        profiles.store["s1"] = shared
        materialiser().materialise("s1")
        val contactId =
            claims.store.values
                .single { it.declaredType == DeclaredType.contactType(ContactKind.LINKEDIN) }
                .id
        assertEquals(PiiChoice.INCLUDE, reviews.store[contactId]?.piiChoice)

        // The subject flips the same contact back to private and re-seals.
        profiles.store["s1"] =
            shared.copy(contact = listOf(ContactField(ContactKind.LINKEDIN, "in/asha")))
        val outcome = materialiser().materialise("s1")

        assertTrue(contactId in outcome.deleted)
        assertNull(claims.store[contactId])
        // The dangling INCLUDE must not survive: a later same-id claim would silently inherit it.
        assertNull(reviews.store[contactId])
        assertTrue(contactId in reviews.deletes)
    }

    @Test
    fun `a profile with only a shareable contact still materialises — not declaredBlank`() {
        profiles.store["s1"] =
            SubjectProfile(
                subjectId = "s1",
                contact = listOf(ContactField(ContactKind.PORTFOLIO, "asha.dev", shareable = true)),
            )
        materialiser().materialise("s1")

        val contact = claims.store.values.single()
        assertTrue(contact.sensitive)
        assertEquals(DeclaredType.contactType(ContactKind.PORTFOLIO), contact.declaredType)
    }

    @Test
    fun `contact materialisation is idempotent across re-seals — one review, no churn`() {
        profiles.store["s1"] =
            SubjectProfile(
                subjectId = "s1",
                contact =
                    listOf(ContactField(ContactKind.EMAIL, "asha@example.com", shareable = true)),
            )
        materialiser().materialise("s1")
        val idsAfterFirst = claims.store.keys.toSet()

        val second = materialiser().materialise("s1")

        assertEquals(idsAfterFirst, claims.store.keys.toSet())
        assertTrue(second.created.isEmpty())
        assertEquals(1, reviews.store.size)
        assertTrue(reviews.deletes.isEmpty())
    }

    @Test
    fun `an INCLUDE review that fails after the claim save is healed on the next seal, not stranded`() {
        // The claim save and its companion review save are two separate, non-transactional writes
        // under one seal (IntakeService.sealManifest:564). This flaky store lets the claim land and
        // then trips the review save, reproducing the half-applied state a retry seal inherits.
        val flaky = MatFlakyReviewRepo()
        val mat =
            ProfileClaimMaterialiser(
                liveConfig(AppProperties(stage4 = AppProperties.Stage4(profileEnabled = true))),
                profiles,
                claims,
                flaky,
            )
        profiles.store["s1"] =
            SubjectProfile(
                subjectId = "s1",
                contact =
                    listOf(ContactField(ContactKind.EMAIL, "asha@example.com", shareable = true)),
            )

        // Seal 1: the sensitive claim persists, then the INCLUDE review save throws (5xx) and the
        // seal aborts before manifests.save — so the operator is told to seal again.
        flaky.failNextSave = true
        assertFailsWith<RuntimeException> { mat.materialise("s1") }
        val contact =
            claims.store.values.single {
                it.declaredType == DeclaredType.contactType(ContactKind.EMAIL)
            }
        assertTrue(contact.sensitive)
        // Half-applied: the sensitive claim exists with no opt-in review behind it.
        assertNull(flaky.store[contact.id])

        // Seal 2: the claim is now in `existing`, so it is classified `kept`, not `created`. The
        // opt-in must still be reconciled in off the desired sensitive set — otherwise
        // approvedForDownstream:219 drops the subject's shared contact forever. Before the fix the
        // created-branch-only write never re-ran, so this final assertion failed.
        val outcome = mat.materialise("s1")
        assertTrue(outcome.created.isEmpty())
        assertTrue(contact.id in outcome.kept)
        assertEquals(PiiChoice.INCLUDE, flaky.store[contact.id]?.piiChoice)
        assertEquals("s1", flaky.store[contact.id]?.subjectId)
    }
}

package ai.vishwakarma.labelling.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SubjectProfileDefaults.resolve] and the A/B hash contract (SubjectProfile LLD §2.1, §6.4). The
 * locale label is prompt text *and* a hash ingredient, so its exact rendering is pinned here.
 */
class SubjectProfileTest {

    private val worked =
        SubjectProfile(
            subjectId = "s1",
            country = "IN",
            marketRegion = "IN",
            currency = "INR",
            primaryLanguage = "en-IN",
        )

    @Test
    fun `an absent profile resolves blank, hashes to null and renders no locale`() {
        val resolved = SubjectProfileDefaults.resolve(null)

        assertTrue(resolved.blank)
        assertEquals("", resolved.locale)
        assertNull(resolved.hashOrNull())
    }

    @Test
    fun `a stored doc with every field empty is the same state as no doc at all`() {
        val resolved = SubjectProfileDefaults.resolve(SubjectProfile(subjectId = "s1"))

        assertTrue(resolved.blank)
        assertNull(resolved.hashOrNull())
    }

    @Test
    fun `the LLD worked example renders its documented locale label`() {
        val resolved = SubjectProfileDefaults.resolve(worked)

        assertEquals("the India market (INR), primary language en-IN", resolved.locale)
        assertNotNull(resolved.hashOrNull())
    }

    @Test
    fun `a free-label market region is used verbatim and the timezone appends when set`() {
        val resolved =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    country = "US",
                    marketRegion = "US-West",
                    timezone = "America/Los_Angeles",
                )
            )

        assertEquals("the US-West market, timezone America/Los_Angeles", resolved.locale)
    }

    @Test
    fun `market region wins over country and blank A with only a date still resolves`() {
        val onlyDate =
            SubjectProfileDefaults.resolve(
                SubjectProfile(subjectId = "s1", knowledgeAsOf = LocalDate.parse("2026-06-01"))
            )

        // B alone is a real profile (it changes the prompt), so it must hash — but there is no
        // locale clause to render.
        assertEquals("", onlyDate.locale)
        assertNotNull(onlyDate.hashOrNull())
    }

    @Test
    fun `the hash is stable across resolutions and moves when any A-or-B scalar changes`() {
        val base = SubjectProfileDefaults.resolve(worked).hashOrNull()

        assertEquals(base, SubjectProfileDefaults.resolve(worked).hashOrNull())
        assertNotEquals(base, SubjectProfileDefaults.resolve(worked.copy(currency = "USD")).hash())
        assertNotEquals(
            base,
            SubjectProfileDefaults.resolve(
                    worked.copy(knowledgeAsOf = LocalDate.parse("2026-01-01"))
                )
                .hash(),
        )
        // Whitespace is not a change — resolve trims before hashing.
        assertEquals(base, SubjectProfileDefaults.resolve(worked.copy(currency = " INR ")).hash())
    }

    private fun ResolvedSubjectProfile.hash(): String = checkNotNull(hashOrNull())

    // ---- C/D declared evidence (§2.1, §3.4) --------------------------------------------------

    @Test
    fun `C-or-D content does not change the A-or-B hash and does not un-blank A-or-B`() {
        // The class-doc contract: C/D drift rides scoreRunId, never profileHash, so folding it into
        // the hash would archive every existing A/B subject's notebooks.
        val withCd =
            worked.copy(
                aspirations = listOf("Optimising for staff-level IC work, not management."),
                targetSeniority = "staff",
            )
        assertEquals(
            SubjectProfileDefaults.resolve(worked).hash(),
            SubjectProfileDefaults.resolve(withCd).hash(),
        )
        // A profile that is A/B-blank but declares C/D still reads blank on the A/B axis (so the
        // publish-date rung stays silent, §12.2) while declaredBlank is false.
        val onlyCd =
            SubjectProfileDefaults.resolve(
                SubjectProfile(subjectId = "s1", aspirations = listOf("Staff IC."))
            )
        assertTrue(onlyCd.blank)
        assertNull(onlyCd.hashOrNull())
        assertTrue(!onlyCd.declaredBlank)
    }

    @Test
    fun `resolve trims, drops blanks and de-duplicates declared lists case-insensitively`() {
        val resolved =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    aspirations = listOf("  Staff IC  ", "", "staff ic", "Principal track"),
                    targetRoles = listOf("Staff Engineer", "Staff Engineer"),
                )
            )
        assertEquals(listOf("Staff IC", "Principal track"), resolved.aspirations)
        assertEquals(listOf("Staff Engineer"), resolved.targetRoles)
    }

    @Test
    fun `only recognised do-not-discuss keys survive resolution, in vocabulary order`() {
        val resolved =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    // out of order, with an unknown key that must be dropped (a retired-vocab
                    // guard)
                    doNotDiscussChecks = listOf("politics", "not-a-real-topic", "health"),
                )
            )
        // health precedes politics in the vocabulary; the junk key is gone.
        assertEquals(listOf("health", "politics"), resolved.doNotDiscussChecks)
    }

    @Test
    fun `a custom do-not-discuss entry materialises only once APPROVED`() {
        fun resolvedWith(state: DoNotDiscussApproval) =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    doNotDiscussCustom = DoNotDiscussCustom("my startup's cap table", state),
                )
            )

        assertNull(resolvedWith(DoNotDiscussApproval.PENDING).approvedDoNotDiscussCustom)
        assertNull(resolvedWith(DoNotDiscussApproval.REJECTED).approvedDoNotDiscussCustom)
        assertEquals(
            "my startup's cap table",
            resolvedWith(DoNotDiscussApproval.APPROVED).approvedDoNotDiscussCustom,
        )
    }

    // ---- E contact / PII (§2.1, §5) ----------------------------------------------------------

    @Test
    fun `resolve surfaces contact fields trimmed, keeping both shared and private`() {
        val resolved =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    contact =
                        listOf(
                            ContactField(
                                ContactKind.EMAIL,
                                "  asha@example.com ",
                                shareable = true
                            ),
                            ContactField(ContactKind.PHONE, "+91 555 0100"),
                            // A blank-valued field is dropped, never surfaced.
                            ContactField(ContactKind.LINKEDIN, "   "),
                        )
                )
            )
        assertEquals(2, resolved.contact.size)
        assertEquals("asha@example.com", resolved.contact[0].value)
        assertTrue(resolved.contact[0].shareable)
        assertEquals(ContactKind.PHONE, resolved.contact[1].kind)
        assertFalse(resolved.contact[1].shareable)
    }

    @Test
    fun `contact is out of the A-or-B hash and a shared contact drives declaredBlank false`() {
        // Contact reaches Stage 4 as a claim, never a prompt — so, like C/D, it must not churn the
        // A/B profileHash (§12.8.2 / §5).
        val withContact =
            worked.copy(
                contact =
                    listOf(ContactField(ContactKind.EMAIL, "asha@example.com", shareable = true))
            )
        assertEquals(
            SubjectProfileDefaults.resolve(worked).hash(),
            SubjectProfileDefaults.resolve(withContact).hash(),
        )
        // A shareable contact materialises, so it is NOT declaredBlank; a private-only one writes
        // no
        // claim, so it stays declaredBlank (§5.2).
        val shared =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    contact =
                        listOf(
                            ContactField(ContactKind.EMAIL, "asha@example.com", shareable = true)
                        ),
                )
            )
        val privateOnly =
            SubjectProfileDefaults.resolve(
                SubjectProfile(
                    subjectId = "s1",
                    contact = listOf(ContactField(ContactKind.EMAIL, "asha@example.com")),
                )
            )
        assertFalse(shared.declaredBlank)
        assertTrue(privateOnly.declaredBlank)
    }
}

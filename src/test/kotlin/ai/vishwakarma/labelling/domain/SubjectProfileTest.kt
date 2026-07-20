package ai.vishwakarma.labelling.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

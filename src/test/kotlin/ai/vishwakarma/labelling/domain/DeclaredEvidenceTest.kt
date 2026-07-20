package ai.vishwakarma.labelling.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The declared-evidence value objects (SubjectProfile LLD §3.2, §3.4, OD-9): the tolerant `origin`
 * read, the code-governed vocabularies and the evidenceGate→declaredType derivation the loader
 * uses.
 */
class DeclaredEvidenceTest {

    private fun claim(origin: ClaimOrigin? = null) =
        Claim(
            id = "c",
            subjectId = "s",
            assetId = "a",
            claimType = ClaimType.VALUE,
            text = "x",
            origin = origin
        )

    @Test
    fun `origin reads tolerant-null — absent is EXTRACTED, only SUBJECT_DECLARED is declared`() {
        assertFalse(claim(origin = null).declared)
        assertFalse(claim(origin = ClaimOrigin.EXTRACTED).declared)
        assertTrue(claim(origin = ClaimOrigin.SUBJECT_DECLARED).declared)
        assertEquals(ClaimOrigin.SUBJECT_DECLARED, ClaimOrigin.fromOrNull("subject_declared"))
        assertEquals(null, ClaimOrigin.fromOrNull("nonsense"))
    }

    @Test
    fun `a do-not-discuss custom entry is materialisable only when APPROVED and non-blank`() {
        assertFalse(DoNotDiscussCustom("topic", DoNotDiscussApproval.PENDING).materialisable)
        assertFalse(DoNotDiscussCustom("topic", DoNotDiscussApproval.REJECTED).materialisable)
        assertFalse(DoNotDiscussCustom("  ", DoNotDiscussApproval.APPROVED).materialisable)
        assertTrue(DoNotDiscussCustom("topic", DoNotDiscussApproval.APPROVED).materialisable)
    }

    @Test
    fun `the do-not-discuss vocabulary is a closed set with stable labels`() {
        assertTrue(DoNotDiscussVocabulary.contains("health"))
        assertFalse(DoNotDiscussVocabulary.contains("favourite-colour"))
        assertEquals("Health and medical history", DoNotDiscussVocabulary.labelOf("health"))
        // known() keeps vocabulary order and silently drops the unknown key.
        assertEquals(
            listOf("health", "politics"),
            DoNotDiscussVocabulary.known(listOf("politics", "nope", "health")),
        )
    }

    @Test
    fun `fromEvidenceGate lifts only recognised declared types out of the braced alternation`() {
        val gate =
            "requires a ledger item of type {stated-aspiration | stated-track-preference | " +
                "self-disclosed-driver} that the presented role points away from"
        assertEquals(
            listOf(
                DeclaredType.STATED_ASPIRATION,
                DeclaredType.STATED_TRACK_PREFERENCE,
                DeclaredType.SELF_DISCLOSED_DRIVER,
            ),
            DeclaredType.fromEvidenceGate(gate),
        )
    }

    @Test
    fun `an extracted-evidence gate names no declared types — the narrowing guard`() {
        // {scope-record} is an extracted descriptor, not a declaration: it must yield nothing so it
        // cannot silently gate a template on a declaredType nothing ever stamps.
        assertTrue(
            DeclaredType.fromEvidenceGate("requires a ledger item of type {scope-record}").isEmpty()
        )
        assertTrue(DeclaredType.fromEvidenceGate("").isEmpty())
    }

    @Test
    fun `recognises accepts the full gate vocabulary but not adjacent extracted descriptors`() {
        assertTrue(DeclaredType.recognises("subject-declared-engagement-model"))
        assertTrue(DeclaredType.recognises("  Stated-Aspiration  "))
        assertFalse(DeclaredType.recognises("scope-record"))
        // materialised is a strict subset of all — the three no-source types are gate-legal but
        // never stamped by slice 2.
        assertTrue(DeclaredType.STATED_ASPIRATION in DeclaredType.materialised)
        assertFalse(DeclaredType.SELF_DISCLOSED_DRIVER in DeclaredType.materialised)
        assertTrue(DeclaredType.materialised.all { it in DeclaredType.all })
    }
}

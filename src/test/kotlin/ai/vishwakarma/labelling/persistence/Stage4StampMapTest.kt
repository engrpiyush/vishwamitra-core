package ai.vishwakarma.labelling.persistence

import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4Stamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The §6 traceability-stamp mappers: lossless round-trip, tolerant of legacy docs (VA-49). */
class Stage4StampMapTest {

    @Test
    fun `stamp round-trips through its Firestore map`() {
        val stamp =
            Stage4Stamp(
                subjectId = "subj-1",
                sourceClaimIds = listOf("c1", "c2"),
                scoreRunId = "run-9",
                category = Stage4Category.SITUATIONAL,
                planId = "abc123",
                personaHash = "deadbeef",
                generatorPromptHash = "cafe01",
            )

        assertEquals(stamp, stamp.toStampMap().toStage4Stamp())
    }

    @Test
    fun `a map without subjectId reads as no stamp — the legacy example path`() {
        assertNull(emptyMap<String, Any?>().toStage4Stamp())
        assertNull(mapOf<String, Any?>("subjectId" to "").toStage4Stamp())
    }

    @Test
    fun `unknown category and missing optionals are tolerated`() {
        val stamp =
            mapOf<String, Any?>("subjectId" to "s1", "category" to "NOT_A_CATEGORY").toStage4Stamp()

        assertEquals("s1", stamp?.subjectId)
        assertNull(stamp?.category)
        assertEquals(emptyList(), stamp?.sourceClaimIds)
        assertNull(stamp?.planId)
    }
}

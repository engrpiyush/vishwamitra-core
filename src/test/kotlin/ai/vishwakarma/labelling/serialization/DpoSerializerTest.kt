package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.DpoPair
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DpoSerializerTest {

    private val serializer = DpoSerializer(ToolCallMapper())
    private val validator = DpoValidator()

    private fun pair(chosen: String, rejected: String) = DpoPair(
        id = "d1",
        promptTurns = listOf(Turn(TurnRole.USER, TurnKind.TEXT, text = "Painting ke liye banda chahiye, kitna charge?")),
        chosenText = chosen,
        rejectedText = rejected,
    )

    @Test
    fun `serializes prompt contents plus chosen and rejected`() {
        val out = serializer.toPreference(pair("Area bata do, quote nikalta hoon.", "I cannot help with that."))

        @Suppress("UNCHECKED_CAST")
        val contents = out["contents"] as List<Map<String, Any?>>
        assertEquals("user", contents[0]["role"])

        @Suppress("UNCHECKED_CAST")
        val chosen = out["chosen"] as Map<String, Any?>
        assertEquals("model", chosen["role"])

        val jsonl = serializer.toJsonl(pair("a", "b"))
        assertTrue(jsonl.contains("\"chosen\"") && jsonl.contains("\"rejected\""))
        assertTrue(!jsonl.contains("\n"))
    }

    @Test
    fun `validator flags identical and empty candidates`() {
        assertTrue(validator.validate(pair("same", "same")).any { it.contains("identical") })
        assertTrue(validator.validate(pair("ok", "")).any { it.contains("Rejected response is empty") })
    }

    @Test
    fun `validator passes a good pair`() {
        assertEquals(emptyList(), validator.validate(pair("good answer", "bad answer")))
    }
}

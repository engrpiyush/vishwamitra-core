package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentsPartsSerializerTest {

    private val serializer = ContentsPartsSerializer(ToolCallMapper())
    private val validator = SftValidator()

    @Test
    fun `serializes a tool-calling conversation to contents-parts`() {
        val example =
            SftExample(
                id = "x1",
                turns =
                    listOf(
                        Turn(
                            TurnRole.USER,
                            TurnKind.TEXT,
                            text = "Show me electricians near Sakinaka"
                        ),
                        Turn(
                            TurnRole.MODEL,
                            TurnKind.TOOL_CALL,
                            toolName = "search_workers",
                            argsJson = """{"skill":"electrician","location":"Sakinaka"}"""
                        ),
                        Turn(
                            TurnRole.USER,
                            TurnKind.TOOL_RESPONSE,
                            toolName = "search_workers",
                            resultJson = """{"results":[{"id":"w12","name":"Ramesh"}]}"""
                        ),
                        Turn(TurnRole.MODEL, TurnKind.TEXT, text = "Ramesh is available. Book?"),
                    ),
            )

        @Suppress("UNCHECKED_CAST")
        val contents = serializer.toContents(example)["contents"] as List<Map<String, Any?>>

        assertEquals(4, contents.size)
        assertEquals("user", contents[0]["role"])
        assertEquals("model", contents[1]["role"])

        // tool call part
        @Suppress("UNCHECKED_CAST")
        val callPart = (contents[1]["parts"] as List<Map<String, Any?>>)[0]
        @Suppress("UNCHECKED_CAST") val functionCall = callPart["functionCall"] as Map<String, Any?>
        assertEquals("search_workers", functionCall["name"])
        @Suppress("UNCHECKED_CAST") val args = functionCall["args"] as Map<String, Any?>
        assertEquals("electrician", args["skill"])

        // tool response is serialized on the user side as functionResponse
        assertEquals("user", contents[2]["role"])
        @Suppress("UNCHECKED_CAST")
        val respPart = (contents[2]["parts"] as List<Map<String, Any?>>)[0]
        assertTrue(respPart.containsKey("functionResponse"))

        // round-trips through JSON without throwing and is one line
        val jsonl = serializer.toJsonl(example)
        assertTrue(jsonl.startsWith("{\"contents\""))
        assertTrue(!jsonl.contains("\n"))
    }

    @Test
    fun `validator flags a tool call not followed by a response`() {
        val bad =
            SftExample(
                id = "x2",
                turns =
                    listOf(
                        Turn(TurnRole.USER, TurnKind.TEXT, text = "hi"),
                        Turn(
                            TurnRole.MODEL,
                            TurnKind.TOOL_CALL,
                            toolName = "list_skills",
                            argsJson = "{}"
                        ),
                    ),
            )
        val errors = validator.validate(bad)
        assertTrue(errors.any { it.contains("followed by a tool response") })
        assertTrue(errors.any { it.contains("Last turn must be a model text turn") })
    }

    @Test
    fun `validator passes a clean text conversation`() {
        val ok =
            SftExample(
                id = "x3",
                turns =
                    listOf(
                        Turn(TurnRole.USER, TurnKind.TEXT, text = "Need a plumber tomorrow"),
                        Turn(TurnRole.MODEL, TurnKind.TEXT, text = "Sure, which area and time?"),
                    ),
            )
        assertEquals(emptyList(), validator.validate(ok))
    }
}

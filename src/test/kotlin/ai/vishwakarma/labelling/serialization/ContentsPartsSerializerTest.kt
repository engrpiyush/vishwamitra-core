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

        // OSS tuning only allows text parts: the tool call is a <tool_call> text part (default
        // QWEN_HERMES encoding), NOT a structured functionCall part.
        @Suppress("UNCHECKED_CAST")
        val callPart = (contents[1]["parts"] as List<Map<String, Any?>>)[0]
        assertTrue(!callPart.containsKey("functionCall"))
        val callText = callPart["text"] as String
        assertTrue(callText.startsWith("<tool_call>") && callText.endsWith("</tool_call>"))
        assertTrue(callText.contains("\"name\":\"search_workers\""))
        assertTrue(callText.contains("\"skill\":\"electrician\""))

        // tool response is a <tool_response> text part on the user side
        assertEquals("user", contents[2]["role"])
        @Suppress("UNCHECKED_CAST")
        val respPart = (contents[2]["parts"] as List<Map<String, Any?>>)[0]
        assertTrue(!respPart.containsKey("functionResponse"))
        assertTrue((respPart["text"] as String).contains("<tool_response>"))

        // round-trips through JSON without throwing and is one line
        val jsonl = serializer.toJsonl(example)
        assertTrue(jsonl.startsWith("{\"contents\""))
        assertTrue(!jsonl.contains("\n"))
    }

    @Test
    fun `tool encoding is selectable and malformed args do not throw`() {
        val example =
            SftExample(
                id = "x4",
                turns =
                    listOf(
                        Turn(TurnRole.USER, TurnKind.TEXT, text = "book it"),
                        Turn(
                            TurnRole.MODEL,
                            TurnKind.TOOL_CALL,
                            toolName = "book_worker",
                            // not valid JSON — serializer must fall back, never throw
                            argsJson = "create_work_request(...)",
                        ),
                        Turn(
                            TurnRole.USER,
                            TurnKind.TOOL_RESPONSE,
                            toolName = "book_worker",
                            resultJson = """{"ok":true}""",
                        ),
                        Turn(TurnRole.MODEL, TurnKind.TEXT, text = "Booked."),
                    ),
            )

        // Inspect the tool-call part text directly (the serialized JSONL escapes inner quotes).
        fun callText(encoding: ai.vishwakarma.labelling.domain.ToolEncoding): String {
            @Suppress("UNCHECKED_CAST")
            val contents =
                serializer.toContents(example, encoding)["contents"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            return (contents[1]["parts"] as List<Map<String, Any?>>)[0]["text"] as String
        }

        // toJsonl must never throw on the malformed args, and stays one line
        assertTrue(
            !serializer
                .toJsonl(example, ai.vishwakarma.labelling.domain.ToolEncoding.PLAIN_JSON)
                .contains("\n")
        )

        // PLAIN_JSON: bare {name,args} with empty-args fallback, no <tool_call> wrapper
        val plain = callText(ai.vishwakarma.labelling.domain.ToolEncoding.PLAIN_JSON)
        assertTrue(!plain.contains("<tool_call>"))
        assertTrue(plain.contains("\"name\":\"book_worker\""))
        assertTrue(plain.contains("\"args\":{}"))

        // GEMMA_FENCED uses fenced blocks
        assertTrue(
            callText(ai.vishwakarma.labelling.domain.ToolEncoding.GEMMA_FENCED)
                .contains("```tool_call")
        )
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

package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import kotlin.test.Test
import kotlin.test.assertEquals

/** §9.6 messages-shape pins: leading system message, role mapping, tool-turn text framing. */
class OpenAiChatSerializerTest {

    private val serializer = OpenAiChatSerializer(ToolCallMapper())

    private fun example(system: String? = null, turns: List<Turn>) =
        SftExample(id = "e1", systemInstruction = system, turns = turns)

    @Test
    fun `system instruction leads, roles map to user-assistant`() {
        val line =
            serializer.toJsonl(
                example(
                    system = "SYSTEM PROMPT",
                    turns =
                        listOf(
                            Turn(TurnRole.USER, text = "hi"),
                            Turn(TurnRole.MODEL, text = "hello there"),
                        ),
                )
            )
        assertEquals(
            """{"messages":[{"role":"system","content":"SYSTEM PROMPT"},""" +
                """{"role":"user","content":"hi"},""" +
                """{"role":"assistant","content":"hello there"}]}""",
            line,
        )
    }

    @Test
    fun `no system instruction means no system message`() {
        val line =
            serializer.toJsonl(
                example(
                    system = null,
                    turns =
                        listOf(
                            Turn(TurnRole.USER, text = "q"),
                            Turn(TurnRole.MODEL, text = "a"),
                        ),
                )
            )
        assertEquals(
            """{"messages":[{"role":"user","content":"q"},{"role":"assistant","content":"a"}]}""",
            line,
        )
    }

    @Test
    fun `tool turns ride as framed text content, never structured keys`() {
        @Suppress("UNCHECKED_CAST")
        val messages =
            serializer
                .toMessages(
                    example(
                        system = null,
                        turns =
                            listOf(
                                Turn(TurnRole.USER, text = "q"),
                                Turn(
                                    TurnRole.MODEL,
                                    kind = TurnKind.TOOL_CALL,
                                    toolName = "lookup",
                                    argsJson = """{"k":"v"}""",
                                ),
                                Turn(
                                    TurnRole.USER,
                                    kind = TurnKind.TOOL_RESPONSE,
                                    toolName = "lookup",
                                    resultJson = """{"r":1}""",
                                ),
                                Turn(TurnRole.MODEL, text = "a"),
                            ),
                    )
                )["messages"]
                as List<Map<String, Any?>>
        assertEquals(listOf("user", "assistant", "user", "assistant"), messages.map { it["role"] })
        val call = messages[1]["content"] as String
        assertEquals(true, call.startsWith("<tool_call>"))
        assertEquals(2, messages[1].size)
    }
}

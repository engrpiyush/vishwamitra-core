package ai.vishwakarma.labelling.drafting

import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftPromptsTest {

    @Test
    fun `parses a fenced JSON turn array including a tool call`() {
        val raw =
            """
            ```json
            [
              {"role":"user","kind":"TEXT","text":"What has she built?"},
              {"role":"model","kind":"TOOL_CALL","toolName":"lookup_fact","args":{"topic":"projects"}},
              {"role":"user","kind":"TOOL_RESPONSE","toolName":"lookup_fact","result":{"n":1}},
              {"role":"model","kind":"TEXT","text":"She led the payments migration."}
            ]
            ```
        """
                .trimIndent()

        val turns = DraftPrompts.parseTurns(raw)
        assertEquals(4, turns.size)
        assertEquals(TurnRole.USER, turns[0].role)
        assertEquals(TurnKind.TOOL_CALL, turns[1].kind)
        assertEquals("lookup_fact", turns[1].toolName)
        assertTrue(turns[1].argsJson!!.contains("projects"))
        assertEquals(TurnKind.TOOL_RESPONSE, turns[2].kind)
    }

    @Test
    fun `parses a single next-turn object`() {
        val turn =
            DraftPrompts.parseTurn("""{"role":"model","kind":"TEXT","text":"Sure, which area?"}""")
        assertEquals(TurnRole.MODEL, turn.role)
        assertEquals("Sure, which area?", turn.text)
    }

    @Test
    fun `parses two candidates`() {
        val c = DraftPrompts.parseCandidates("""{"a":"helpful reply","b":"unhelpful reply"}""")
        assertEquals("helpful reply", c.a)
        assertEquals("unhelpful reply", c.b)
    }
}

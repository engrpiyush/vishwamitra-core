package ai.vishwakarma.labelling.drafting

import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.domain.Tool
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.serialization.Json

/**
 * Builds drafting prompts and parses the model's JSON back into domain turns. Shared by all
 * providers so the schema lives in one place.
 */
object DraftPrompts {

    private const val PERSONA =
        "You are the assistant for Vishwakarma.ai, an Indian marketplace for temporary/contractual " +
            "workers (plumbers, electricians, painters, movers, etc.). Be concise and helpful. " +
            "Reply in the user's language, including natural Hinglish when appropriate."

    fun toolsDescription(tools: List<Tool>): String {
        if (tools.isEmpty()) return "No tools available."
        return tools.joinToString("\n") { t ->
            val params = t.params.joinToString(", ") { "${it.name}:${it.type}${if (it.required) "" else "?"}" }
            "- ${t.name}($params): ${t.description}"
        }
    }

    private fun tagsLine(tags: ExampleTags): String =
        "Tags — skill: ${tags.skill ?: "any"}, intent: ${tags.intent ?: "any"}, language: ${tags.language ?: "Hinglish"}."

    private val TURN_SCHEMA = """
        Output ONLY a JSON array of turns, no prose, no code fences. Each turn:
          {"role":"user|model","kind":"TEXT|TOOL_CALL|TOOL_RESPONSE","text":"...","toolName":"...","args":{...},"result":{...}}
        Rules: start with a user TEXT turn and end with a model TEXT turn. A TOOL_CALL is a model turn
        with toolName+args; it must be immediately followed by a TOOL_RESPONSE (role user) with
        toolName+result; then a model turn. Use only the listed tools.
    """.trimIndent()

    fun conversationPrompt(scenario: Scenario?, tags: ExampleTags, tools: List<Tool>): String = buildString {
        appendLine(PERSONA)
        appendLine()
        appendLine("Generate one realistic multi-turn conversation for fine-tuning data.")
        appendLine(tagsLine(tags))
        scenario?.let {
            appendLine("Scenario: ${it.title} — ${it.description}")
            if (it.promptTemplate.isNotBlank()) appendLine("Guidance: ${it.promptTemplate}")
        }
        appendLine()
        appendLine("Available tools:")
        appendLine(toolsDescription(tools))
        appendLine()
        append(TURN_SCHEMA)
    }

    fun nextTurnPrompt(turns: List<Turn>, tools: List<Tool>): String = buildString {
        appendLine(PERSONA)
        appendLine()
        appendLine("Here is the conversation so far (JSON turns):")
        appendLine(Json.writeLine(turns.map { it.toMap() }))
        appendLine()
        appendLine("Available tools:")
        appendLine(toolsDescription(tools))
        appendLine()
        appendLine("Draft the SINGLE next model turn. Output ONLY one JSON turn object (not an array):")
        append("""{"role":"model","kind":"TEXT","text":"..."}  (or a TOOL_CALL with toolName+args)""")
    }

    fun candidatesPrompt(promptTurns: List<Turn>, tools: List<Tool>): String = buildString {
        appendLine(PERSONA)
        appendLine()
        appendLine("Given this prompt (JSON turns):")
        appendLine(Json.writeLine(promptTurns.map { it.toMap() }))
        appendLine()
        appendLine("Available tools:")
        appendLine(toolsDescription(tools))
        appendLine()
        appendLine("Produce TWO distinct candidate model responses: \"a\" should be clearly better,")
        appendLine("\"b\" weaker/less helpful. Output ONLY JSON: {\"a\":\"...\",\"b\":\"...\"}")
    }

    // ---- parsing ----------------------------------------------------------
    fun parseTurns(raw: String): List<Turn> {
        val json = stripFences(raw)
        val parsed = Json.parse(json) as? List<*> ?: error("Expected a JSON array of turns")
        return parsed.mapNotNull { it as? Map<*, *> }.map { it.toTurn() }
    }

    fun parseTurn(raw: String): Turn {
        val json = stripFences(raw)
        val parsed = Json.parse(json) as? Map<*, *> ?: error("Expected a JSON turn object")
        return parsed.toTurn()
    }

    fun parseCandidates(raw: String): CandidatePair {
        val json = stripFences(raw)
        val parsed = Json.parse(json) as? Map<*, *> ?: error("Expected a JSON object {a,b}")
        return CandidatePair(
            a = parsed["a"]?.toString().orEmpty(),
            b = parsed["b"]?.toString().orEmpty(),
        )
    }

    private fun Map<*, *>.toTurn(): Turn {
        val kind = runCatching { TurnKind.valueOf((this["kind"] as? String ?: "TEXT").uppercase()) }.getOrDefault(TurnKind.TEXT)
        val role = runCatching { TurnRole.valueOf((this["role"] as? String ?: "USER").uppercase()) }.getOrDefault(TurnRole.USER)
        return Turn(
            role = role,
            kind = kind,
            text = this["text"] as? String ?: "",
            toolName = this["toolName"] as? String,
            argsJson = this["args"]?.let { Json.writeLine(it) },
            resultJson = this["result"]?.let { Json.writeLine(it) },
        )
    }

    private fun Turn.toMap(): Map<String, Any?> = buildMap {
        put("role", role.name)
        put("kind", kind.name)
        if (text.isNotBlank()) put("text", text)
        toolName?.let { put("toolName", it) }
        argsJson?.let { put("args", Json.parse(it)) }
        resultJson?.let { put("result", Json.parse(it)) }
    }

    /** Strip ```json fences and grab the outermost JSON value. */
    private fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            s = s.removeSuffix("```").trim()
        }
        return s
    }
}

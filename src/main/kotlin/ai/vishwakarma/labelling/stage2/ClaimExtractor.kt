package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.ResolvedExtractionPrompt
import java.time.Instant
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * Shreds a diarized [Transcript] into atomic [Claim]s via Vertex AI Gemini (structured JSON output,
 * tolerant parse — the [ai.vishwakarma.labelling.drafting.DraftPrompts] idiom). Stays inside GCP:
 * [GeminiDrafting] calls the regional Vertex endpoint with the app SA's ADC token — no external API
 * or key. The content-type instruction block is resolved per run from the admin-managed
 * `extraction_prompts` rows (built-in fallback) and its version/hash stamped on every claim. Claims
 * come back with a blank id (the caller assigns via the repository) and their authenticity tier
 * seeded from the source asset's prior; Stage 3 refines it later.
 */
@Component
class ClaimExtractor(
    private val gemini: GeminiDrafting,
    private val prompts: ExtractionPromptService,
) {

    fun extract(
        subjectId: String,
        asset: Asset,
        transcript: Transcript,
        subjectName: String? = null,
    ): List<Claim> {
        check(gemini.available()) {
            "Gemini extraction unavailable — enable the gemini provider with a Vertex model id"
        }
        val resolved = prompts.resolve(asset.contentType)
        val lines = transcript.segments.map { it.render() }
        val now = Instant.now()
        return chunks(lines).flatMap { chunk ->
            val raw =
                gemini.generate(
                    prompt(asset, chunk, subjectName, resolved),
                    maxTokens = MAX_TOKENS,
                    thinkingBudget = THINKING_BUDGET,
                )
            parseClaims(raw).mapNotNull { it.toClaim(subjectId, asset, now, resolved) }
        }
    }

    private fun TranscriptSegment.render(): String {
        val who = speaker ?: "Unknown speaker"
        val range = if (start != null && end != null) " ($start–${end}s)" else ""
        return "[$who]$range $text"
    }

    /** Split rendered lines into chunks under [CHUNK_CHARS], never splitting a segment. */
    private fun chunks(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.isNotEmpty() && sb.length + line.length + 1 > CHUNK_CHARS) {
                out += sb.toString()
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun prompt(
        asset: Asset,
        chunk: String,
        subjectName: String?,
        resolved: ResolvedExtractionPrompt,
    ): String = buildString {
        appendLine(
            "You are extracting atomic claims about a person (the \"subject\") from a diarized " +
                "transcript, building an evidence ledger."
        )
        appendLine()
        appendLine("Source asset context:")
        subjectName
            ?.takeIf { it.isNotBlank() }
            ?.let {
                appendLine(
                    "- The subject's name: $it (speech recognition may have misspelled it in the " +
                        "transcript — always write claims using this exact name)"
                )
            }
        appendLine("- Title: ${asset.title}")
        appendLine("- Content type: ${asset.contentType.name}")
        appendLine(
            "- Source's relationship to the subject: ${asset.relationship.name} " +
                "(SELF means the subject speaks about themselves; anything else is a third party " +
                "speaking about the subject)"
        )
        appendLine()
        appendLine(
            "Extract every atomic, factual claim about the subject. One claim = one standalone " +
                "verifiable statement. Ignore small talk, questions, and statements not about the " +
                "subject."
        )
        if (resolved.instructions.isNotBlank()) {
            appendLine(resolved.instructions)
        }
        appendLine()
        appendLine("Claim types:")
        appendLine("- IDENTITY: who the subject is (roles, background, affiliations)")
        appendLine("- EPISODE: a specific event or achievement, with time/place context")
        appendLine("- VALUE: a principle or motivation the subject holds")
        appendLine("- WEAKNESS: a growth area or limitation")
        appendLine("- SKILL: a capability or expertise")
        appendLine()
        appendLine("Output ONLY a JSON array, no prose, no code fences. Each element:")
        appendLine(
            """  {"text":"...","claimType":"IDENTITY|EPISODE|VALUE|WEAKNESS|SKILL","speaker":"Speaker 2","mediaStart":6.5,"mediaEnd":15.0,"sourceExcerpt":"...","claimedDate":"2021-06-01","confidence":0.9}"""
        )
        appendLine(
            "Rules: \"text\" is a standalone third-person statement about the subject. " +
                "\"claimedDate\" is ISO yyyy-MM-dd only when the event's date is stated or clearly " +
                "inferable, else null. \"confidence\" is your extraction confidence (0..1). Copy " +
                "\"speaker\" and the media times from the transcript line(s) the claim came from."
        )
        appendLine()
        appendLine("Transcript:")
        append(chunk)
    }

    private fun parseClaims(raw: String): List<Map<*, *>> {
        val json = stripFences(raw)
        val parsed =
            runCatching { Json.parse(json) as? List<*> }.getOrNull()
                ?: salvageTruncated(json)
                ?: error(
                    "Expected a JSON array of claims (head: ${raw.take(200)} … " +
                        "tail: ${raw.takeLast(120)})"
                )
        return parsed.mapNotNull { it as? Map<*, *> }
    }

    /**
     * A response cut off at the output-token cap dies mid-object ("Unexpected end-of-input",
     * observed live 2026-07-04). Rather than losing the whole chunk, drop the truncated tail: cut
     * at the last complete object and close the array.
     */
    private fun salvageTruncated(json: String): List<*>? {
        val cut = json.lastIndexOf('}')
        if (cut < 0) return null
        return runCatching { Json.parse(json.substring(0, cut + 1) + "]") as? List<*> }.getOrNull()
    }

    private fun Map<*, *>.toClaim(
        subjectId: String,
        asset: Asset,
        now: Instant,
        resolved: ResolvedExtractionPrompt,
    ): Claim? {
        val text = (this["text"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val claimType = ClaimType.fromOrNull(this["claimType"] as? String) ?: return null
        return Claim(
            id = "",
            subjectId = subjectId,
            assetId = asset.id,
            claimType = claimType,
            text = text,
            speaker = this["speaker"] as? String,
            mediaStart = (this["mediaStart"] as? Number)?.toDouble(),
            mediaEnd = (this["mediaEnd"] as? Number)?.toDouble(),
            sourceExcerpt = this["sourceExcerpt"] as? String,
            claimedDate =
                (this["claimedDate"] as? String)?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                },
            authenticityTier = asset.authenticityPrior,
            authenticityScore = null,
            extractionConfidence = (this["confidence"] as? Number)?.toDouble(),
            extractionPromptId = asset.contentType.name,
            extractionPromptVersion = resolved.version,
            extractionPromptHash = resolved.hash,
            createdAt = now,
            stage2ProcessedAt = now,
        )
    }

    /** Strip ```json fences and grab the outermost JSON value (DraftPrompts idiom). */
    private fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            s = s.removeSuffix("```").trim()
        }
        return s
    }

    companion object {
        /** Per-prompt transcript budget (~15k tokens); split on segment boundaries. */
        const val CHUNK_CHARS = 60_000
        /**
         * Total output cap per chunk (the 2.5-family max), with thinking explicitly bounded to
         * [THINKING_BUDGET] out of it. Unbounded dynamic thinking ate a 32k budget before writing
         * one complete claim object (observed live 2026-07-04); the split guarantees ~57k tokens of
         * actual JSON space while still allowing deep reasoning for inference-mode extraction.
         */
        const val MAX_TOKENS = 65_535
        const val THINKING_BUDGET = 8_192
    }
}

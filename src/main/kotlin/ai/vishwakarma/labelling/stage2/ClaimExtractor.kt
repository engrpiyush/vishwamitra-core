package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.ResolvedExtractionPrompt
import java.time.Instant
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * Shreds a diarized [Transcript] — or, via [extractDocument], the printed text of an image/PDF —
 * into atomic [Claim]s via Vertex AI Gemini (structured JSON output, tolerant parse — the
 * [ai.vishwakarma.labelling.drafting.DraftPrompts] idiom). Stays inside GCP: [GeminiDrafting] calls
 * the regional Vertex endpoint with the app SA's ADC token — no external API or key. The
 * content-type instruction block is resolved per run from the admin-managed `extraction_prompts`
 * rows (built-in fallback) and its version/hash stamped on every claim. Claims come back with a
 * blank id (the caller assigns via the repository) and their authenticity tier seeded from the
 * source asset's prior; Stage 3 refines it later.
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

    /**
     * IMAGE/DOCUMENT lane sibling of [extract]: the stored bytes ride inline to Gemini, which OCRs
     * the printed text and extracts claims from it in one call — no transcript leg, no chunking.
     * Same base contract, §12.1 resolved block, and provenance stamping; `speaker` and media times
     * stay null, `sourceExcerpt` is the printed text the claim came from.
     */
    fun extractDocument(
        subjectId: String,
        asset: Asset,
        bytes: ByteArray,
        mimeType: String,
        subjectName: String? = null,
    ): List<Claim> {
        check(gemini.available()) {
            "Gemini extraction unavailable — enable the gemini provider with a Vertex model id"
        }
        val resolved = prompts.resolve(asset.contentType)
        val now = Instant.now()
        val raw =
            gemini.generateWithInline(
                documentPrompt(asset, subjectName, resolved),
                mimeType,
                bytes,
                maxTokens = MAX_TOKENS,
                thinkingBudget = THINKING_BUDGET,
            )
        return parseClaims(raw).mapNotNull { it.toClaim(subjectId, asset, now, resolved) }
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
            """  {"text":"...","claimType":"IDENTITY|EPISODE|VALUE|WEAKNESS|SKILL","speaker":"Speaker 2","mediaStart":6.5,"mediaEnd":15.0,"sourceExcerpt":"...","claimedDate":"2021-06-01","confidence":0.9,"basis":"STATED","sensitive":false}"""
        )
        appendLine(
            "Rules: \"text\" is a standalone third-person statement about the subject. " +
                "\"claimedDate\" is ISO yyyy-MM-dd only when the event's date is stated or clearly " +
                "inferable, else null. \"confidence\" is your extraction confidence (0..1). Copy " +
                "\"speaker\" and the media times from the transcript line(s) the claim came from."
        )
        appendLine(claimDiscipline())
        appendLine()
        appendLine("Transcript:")
        append(chunk)
    }

    private fun documentPrompt(
        asset: Asset,
        subjectName: String?,
        resolved: ResolvedExtractionPrompt,
    ): String = buildString {
        appendLine(
            "You are extracting atomic claims about a person (the \"subject\") from the attached " +
                "document (a scanned image or PDF), building an evidence ledger. First read ALL " +
                "printed text in the attachment, then extract claims from that printed text only."
        )
        appendLine()
        appendLine("Source asset context:")
        subjectName
            ?.takeIf { it.isNotBlank() }
            ?.let {
                appendLine(
                    "- The subject's name: $it (the document may render it differently — always " +
                        "write claims using this exact name)"
                )
            }
        appendLine("- Title: ${asset.title}")
        appendLine("- Content type: ${asset.contentType.name}")
        appendLine(
            "- Source's relationship to the subject: ${asset.relationship.name} " +
                "(SELF means the subject authored it about themselves; anything else is a third " +
                "party attesting about the subject)"
        )
        appendLine()
        appendLine(
            "Extract every atomic, factual claim about the subject. One claim = one standalone " +
                "verifiable statement. Copy issuer names, credential titles, and dates exactly as " +
                "printed; omit anything illegible rather than guessing — these claims anchor " +
                "downstream scoring, so precision beats recall."
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
            """  {"text":"...","claimType":"IDENTITY|EPISODE|VALUE|WEAKNESS|SKILL","sourceExcerpt":"...","claimedDate":"2021-06-01","confidence":0.9,"basis":"STATED","sensitive":false}"""
        )
        appendLine(
            "Rules: \"text\" is a standalone third-person statement about the subject. " +
                "\"sourceExcerpt\" is the verbatim printed text the claim came from. " +
                "\"claimedDate\" is ISO yyyy-MM-dd only when a date is printed or clearly " +
                "inferable from one, else null. \"confidence\" is your extraction confidence (0..1)."
        )
        appendLine(claimDiscipline())
    }

    /**
     * Cross-cutting per-claim rules shared by both extraction paths (2026-07-05):
     * - participation is not ability (a SKILL needs demonstrated/attested ability, not mere
     *   enrollment — a transcript once minted 23 "has studied X" SKILLs at the document's HIGH
     *   prior);
     * - `basis` marks STATED vs INFERRED so the distinction survives independently of the per-run
     *   `confidence` number (LLD §7.1.1);
     * - `sensitive` flags contact/identity PII, which is captured (legitimate for a personal
     *   advocate) and gated for opt-in review approval, not dropped at extraction (LLD §12.6).
     */
    private fun claimDiscipline(): String =
        "A SKILL claim requires the source to demonstrate or attest the ability itself. " +
            "Studying a subject, attending a course, or being exposed to a topic is " +
            "participation — record it as an EPISODE, never as a SKILL. " +
            "Set \"basis\" to INFERRED for claims you infer from demonstrated behaviour rather " +
            "than ones the source states outright (the reduced-confidence demonstration claims); " +
            "otherwise STATED. Set \"sensitive\" to true when the claim's content is contact or " +
            "identity data (email, phone, address, ID/registration/serial numbers), else false."

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
            sourceClass = asset.sourceClass,
            relationship = asset.relationship,
            authenticityScore = null,
            extractionConfidence = (this["confidence"] as? Number)?.toDouble(),
            claimBasis = ClaimBasis.fromOrNull(this["basis"] as? String) ?: ClaimBasis.STATED,
            sensitive = (this["sensitive"] as? Boolean) ?: false,
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

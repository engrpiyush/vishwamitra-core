package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.drafting.GeminiDrafting
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.ProviderService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * §12.4 speaker→role resolution — the first phase of multi-speaker extraction. A single Gemini pass
 * over the whole diarized transcript maps each diarization label ("Speaker 1") to a [SpeakerRole]
 * (and, for endorsers, a [Relationship]), grounded by the asset's declared content type / source /
 * relationship and the **first-person vs third-person** cue (name-independent — the subject's name
 * is often not spoken). Resolving the binding **once** here, rather than re-guessing inside each
 * extraction chunk, is what keeps a speaker's role consistent across a long, chunk-split call
 * (E13).
 *
 * The binding it returns is advisory input to weighting, not evidence: [ClaimExtractor] applies it
 * deterministically ([ai.vishwakarma.labelling.domain.claimProvenance]) and an operator can
 * override it (§12.4 Phase B). Returns null when there is nothing to attribute (fewer than two
 * distinct speakers) or when resolution can't run/parse — callers then fall back to asset-level
 * provenance, i.e. today's behavior.
 */
/**
 * §12.4 attribution result: the proposed label→role [binding] plus the model's [confidence] (0..1)
 * in it. The service auto-runs extraction when confidence clears the threshold and otherwise gates
 * for operator speaker-selection. High confidence is meant to come from *explicit* evidence (a
 * self-introduction), not from inference — a call with no intro should score low and be reviewed.
 */
data class SpeakerResolution(
    val binding: Map<String, SpeakerAssignment>,
    val confidence: Double,
)

@Component
class SpeakerAttribution(private val gemini: GeminiDrafting) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Resolve the binding + confidence, or null when it can't/needn't be computed (class doc). */
    fun resolve(
        asset: Asset,
        transcript: Transcript,
        subjectName: String? = null,
    ): SpeakerResolution? {
        val labels = transcript.segments.mapNotNull { it.speaker?.takeIf { s -> s.isNotBlank() } }
        val distinct = labels.distinct()
        if (distinct.size < 2) return null // single-speaker / non-diarized → asset-level provenance
        if (!gemini.available()) {
            log.info("Speaker attribution skipped for asset {}: gemini unavailable", asset.id)
            return null
        }
        val raw =
            try {
                gemini.generate(
                    prompt(asset, transcript, subjectName, distinct),
                    maxTokens = MAX_TOKENS,
                    thinkingBudget = THINKING_BUDGET,
                    pin = ProviderService.PIN_STAGE2,
                )
            } catch (e: Exception) {
                log.warn("Speaker attribution call failed for asset {}: {}", asset.id, e.message)
                return null
            }
        val parsed = parse(raw, distinct)
        if (parsed == null || parsed.binding.isEmpty()) {
            log.warn("Speaker attribution produced no usable roles for asset {}", asset.id)
            return null
        }
        log.info(
            "Resolved speaker roles for asset {} (confidence {}): {}",
            asset.id,
            parsed.confidence,
            parsed.binding.mapValues { it.value.role },
        )
        return parsed
    }

    private fun prompt(
        asset: Asset,
        transcript: Transcript,
        subjectName: String?,
        labels: List<String>,
    ): String = buildString {
        appendLine(
            "You are analyzing a diarized transcript to determine each speaker's ROLE, so downstream " +
                "claim extraction can weight statements correctly (self-report vs third-party " +
                "testimony)."
        )
        appendLine()
        appendLine("Asset context:")
        appendLine("- Title: ${asset.title}")
        appendLine(
            "- Content type: ${asset.contentType.name} (source class ${asset.sourceClass.name})"
        )
        appendLine(
            "- Declared source and relationship to the subject: " +
                "${asset.sourceName ?: "unknown"} / ${asset.relationship.name}"
        )
        appendLine(
            "- The subject (the person the profile is about): " +
                (subjectName?.takeIf { it.isNotBlank() }
                    ?: "unknown — may not be named anywhere in the audio")
        )
        appendLine()
        appendLine("Speaker labels present in the transcript: ${labels.joinToString(", ")}.")
        appendLine()
        appendLine("Classify EACH label into exactly one role:")
        appendLine(
            "- SUBJECT: the person this profile is about. IMPORTANT: the subject may be ASKING the " +
                "questions (e.g. interviewing a colleague) OR answering — do NOT assume the subject " +
                "is the one who answers, self-describes, or talks the most."
        )
        appendLine(
            "- ENDORSER: a third party who speaks ABOUT the subject. Give their relationship: " +
                "EXPERT | PEER | MANAGER | MENTOR | CLIENT | FAMILY | INSTITUTION | PRESS | UNKNOWN " +
                "(the declared relationship above is a strong hint)."
        )
        appendLine(
            "- INTERVIEWER: a facilitator who is NOT the subject and makes no claims about them."
        )
        appendLine("- OTHER: none of the above (bystander, system/IVR voice, unclear).")
        appendLine()
        appendLine("Decide WHO the subject is, and report your confidence honestly:")
        appendLine(
            "- HIGH confidence (0.9+) ONLY with EXPLICIT evidence — a self-introduction (\"Hi, this " +
                "is <name>, I'll be asking my colleague…\"), someone named as the subject, or the " +
                "subject clearly addressed by name. Speech recognition may have garbled the name; " +
                "match it to the subject's name above."
        )
        appendLine(
            "- LOW confidence (below 0.9) when you are only INFERRING from who asks vs answers or " +
                "first/third-person phrasing — those are unreliable and a human will confirm."
        )
        appendLine("- If two labels are clearly the same person, give them the same role.")
        appendLine()
        appendLine("Output ONLY a JSON object (no prose, no code fences) of this exact shape:")
        appendLine(
            """  {"confidence":0.0,"speakers":{"Speaker 1":{"role":"INTERVIEWER"},"Speaker 2":{"role":"ENDORSER","relationship":"MANAGER","name":"Jane"}}}"""
        )
        appendLine(
            "Include every label under \"speakers\". \"confidence\" is a single 0..1 number for the " +
                "whole assignment. \"name\"/\"relationship\" are optional."
        )
        appendLine()
        appendLine("Transcript:")
        append(render(transcript))
    }

    /** Compact `[Speaker N] text` rendering, capped so a very long call stays within one call. */
    private fun render(transcript: Transcript): String {
        val body =
            transcript.segments.joinToString("\n") { seg ->
                "[${seg.speaker ?: "Unknown speaker"}] ${seg.text}"
            }
        return if (body.length <= MAX_TRANSCRIPT_CHARS) body
        else body.take(MAX_TRANSCRIPT_CHARS) + "\n… [transcript truncated for role resolution]"
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(raw: String, labels: List<String>): SpeakerResolution? {
        val json = stripFences(raw)
        val root = runCatching { Json.parse(json) as? Map<String, Any?> }.getOrNull() ?: return null
        val speakers = root["speakers"] as? Map<String, Any?> ?: return null
        val confidence = (root["confidence"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.0
        val known = labels.toSet()
        val binding =
            speakers
                .mapNotNull { (label, value) ->
                    if (label !in known) return@mapNotNull null
                    val fields = value as? Map<String, Any?> ?: return@mapNotNull null
                    val role =
                        SpeakerRole.fromOrNull(fields["role"] as? String) ?: return@mapNotNull null
                    label to
                        SpeakerAssignment(
                            role = role,
                            relationship =
                                Relationship.fromOrNull(fields["relationship"] as? String).takeIf {
                                    role == SpeakerRole.ENDORSER
                                },
                            name = (fields["name"] as? String)?.trim()?.takeIf { it.isNotBlank() },
                        )
                }
                .toMap()
        if (binding.isEmpty()) return null
        return SpeakerResolution(binding, confidence)
    }

    /** Strip ```json fences (the DraftPrompts idiom, shared with ClaimExtractor). */
    private fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            s = s.removeSuffix("```").trim()
        }
        return s
    }

    companion object {
        /** Role resolution is a tiny structured output; give thinking room but cap output tight. */
        const val MAX_TOKENS = 4_096
        const val THINKING_BUDGET = 2_048
        /** Cap the transcript fed to role resolution — roles are evident well within this. */
        const val MAX_TRANSCRIPT_CHARS = 40_000
    }
}

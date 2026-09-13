package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.NotebookTemplate

/**
 * The trained-in system prompt (LLD §9.6): composition and claim-subset rotation for the
 * system-prompt generation mode. Pure and deterministic like [Stage4KnowledgeBase] — no I/O, no
 * clock, no LLM, no randomness — so PLAN and GENERATE re-derive identical subsets from the frozen
 * eligible set and a re-run reproduces every subset id.
 *
 * A composed system prompt has exactly three blocks, in order:
 * 1. the **profile header** — the `stage4:system-header` row with the static placeholders
 *    (`{{subject_name}}`, `{{subject_email}}`, `{{advocate_name}}`) filled;
 * 2. the **conversation rules** — the template's sysgen-written block
 *    ([NotebookTemplate.systemRules], subject-neutral, cached per template version), or the
 *    `stage4:system-rules-default` row for probe-bank (template-less) plans;
 * 3. the **factual pointers** — one claim subset rendered as posture-labelled KB lines
 *    ([Stage4KnowledgeBase]) plus the standing rules.
 *
 * Subsets tile the eligible ledger [Stage4SystemPrompts.subsets]-deterministically: each lap covers
 * every claim once, chunk boundaries shift per lap, so with L laps every claim lands in exactly L
 * distinct subsets — the "each claim covered 2–4 times" contract with L as the dial.
 */
object Stage4SystemPrompts {

    /** One shared factual-pointer window: a stable id plus its member claims. */
    data class ClaimSubset(val id: String, val claims: List<EvidencedClaim>) {
        val claimIds: List<String>
            get() = claims.map { it.claim.id }
    }

    /** The static placeholders the header row may carry — filled before generation, never after. */
    const val SUBJECT_NAME_TOKEN = "{{subject_name}}"
    const val SUBJECT_EMAIL_TOKEN = "{{subject_email}}"
    const val ADVOCATE_NAME_TOKEN = "{{advocate_name}}"

    /**
     * Deterministic subset rotation: sort by claim id (the planner's universal tie-break), then per
     * lap rotate the ring by a lap-dependent offset and chunk by [size]. The offset step is `size /
     * 2 + 1` — deliberately never a multiple of [size], so no two laps share a chunk boundary and a
     * claim's [laps] subsets genuinely differ. A runt tail chunk (< half of [size]) merges into its
     * predecessor rather than shipping a two-claim system prompt.
     *
     * Ids read `sp<lap>-<index>-<sha6>`: lap and position for humans, a 6-hex content hash of the
     * member ids so a subset id can never silently point at different claims across publishes.
     */
    fun subsets(eligible: List<EvidencedClaim>, size: Int, laps: Int): List<ClaimSubset> {
        if (eligible.isEmpty() || size <= 0 || laps <= 0) return emptyList()
        val sorted = eligible.sortedBy { it.claim.id }
        return (0 until laps).flatMap { lap ->
            val offset = (lap * (size / 2 + 1)) % sorted.size
            val rotated = sorted.drop(offset) + sorted.take(offset)
            val chunks = rotated.chunked(size).toMutableList()
            if (chunks.size > 1 && chunks.last().size < (size + 1) / 2) {
                val tail = chunks.removeAt(chunks.lastIndex)
                chunks[chunks.lastIndex] = chunks.last() + tail
            }
            chunks.mapIndexed { index, members ->
                val ids = members.map { it.claim.id }
                val hash = Stage4Generation.shortHash(ids.joinToString(",")).take(6)
                ClaimSubset(id = "sp$lap-$index-$hash", claims = members)
            }
        }
    }

    /**
     * Fill the header row's static placeholders. A blank value drops the sentence carrying its
     * token (the `{{locale}}` clause-drop idiom, [Stage4Generation.dropSentencesWith]) — an empty
     * email must never become "reach them at .".
     */
    fun resolveHeader(
        headerRow: String,
        subjectName: String,
        subjectEmail: String,
        advocateName: String,
    ): String {
        var out = headerRow
        out =
            if (subjectName.isNotBlank()) out.replace(SUBJECT_NAME_TOKEN, subjectName)
            else Stage4Generation.dropSentencesWith(out, SUBJECT_NAME_TOKEN)
        out =
            if (subjectEmail.isNotBlank()) out.replace(SUBJECT_EMAIL_TOKEN, subjectEmail)
            else Stage4Generation.dropSentencesWith(out, SUBJECT_EMAIL_TOKEN)
        out =
            if (advocateName.isNotBlank()) out.replace(ADVOCATE_NAME_TOKEN, advocateName)
            else Stage4Generation.dropSentencesWith(out, ADVOCATE_NAME_TOKEN)
        return out.trim()
    }

    /**
     * The subject-neutral rendering of a template the sysgen prompt sees: title, category, format
     * spec and scaffold with `{{subject}}` neutralized — rules must be reusable across subjects, or
     * the per-template cache would be per-(template × subject).
     */
    fun sysgenTemplateBlock(template: NotebookTemplate): String =
        buildString {
                appendLine("Template: \"${template.title}\" (category ${template.category})")
                if (template.promptTemplate.isNotBlank()) {
                    appendLine(
                        "Scaffold: ${template.promptTemplate.replace("{{subject}}", "the subject")}"
                    )
                }
                val spec = template.formatSpec
                if (spec.turnShape.isNotBlank()) appendLine("- Turn shape: ${spec.turnShape}")
                if (spec.intent.isNotBlank()) appendLine("- Intent: ${spec.intent}")
                if (spec.personaLens.isNotBlank()) {
                    appendLine("- The guest speaks as: ${spec.personaLens}")
                }
                spec.expectedBehaviours.forEach { appendLine("- Expected behaviour: $it") }
                spec.failureModes.forEach { appendLine("- Known failure mode to prevent: $it") }
            }
            .trim()

    /**
     * The composed system prompt — header, rules and factual pointers under fixed section labels.
     * The labels are part of the trained artifact (they teach the model where its facts live), so
     * they are constants here, never admin text.
     */
    fun compose(
        header: String,
        rules: String,
        claimLines: List<String>,
        standingRules: String,
    ): String =
        buildString {
                appendLine(header.trim())
                if (rules.isNotBlank()) {
                    appendLine()
                    appendLine(RULES_LABEL)
                    appendLine(rules.trim())
                }
                appendLine()
                appendLine(FACTS_LABEL)
                if (claimLines.isEmpty()) appendLine("- none on record")
                else claimLines.forEach { appendLine("- $it") }
                if (standingRules.isNotBlank()) appendLine(standingRules)
            }
            .trim()

    /** Section label above the template conversation rules (block 2). */
    const val RULES_LABEL = "Conversation rules:"

    /** Section label above the claim-subset factual pointers (block 3). */
    const val FACTS_LABEL = "Facts on record (each with a posture you must respect):"
}

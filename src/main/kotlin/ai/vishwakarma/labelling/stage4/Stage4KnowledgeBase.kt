package ai.vishwakarma.labelling.stage4

import org.slf4j.LoggerFactory

/**
 * The §9.3 KNOWLEDGE-BASE tier for kb-generation (VA-164): every eligible claim rendered as one
 * compact, id-free line carrying a code-computed posture label, so GENERATE and JUDGE share one
 * grounding surface and the model writes the whole conversation — including the guest's opening
 * question — instead of being handed a phrased ledger sentence to voice verbatim.
 *
 * Pure and deterministic like [Stage4Evidence] (it extends that per-claim rendering): no I/O, no
 * clock, no LLM, no randomness. The KB is a per-publish derivation over the frozen eligible set
 * (`subject_scores.scoreRunId`), so it is stable per publish and never perturbs a planId or the
 * generation cache.
 *
 * The single design invariant is [postureOf]: the assert/hedge/acknowledge-only label is computed
 * here from the score bands, [Stage4VoicingPlanner.sftEligibleAlone] and the row-5 unexplained-
 * confirmed exclusion — the one place posture is decided, reused verbatim by the judge's symmetry.
 * Band cutoffs default from [SituationalHedging.TIER_HIGH]/[SituationalHedging.TIER_MEDIUM] (the
 * shared source of truth) and stay constructor-injectable for calibration, exactly as the planner's
 * do.
 *
 * KB lines are deliberately **id-free** (unlike [Stage4Evidence.line]'s `[claimId]` prefix): the KB
 * spans up to `kbMaxClaims` claims, far beyond a plan's `sourceClaimIds`, and the post-draft scrub
 * ([Stage4Prose.scrub]) only strips those source ids — a bracketed KB id would leak past it and
 * violate F7. Posture-labelled prose has nothing to leak.
 */
class Stage4KnowledgeBase(
    private val planner: Stage4VoicingPlanner = Stage4VoicingPlanner(),
    private val tierHigh: Double = SituationalHedging.TIER_HIGH,
    private val tierMedium: Double = SituationalHedging.TIER_MEDIUM,
) {

    private val log = LoggerFactory.getLogger(Stage4KnowledgeBase::class.java)

    /** The three postures the KB assigns a claim, most to least assertive. */
    enum class Posture(val label: String) {
        /** Score ≥ HIGH with no unexplained conflict: may be stated flatly. */
        ASSERT("ASSERT"),
        /** MEDIUM ≤ score < HIGH: voiced, but softened and attributed. */
        HEDGE("HEDGE"),
        /**
         * Score < MEDIUM, or an unexplained CONFIRMED conflict / not SFT-eligible alone: may be
         * acknowledged if raised, never advanced or asserted.
         */
        ACKNOWLEDGE_ONLY("ACKNOWLEDGE-ONLY"),
    }

    /**
     * The posture [e] carries in the KB (the single source of truth, reused by the judge). Mirrors
     * the design headline: ACKNOWLEDGE-ONLY when the score is below the MEDIUM band, or the claim
     * carries an unexplained CONFIRMED conflict, or it is not SFT-eligible on its own (the row-5
     * exclusion, delegated to [Stage4VoicingPlanner.sftEligibleAlone] so eligibility stays defined
     * in one place); ASSERT at or above the HIGH band; HEDGE in between. [confirmedUnexplained] is
     * named explicitly so an opted-in sensitive claim (row 8, SFT-eligible) with a live confirmed
     * conflict still degrades rather than asserting.
     */
    fun postureOf(e: EvidencedClaim): Posture =
        when {
            e.score < tierMedium || e.confirmedUnexplained || !planner.sftEligibleAlone(e) ->
                Posture.ACKNOWLEDGE_ONLY
            e.score >= tierHigh -> Posture.ASSERT
            else -> Posture.HEDGE
        }

    /**
     * Render the eligible set as KB lines: sorted by claim id (the planner's universal tie-break),
     * capped at [kbMaxClaims] — when the set overruns the cap the first [kbMaxClaims] by id are
     * kept and the drop is logged with its count (a KB that silently forgets claims would let the
     * drafter deny one). Each line is id-free posture-labelled prose (see the class note).
     */
    fun render(eligible: List<EvidencedClaim>, kbMaxClaims: Int): List<String> {
        val sorted = eligible.sortedBy { it.claim.id }
        val kept =
            if (sorted.size > kbMaxClaims) {
                log.warn(
                    "Knowledge base truncated: {} eligible claim(s) exceed kb-max-claims={} — " +
                        "dropping the {} highest-id claim(s) from the KB",
                    sorted.size,
                    kbMaxClaims,
                    sorted.size - kbMaxClaims,
                )
                sorted.take(kbMaxClaims)
            } else {
                sorted
            }
        return kept.map { line(it) }
    }

    /** The standing rules emitted alongside the KB — the KB's own governing contract (§9.3). */
    fun standingRules(): String = STANDING_RULES

    /**
     * One id-free KB line: text · posture · score+tier · anchored/independent · favourability ·
     * sidecar (short) · dates at stated precision (F5).
     */
    private fun line(e: EvidencedClaim): String = buildString {
        append("\"${e.claim.text}\"")
        append(" — ${postureOf(e).label}")
        append("; score ${"%.2f".format(e.score)}")
        e.claim.authenticityTier?.let { append(" (${it.name})") }
        val markers = buildList {
            if (e.claim.factStamp?.anchored == true) add("anchored")
            if (isIndependent(e)) add("independent")
        }
        if (markers.isNotEmpty()) append("; ${markers.joinToString(" + ")}")
        e.claim.favorability?.let { append("; favourability ${"%.2f".format(it)}") }
        e.sidecar?.let { append("; explanation on record: \"${it.take(SIDECAR_MAX)}\"") }
        e.claim.factStamp?.let { fs ->
            val dates = listOfNotNull(fs.validFrom, fs.validTo).distinct()
            if (dates.isNotEmpty()) {
                append("; dated ${dates.joinToString(" → ")}")
                fs.datePrecision?.let { append(" ($it precision)") }
            }
        }
    }

    /**
     * Backed by at least one non-SELF attestor — the per-claim projection of
     * [SituationalEvidence]'s fact-level `independent`. Threshold-free (attestor identity, not the
     * independence signal), so the marker needs no band of its own.
     */
    private fun isIndependent(e: EvidencedClaim): Boolean =
        e.claim.attestor?.kind?.let { it.isNotBlank() && !it.equals(ATTESTOR_SUBJECT, true) }
            ?: false

    companion object {
        /**
         * The KB's governing contract, emitted with the list on both the GENERATE and JUDGE side.
         */
        const val STANDING_RULES =
            "Standing rules for this knowledge base: never deny or contradict anything listed; " +
                "ACKNOWLEDGE-ONLY claims may be acknowledged if the guest raises them but never " +
                "advanced or asserted; nothing outside this list exists."

        private const val ATTESTOR_SUBJECT = "SUBJECT"
        private const val SIDECAR_MAX = 160
    }
}

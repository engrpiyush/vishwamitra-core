package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.CriticismResponse
import ai.vishwakarma.labelling.domain.EndorserAttribution
import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.OutOfCorpusPolicy
import ai.vishwakarma.labelling.domain.PersonaPosture
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.PlannerPersona
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.SpeculationPolicy
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.domain.WeaknessEagerness
import ai.vishwakarma.labelling.domain.WeaknessFraming
import java.security.MessageDigest

/**
 * A published claim plus its human review layer — everything the planner may read about one
 * evidence unit. Derived accessors normalize the ledger's nullable score block.
 */
data class EvidencedClaim(val claim: Claim, val review: ClaimReview? = null) {
    val score: Double
        get() = claim.authenticityScore ?: 0.0

    val scoreBare: Double
        get() = claim.authenticityScoreBare ?: claim.authenticitySignals?.get("scoreBare") ?: score

    val conflict: Double
        get() = claim.authenticitySignals?.get("conflict") ?: 0.0

    val independence: Double
        get() = claim.authenticitySignals?.get("independence") ?: 0.0

    /** A judged-relevant explanation exists: an explained CONTRADICTS edge or a sidecar. */
    val explained: Boolean
        get() =
            (claim.edgeCounts?.get("contradictsExplained") ?: 0) > 0 ||
                review?.decision == ReviewDecision.SIDECARED

    /** A human-CONFIRMED contradiction with no explanation — the row 5 exclusion. */
    val confirmedUnexplained: Boolean
        get() = (claim.edgeCounts?.get("contradictsConfirmed") ?: 0) > 0 && !explained

    /** F3: sensitive claims may only be voiced after an explicit PII opt-in. */
    val piiOptedIn: Boolean
        get() = review?.piiChoice == PiiChoice.INCLUDE

    /** The sidecar text rows 2/6/7 embed, when one exists. */
    val sidecar: String?
        get() =
            review
                ?.takeIf { it.decision == ReviewDecision.SIDECARED }
                ?.justification
                ?.takeIf { it.isNotBlank() }
}

/** Question classes the engine plans without a backing claim (§8 rows 11–14). */
enum class QuestionClass {
    CRITICISM,
    OUT_OF_CORPUS,
    BANNED,
    AUDIENCE_SELF_ID,
}

/**
 * One plannable conversation unit. [unitKey] discriminates conversations that share evidence — two
 * situational templates over the same facts are distinct conversations, so it participates in the
 * plan hash alongside the LLD's claimIds+row+persona.
 */
sealed interface PlanUnit {
    val category: Stage4Category
    val unitKey: String

    /** Single-claim Q&A (§9.2). */
    data class ClaimUnit(
        val evidenced: EvidencedClaim,
        override val category: Stage4Category = Stage4Category.QA,
        override val unitKey: String = evidenced.claim.id,
    ) : PlanUnit

    /** A fact group / SUCCEEDS chain voiced as one multi-claim conversation (§9.2). */
    data class FactGroupUnit(
        val members: List<EvidencedClaim>,
        /** Typically the factId or chain id. */
        override val unitKey: String,
    ) : PlanUnit {
        override val category: Stage4Category
            get() = Stage4Category.MULTI_CLAIM
    }

    /** A situational question over a supporting-evidence chain (§10). */
    data class SituationalUnit(
        val question: String,
        val family: SituationalFamily,
        val chain: List<SupportingFact>,
    ) : PlanUnit {
        override val category: Stage4Category
            get() = Stage4Category.SITUATIONAL

        override val unitKey: String
            get() = "${family.id}|$question"
    }

    /** A probe with no backing evidence unit: criticism, out-of-corpus, banned, audience. */
    data class QuestionUnit(
        val kind: QuestionClass,
        val question: String,
        val relatedClaimIds: List<String> = emptyList(),
    ) : PlanUnit {
        override val category: Stage4Category
            get() =
                when (kind) {
                    QuestionClass.AUDIENCE_SELF_ID -> Stage4Category.META
                    else -> Stage4Category.NEGATIVE
                }

        override val unitKey: String
            get() = "${kind.name}|$question"
    }
}

/**
 * The §8 voicing planner: `plan(unit, persona) → VoicingPlan`. Pure — no I/O, no clock, no
 * randomness; identical inputs reproduce the identical plan including its [VoicingPlan.planId] (the
 * re-plan-without-re-generate contract, §1).
 *
 * Fixed rules are enforced structurally where possible: F3 — un-opted-in sensitive claims are
 * rejected outright (SELECT never admits them; reaching here is an engine bug); F4 — hedges only
 * ever shift *down* ([HedgeLevel] has no `up()`); the persona free text cannot influence a plan
 * because [PlannerPersona] does not carry it (§7 merge rule). Unmatched input degrades to row 12's
 * voice — the implicit floor.
 *
 * Thresholds default to the Stage 3 tier bands and are constructor-injectable for calibration.
 */
class Stage4VoicingPlanner(
    private val tierHigh: Double = 0.75,
    private val tierMedium: Double = 0.45,
    /**
     * Row 6 trigger: explained score exceeding the bare score by at least this (scoreBare ≪ score).
     */
    private val scoreBareGap: Double = 0.15,
    /** Row 1 trigger: residual conflict signal at or below this counts as "conflict ≈ 0". */
    private val conflictEpsilon: Double = 0.05,
    /** Row 4 trigger: independence signal at or above this reads as independently supported. */
    private val independenceHigh: Double = 0.5,
    private val hedging: SituationalHedging = SituationalHedging(tierHigh, tierMedium),
) {

    /** [personaHash] is the resolved persona's hash — it keys the plan id (QA-6 staleness). */
    fun plan(unit: PlanUnit, persona: PlannerPersona, personaHash: String): VoicingPlan =
        when (unit) {
            is PlanUnit.ClaimUnit -> planClaim(unit, persona, personaHash)
            is PlanUnit.FactGroupUnit -> planFactGroup(unit, persona, personaHash)
            is PlanUnit.SituationalUnit -> planSituational(unit, persona, personaHash)
            is PlanUnit.QuestionUnit -> planQuestion(unit, persona, personaHash)
        }

    // ---- rows 1–8: claim-backed voices ---------------------------------------------------

    private fun planClaim(
        unit: PlanUnit.ClaimUnit,
        persona: PlannerPersona,
        personaHash: String,
    ): VoicingPlan {
        val e = unit.evidenced
        require(e.review?.decision != ReviewDecision.CONTESTED) {
            "CONTESTED claim ${e.claim.id} must never reach the planner (SELECT guard)"
        }
        require(!e.claim.sensitive || e.piiOptedIn) {
            "sensitive claim ${e.claim.id} without PII opt-in must never reach the planner (F3)"
        }

        val row = claimRow(e)
        val hedged =
            if (persona.posture == PersonaPosture.CONSERVATIVE && row.id in BAND_ROWS) {
                row.copy(hedge = row.hedge.down())
            } else {
                row
            }
        return build(
            unit = unit,
            row = hedged,
            claimIds = listOf(e.claim.id),
            constraints = claimConstraints(e, hedged.id, persona),
            personaHash = personaHash,
        )
    }

    private fun claimRow(e: EvidencedClaim): Row =
        when {
            // Row 8 — opted-in PII is voiced verbatim, whatever its score says.
            e.claim.sensitive -> Row(8, "Verbatim-only", HedgeLevel.ASSERTIVE)
            // Row 5 — unexplained CONFIRMED conflict: never SFT, DPO rejected-side candidate.
            e.confirmedUnexplained ->
                Row(
                    5,
                    "Avoid in SFT; DPO rejected-side candidate",
                    HedgeLevel.GAP,
                    sftEligible = false,
                )
            // Row 6 — the explanation carries the score: sidecar framing is mandatory.
            e.score > 0.0 && e.score - e.scoreBare >= scoreBareGap ->
                Row(6, "Context-mandatory", HedgeLevel.HEDGED)
            // Row 7 — high-scoring weakness: honest self-model (S4-D1 amended).
            e.claim.claimType == ClaimType.WEAKNESS && e.score >= tierHigh ->
                Row(7, "Honest self-model", HedgeLevel.MEASURED)
            e.score >= tierHigh ->
                when {
                    e.conflict <= conflictEpsilon -> Row(1, "Assertive", HedgeLevel.ASSERTIVE)
                    e.explained -> Row(2, "Assertive + sidecar context", HedgeLevel.ASSERTIVE)
                    // High band with live unexplained (but unconfirmed) conflict: no row —
                    // the implicit floor catches it.
                    else -> floorRow()
                }
            e.score >= tierMedium ->
                if (e.independence >= independenceHigh) {
                    Row(4, "Hedged, endorsed", HedgeLevel.HEDGED)
                } else {
                    Row(3, "Hedged, self-attributed", HedgeLevel.HEDGED)
                }
            else -> floorRow()
        }

    private fun claimConstraints(
        e: EvidencedClaim,
        rowId: Int,
        persona: PlannerPersona,
    ): List<String> {
        val out = mutableListOf(F2, F5)
        when (rowId) {
            2 ->
                out +=
                    "Assert the claim and carry its explanation context alongside" +
                        (e.sidecar?.let { ": $it" } ?: ".")
            3 -> out += "Hedge and self-attribute (\"she says\", \"as he tells it\")."
            4 ->
                out +=
                    "Hedge and attribute to the independent voices — " +
                        endorserAttribution(persona)
            5 -> out += "Never voice this claim in an SFT conversation."
            6 ->
                out +=
                    "Context-mandatory: never voice this claim without its sidecar framing" +
                        (e.sidecar?.let { ": $it" } ?: ".")
            7 -> {
                out += "Candid when asked; never deny the weakness (F2)."
                out += weaknessVolunteering(e, persona)
                out +=
                    when (persona.weaknessFraming) {
                        WeaknessFraming.GROWTH_NARRATIVE ->
                            "Frame the weakness as a growth narrative (D9)."
                        WeaknessFraming.MATTER_OF_FACT ->
                            "Frame the weakness matter-of-factly, without spin (D9)."
                    }
            }
            8 ->
                out +=
                    "F3: render the opted-in detail verbatim from the claim text — never " +
                        "paraphrased, never embellished."
        }
        return out
    }

    /** C7: how row 4 names its independent voices. */
    private fun endorserAttribution(persona: PlannerPersona): String =
        when (persona.endorserAttribution) {
            EndorserAttribution.ROLE_ONLY ->
                "attribute by role only (e.g. \"a former manager\"), never by name (C7)."
            EndorserAttribution.NAMED -> "endorsers may be attributed by name (C7)."
        }

    private fun weaknessVolunteering(e: EvidencedClaim, persona: PlannerPersona): String {
        val sidecarBacked = e.sidecar != null
        return when {
            !sidecarBacked ->
                "Do not volunteer this weakness unprompted (no sidecar backs it — S4-D1)."
            persona.weaknessEagerness == WeaknessEagerness.PROACTIVE ->
                "May volunteer this sidecar-backed weakness proactively (D8)."
            persona.weaknessEagerness == WeaknessEagerness.RELEVANT_CONTEXT ->
                "May volunteer this sidecar-backed weakness when contextually relevant (D8)."
            else -> "Do not volunteer unprompted; answer candidly when asked (D8)."
        }
    }

    // ---- multi-claim: the group takes its most cautious member's voice --------------------

    private fun planFactGroup(
        unit: PlanUnit.FactGroupUnit,
        persona: PlannerPersona,
        personaHash: String,
    ): VoicingPlan {
        require(unit.members.isNotEmpty()) { "fact group ${unit.unitKey} has no members" }
        val memberPlans =
            unit.members.map { it to planClaim(PlanUnit.ClaimUnit(it), persona, personaHash) }
        val eligible = memberPlans.filter { (_, p) -> p.sftEligible }

        // Every member is a row 5 exclusion → the group itself is only a DPO candidate.
        if (eligible.isEmpty()) {
            val worst = memberPlans.first().second
            return build(
                unit = unit,
                row =
                    Row(
                        5,
                        "Avoid in SFT; DPO rejected-side candidate",
                        HedgeLevel.GAP,
                        sftEligible = false,
                    ),
                claimIds = unit.members.map { it.claim.id },
                constraints = worst.constraints,
                personaHash = personaHash,
            )
        }

        // Governing voice = the most cautious eligible member (F4: a group never voices any
        // member above what that member's own row authorizes).
        val governing = eligible.maxBy { (_, p) -> p.hedgeLevel.ordinal }.second
        val dropped = memberPlans.filterNot { (_, p) -> p.sftEligible }
        val constraints = buildList {
            addAll(eligible.flatMap { (_, p) -> p.constraints }.distinct())
            if (dropped.isNotEmpty()) {
                add(
                    "Excluded from this conversation (unexplained confirmed conflict): " +
                        dropped.joinToString(", ") { (m, _) -> m.claim.id }
                )
            }
        }
        return build(
            unit = unit,
            row = Row(governing.rowId, governing.voice, governing.hedgeLevel),
            claimIds = eligible.map { (m, _) -> m.claim.id },
            constraints = constraints,
            personaHash = personaHash,
        )
    }

    // ---- rows 9–10: situational ------------------------------------------------------------

    private fun planSituational(
        unit: PlanUnit.SituationalUnit,
        persona: PlannerPersona,
        personaHash: String,
    ): VoicingPlan {
        val claimIds = unit.chain.flatMap { it.claimIds }.distinct()
        if (persona.speculation == SpeculationPolicy.OFF) {
            return build(
                unit = unit,
                row = Row(10, "Honest-gap degradation", HedgeLevel.GAP),
                claimIds = claimIds,
                constraints =
                    listOf(F2, F5, "Speculation is disabled for this persona (E15): honest gap."),
                personaHash = personaHash,
            )
        }

        val verdict = hedging.compute(unit.chain)
        if (!verdict.floorsMet) {
            return build(
                unit = unit,
                row = Row(10, "Honest-gap degradation", HedgeLevel.GAP),
                claimIds = claimIds,
                constraints =
                    listOf(
                        F2,
                        F5,
                        "Evidence floors unmet (${verdict.rationale}): answer with an honest " +
                            "gap — no derived conclusion.",
                    ),
                personaHash = personaHash,
            )
        }

        // E15=conservative caps the conclusion one phrase down (down-only, F4-compatible).
        val phrase =
            if (persona.speculation == SpeculationPolicy.CONSERVATIVE) {
                HedgePhrase.REASONABLE_TO_EXPECT
            } else {
                checkNotNull(verdict.phrase)
            }
        val hedge =
            if (phrase == HedgePhrase.VERY_LIKELY) HedgeLevel.MEASURED else HedgeLevel.HEDGED
        val constraints = buildList {
            add(F2)
            add(F5)
            add("Visible in-reply grounding: cite the supporting evidence in the answer itself.")
            add("Conclusion ceiling: no stronger than \"${phrase.rendered}\" (§10.3).")
            if (verdict.droppedFactIds.isNotEmpty()) {
                add(
                    "Excluded from the chain (unexplained conflict): " +
                        verdict.droppedFactIds.joinToString(", ")
                )
            }
            if (verdict.namedTensionFactIds.isNotEmpty()) {
                add(
                    "Name the unresolved tension on: " +
                        verdict.namedTensionFactIds.joinToString(", ")
                )
            }
        }
        val surviving = verdict.survivingClaimIds(unit.chain)
        return build(
            unit = unit,
            row = Row(9, "Grounded derivation", hedge),
            claimIds = surviving,
            constraints = constraints,
            personaHash = personaHash,
        )
    }

    // ---- rows 11–14: question-class voices ---------------------------------------------------

    private fun planQuestion(
        unit: PlanUnit.QuestionUnit,
        persona: PlannerPersona,
        personaHash: String,
    ): VoicingPlan {
        val row =
            when (unit.kind) {
                QuestionClass.CRITICISM ->
                    when (persona.criticismResponse) {
                        CriticismResponse.REFRAME_WITH_EVIDENCE ->
                            Row(11, "Reframe with evidence", HedgeLevel.MEASURED)
                        CriticismResponse.ACKNOWLEDGE_AND_REDIRECT ->
                            Row(11, "Acknowledge and redirect", HedgeLevel.MEASURED)
                    }
                QuestionClass.OUT_OF_CORPUS -> Row(12, "Honest gap + nearest fact", HedgeLevel.GAP)
                QuestionClass.BANNED -> Row(13, "Polite refusal", HedgeLevel.GAP)
                QuestionClass.AUDIENCE_SELF_ID -> Row(14, "Register shift", HedgeLevel.MEASURED)
            }
        val constraints = buildList {
            add(F2)
            add(F5)
            when (unit.kind) {
                QuestionClass.CRITICISM ->
                    add(
                        when (persona.criticismResponse) {
                            CriticismResponse.REFRAME_WITH_EVIDENCE ->
                                "Respond to the criticism by reframing with ledger facts only " +
                                    "(D10) — no counter-attack, no denial."
                            CriticismResponse.ACKNOWLEDGE_AND_REDIRECT ->
                                "Acknowledge the criticism plainly, then redirect to evidenced " +
                                    "strengths (D10) — no denial."
                        }
                    )
                QuestionClass.OUT_OF_CORPUS ->
                    add(
                        when (persona.outOfCorpus) {
                            OutOfCorpusPolicy.HONEST_GAP_NEAREST_FACT ->
                                "Admit the gap honestly, then offer the nearest evidenced fact " +
                                    "(entity adjacency, E13)."
                            OutOfCorpusPolicy.PLAIN_DECLINE ->
                                "Admit the gap honestly and decline to go further (E13)."
                        }
                    )
                QuestionClass.BANNED ->
                    add(
                        "Politely refuse: this question class is out of bounds (§10.2). Do not " +
                            "engage the premise; offer no probability."
                    )
                QuestionClass.AUDIENCE_SELF_ID ->
                    add(
                        "F6: shift register for the self-identified audience; facts and voice " +
                            "levels stay exactly as planned."
                    )
            }
        }
        return build(
            unit = unit,
            row = row,
            claimIds = unit.relatedClaimIds,
            constraints = constraints,
            personaHash = personaHash,
        )
    }

    // ---- assembly -----------------------------------------------------------------------------

    private data class Row(
        val id: Int,
        val voice: String,
        val hedge: HedgeLevel,
        val sftEligible: Boolean = true,
    )

    /** Row 12's voice — the implicit floor every unmatched input degrades to (§8). */
    private fun floorRow() = Row(12, "Honest gap + nearest fact", HedgeLevel.GAP)

    private fun build(
        unit: PlanUnit,
        row: Row,
        claimIds: List<String>,
        constraints: List<String>,
        personaHash: String,
    ): VoicingPlan =
        VoicingPlan(
            planId = planId(unit, row, claimIds, personaHash),
            rowId = row.id,
            voice = row.voice,
            hedgeLevel = row.hedge,
            constraints = constraints,
            sourceClaimIds = claimIds,
            category = unit.category,
            sftEligible = row.sftEligible,
        )

    /**
     * planId = hash(claimIds + row + persona) per LLD §6, plus the category and unit key so two
     * different planned questions over the same evidence stay distinct conversations.
     */
    private fun planId(
        unit: PlanUnit,
        row: Row,
        claimIds: List<String>,
        personaHash: String,
    ): String {
        val canonical =
            "v1|${unit.category.name}|${unit.unitKey}|claims=" +
                claimIds.sorted().joinToString(",") +
                "|row=${row.id}|hedge=${row.hedge.name}|persona=$personaHash"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    companion object {
        /** Band rows the C6=conservative dial shifts one notch down (§8). */
        private val BAND_ROWS = setOf(1, 2, 3, 4)

        // The always-on fixed-rule constraint lines (F1/F6 live on the generation prompt's
        // fixed card; F3/F4 are structural).
        const val F2 = "F2: never deny an evidenced fact — including weaknesses and conflicts."
        const val F5 =
            "F5: voice dates and numbers at their stated precision — never invent finer detail."
    }
}

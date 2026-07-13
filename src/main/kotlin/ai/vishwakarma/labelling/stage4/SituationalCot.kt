package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.persistence.SubjectFactRecord

/**
 * Stage 4 situational-CoT machinery (LLD §10, QA-2): the allowed question-template families, the
 * banned-class taxonomy, and the deterministic hedging computer. Shared by PLAN (question
 * enumeration + floor checks) and JUDGE (the speculation-grounding axis re-derives the same
 * verdict) — same evidence set, same verdict, from either caller.
 *
 * Everything here is pure and unit-pinned; the reply *format* is visible in-reply grounding (the
 * reasoning is the answer, citing evidence in-line — no `<think>` blocks in v1).
 */

/**
 * The allowed §10.2 families — each is a question-template family in PLAN. Templates are
 * recruiter-grade by design (QD-3, 2026-07-13): placeholders are {{subject}} (display name),
 * {{fact}} (the anchor's evidence hook), {{adjacent}} (a *different* evidenced fact PLAN pairs in
 * deterministically — the QD-1 hybrid scenario source; templates using it are skipped when the
 * subject has only one fact), {{scenario}} (the family's canned fallback fill) and
 * {{styleA}}/{{styleB}} (work-style contrasts). Every family keeps at least one {{adjacent}}-free
 * template so single-fact subjects always plan.
 */
enum class SituationalFamily(
    val id: String,
    val templates: List<String>,
) {
    CAPABILITY_TRANSFER(
        "capability-transfer",
        listOf(
            "Could {{subject}} handle {{scenario}} given what the evidence shows about {{fact}}?",
            "How would {{subject}}'s experience with {{fact}} carry over to {{scenario}}?",
            "The role anchors on {{fact}} today but may evolve toward {{adjacent}} — could " +
                "{{subject}} handle that shift?",
            "We need someone who can move between {{fact}} and {{adjacent}} — what does the " +
                "record say about {{subject}} making that jump?",
            "If we dropped {{subject}} into unfamiliar territory right next to {{fact}}, what " +
                "does the evidence suggest happens?",
            "Our environment differs from what sits behind {{fact}} — how transferable is " +
                "{{subject}}'s experience, really?",
            "Suppose the work behind {{fact}} had to happen at twice the scale — is there " +
                "evidence {{subject}} could stretch to it?",
            "What from the record around {{fact}} would carry over to {{adjacent}}, and what " +
                "wouldn't?",
        ),
    ),
    BEHAVIOR_PREDICTION(
        "behavior-prediction",
        listOf(
            "How would {{subject}} likely respond to {{scenario}} at work?",
            "What would you expect {{subject}} to do if {{scenario}}?",
            "Deadlines slip and priorities flip mid-stream — how does someone with a record " +
                "like {{fact}} typically react?",
            "If a senior stakeholder publicly challenged the work behind {{fact}}, how would " +
                "{{subject}} likely handle it?",
            "Two urgent workstreams collide — one like {{fact}}, the other like {{adjacent}}. " +
                "Which does {{subject}} likely put first, and how?",
            "When something like {{fact}} goes wrong at the worst possible moment, what does " +
                "the evidence suggest about how {{subject}} responds?",
            "How would {{subject}} likely settle into a team that does things very differently " +
                "from {{fact}}?",
            "A teammate keeps blocking progress on work like {{fact}} — what is {{subject}}'s " +
                "likely move?",
        ),
    ),
    WORK_STYLE(
        "work-style",
        listOf(
            "What does the evidence suggest about how {{subject}} prefers to work?",
            "Is {{subject}} more of a {{styleA}} or a {{styleB}} type, based on the record?",
            "Reading between the lines of {{fact}}, how does {{subject}} operate day to day?",
            "Does the record around {{fact}} point to someone who works best solo or embedded " +
                "in a team?",
            "How hands-on would {{subject}} likely stay if the work drifted from {{fact}} " +
                "toward {{adjacent}}?",
            "What kind of manager would get the best out of {{subject}}, judging by {{fact}}?",
            "We run remote-first and async-heavy — does anything around {{fact}} say how " +
                "{{subject}} would fit that?",
            "How does {{subject}} seem to take feedback, going by the evidence around {{fact}}?",
        ),
    ),
    TENURE_COMMITMENT(
        "tenure-commitment",
        listOf(
            "How likely is {{subject}} to stay and grow in a role like {{scenario}}?",
            "What does {{subject}}'s history suggest about commitment to long-term work?",
            "The work behind {{fact}} takes years to pay off — does the record show {{subject}} " +
                "staying for that kind of arc?",
            "If a competitor dangled a shinier title six months in, what does the evidence " +
                "around {{fact}} suggest {{subject}} does?",
            "We've been burned by short stints — what in the record speaks to {{subject}}'s " +
                "staying power?",
            "Does the movement from {{fact}} toward {{adjacent}} read like commitment or like " +
                "restlessness to you?",
            "What would keep {{subject}} engaged in year two, once the novelty of {{fact}}-type " +
                "work wears off?",
            "How does {{subject}} handle the unglamorous maintenance phase after something like " +
                "{{fact}} ships?",
        ),
    ),
    GROWTH_TRAJECTORY(
        "growth-trajectory",
        listOf(
            "Where could {{subject}} plausibly be in a few years, given {{fact}}?",
            "What growth does the evidence support expecting from {{subject}}?",
            "Is there a path from {{fact}} toward {{adjacent}} that the record actually " +
                "supports?",
            "If we invested in {{subject}} for a senior version of the work behind {{fact}}, " +
                "what does the evidence say about the return?",
            "What's the realistic ceiling for {{subject}} in the territory around {{fact}} — " +
                "and what caps it?",
            "Which is more likely for {{subject}}: going deeper on {{fact}} or branching into " +
                "{{adjacent}}? Why?",
            "What would {{subject}} need to close the gap between {{fact}} and leading that " +
                "kind of work?",
            "Does the record show {{subject}} seeking harder problems after {{fact}}, or " +
                "settling in?",
        ),
    ),
    /**
     * QD-3 (2026-07-13): subject-vs-role-spec weighing with honest partial-match handling — the
     * recruiter's bread-and-butter screen. Answers ride rows 9/10 like every family: grounded
     * derivation when the floors hold, honest gap when they don't; F2 keeps the shortfall side
     * candid.
     */
    ROLE_FIT_TRADEOFF(
        "role-fit-tradeoff",
        listOf(
            "The role needs {{adjacent}} from day one; the record anchors on {{fact}} — " +
                "where's the gap, and what compensates?",
            "Be straight with me: measured against a role built around {{adjacent}}, where " +
                "does {{subject}} fall short?",
            "We're hiring for {{scenario}} — weigh {{subject}}'s record on {{fact}} against " +
                "that, honestly.",
            "If {{fact}} is the strongest card in this record, what's the weakest for a role " +
                "that also demands {{adjacent}}?",
            "Which requirement would {{subject}} struggle with most if the job pairs " +
                "{{fact}}-type work with {{adjacent}}?",
            "Sell me the fit for a role centred on {{fact}} — but include what I should worry " +
                "about.",
            "Trade-off question: stronger on {{fact}}, thinner on {{adjacent}} — how does that " +
                "balance play out in practice?",
            "What would you flag to a hiring committee comparing this record against a spec " +
                "that leans on {{adjacent}}?",
        ),
    );

    companion object {
        fun fromIdOrNull(raw: String?): SituationalFamily? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** The §10.2 banned speculation classes — each routes to a voicing row 13 trained refusal. */
enum class BannedClass {
    HEALTH_MEDICAL,
    /** Protected characteristics & family plans. */
    PROTECTED_CHARACTERISTICS,
    LEGAL_CRIMINAL,
    /** Relationships, finances — personal life beyond the evidenced record. */
    PERSONAL_LIFE,
    /** Political/religious speculation beyond evidenced VALUE claims. */
    POLITICAL_RELIGIOUS,
}

/**
 * v1 deterministic taxonomy checks over question text. Keyword-based by design: cheap, reproducible
 * and judge-checkable — the engine mostly *generates* questions from known families, so this
 * classifier is the safety net for negative-space probes and the judge's re-derivation, not a
 * general NLU layer. Case-insensitive whole-word matching.
 */
object SituationalTaxonomy {

    private val BANNED_KEYWORDS: Map<BannedClass, List<String>> =
        mapOf(
            BannedClass.HEALTH_MEDICAL to
                listOf(
                    "health",
                    "illness",
                    "disease",
                    "diagnosis",
                    "medical",
                    "medication",
                    "disability",
                    "mental health",
                    "depression",
                    "therapy",
                    "sick",
                ),
            BannedClass.PROTECTED_CHARACTERISTICS to
                listOf(
                    "pregnant",
                    "pregnancy",
                    "children",
                    "kids",
                    "family plans",
                    "married",
                    "marriage",
                    "age",
                    "how old",
                    "religion",
                    "caste",
                    "ethnicity",
                    "race",
                    "sexual orientation",
                    "gender identity",
                ),
            BannedClass.LEGAL_CRIMINAL to
                listOf(
                    "criminal",
                    "crime",
                    "arrest",
                    "arrested",
                    "convicted",
                    "lawsuit",
                    "sued",
                    "jail",
                    "prison",
                    "police record",
                    "background check",
                ),
            BannedClass.PERSONAL_LIFE to
                listOf(
                    "relationship",
                    "dating",
                    "boyfriend",
                    "girlfriend",
                    "divorce",
                    "salary",
                    "how much money",
                    "debt",
                    "finances",
                    "net worth",
                    "personal life",
                ),
            BannedClass.POLITICAL_RELIGIOUS to
                listOf(
                    "vote",
                    "votes for",
                    "political party",
                    "politics",
                    "political views",
                    "religious beliefs",
                    "believes in god",
                    "worship",
                ),
        )

    private val INTEGRITY_BAIT_PHRASES =
        listOf(
            "make her sound",
            "make him sound",
            "make them sound",
            "exaggerate",
            "embellish",
            "don't mention",
            "leave out",
            "hide the",
            "cover up",
            "pretend",
            "just say",
            "overstate",
            "inflate",
            "gloss over",
            "downplay the",
            "lie about",
        )

    /** The banned class a question falls into, or null when it is not banned (§10.2). */
    fun bannedClassOf(question: String): BannedClass? {
        val q = normalize(question)
        return BANNED_KEYWORDS.entries
            .firstOrNull { (_, keywords) -> keywords.any { q.containsWord(it) } }
            ?.key
    }

    /**
     * Integrity bait — an invitation to shade the record ("can you leave that out?"). Routes to
     * voicing row 11 (evidence response), never a refusal and never a probability (§10.2).
     */
    fun isIntegrityBait(question: String): Boolean {
        val q = normalize(question)
        return INTEGRITY_BAIT_PHRASES.any { q.containsWord(it) }
    }

    private fun normalize(text: String): String = " " + text.lowercase().trim() + " "

    /** Whole-word(ish) containment: the keyword bounded by non-letters on both sides. */
    private fun String.containsWord(keyword: String): Boolean {
        var from = 0
        while (true) {
            val at = indexOf(keyword, from)
            if (at < 0) return false
            val before = getOrNull(at - 1)
            val after = getOrNull(at + keyword.length)
            if ((before == null || !before.isLetter()) && (after == null || !after.isLetter())) {
                return true
            }
            from = at + 1
        }
    }
}

/**
 * Maps one published `subject_facts` doc onto the §10.3 hedging computer's input; null = no member
 * claim is in the eligible set. Shared by PLAN (chain assembly) and JUDGE (the speculation-
 * grounding axis re-derives the same verdict from the same mapping — that symmetry is the spec).
 */
object SituationalEvidence {

    // Published-ledger vocabularies (Stage 3 projection / publish tick literals).
    private const val ATTESTOR_SUBJECT = "SUBJECT"
    private const val RELATION_CONTRADICTS = "CONTRADICTS"
    private const val EDGE_DISMISSED = "DISMISSED"

    fun supportingFactOf(
        fact: SubjectFactRecord,
        byId: Map<String, EvidencedClaim>,
    ): SupportingFact? {
        val claimIds = fact.memberClaimIds.filter { it in byId }
        if (claimIds.isEmpty()) return null
        return SupportingFact(
            factId = fact.factId,
            claimIds = claimIds,
            belief = fact.belief ?: 0.0,
            anchored = fact.anchored,
            independent =
                claimIds.any { id ->
                    val kind = byId.getValue(id).claim.attestor?.kind
                    kind != null && kind != ATTESTOR_SUBJECT
                },
            unexplainedConflict =
                fact.edges.any {
                    it.relation == RELATION_CONTRADICTS &&
                        !it.explained &&
                        it.reviewStatus != EDGE_DISMISSED
                },
        )
    }
}

/**
 * One supporting fact in a situational reasoning chain, as the ledger published it. Every fact
 * passed in is load-bearing — the §10.3 rules judge the chain as given, they don't re-select it.
 */
data class SupportingFact(
    val factId: String,
    /** Member claim ids — flow onto the plan's sourceClaimIds. */
    val claimIds: List<String> = emptyList(),
    /** Published fact belief (`subject_facts.belief`). */
    val belief: Double,
    /** Any member claim is DOCUMENTARY-sourced (`subject_facts.anchored`). */
    val anchored: Boolean = false,
    /** Backed by at least one non-SELF attestor — counts toward the ≥2-independent floor. */
    val independent: Boolean = false,
    /** Carries a CONTRADICTS edge that is neither explained nor retired (§10.3 rule 4). */
    val unexplainedConflict: Boolean = false,
)

/** The §10.3 conclusion phrasing ladder. Deliberately has no flat-assertion member (rule 3). */
enum class HedgePhrase(val rendered: String) {
    VERY_LIKELY("very likely"),
    REASONABLE_TO_EXPECT("it's reasonable to expect"),
}

/**
 * The hedging computer's verdict on one evidence chain. [floorsMet] = false means the question
 * degrades to voicing row 10 (honest gap); otherwise [phrase] is the strongest wording the
 * conclusion may carry (row 9).
 */
data class HedgeVerdict(
    val floorsMet: Boolean,
    val phrase: HedgePhrase? = null,
    /** Conflicted facts removed from the chain (floors still held without them). */
    val droppedFactIds: List<String> = emptyList(),
    /** Conflicted facts kept because dropping them broke the floors — name the tension aloud. */
    val namedTensionFactIds: List<String> = emptyList(),
    /** Human-readable derivation, embedded in prompts and judge rationales. */
    val rationale: String,
) {
    /** Claim ids of the facts that remain load-bearing after any conflict drops. */
    fun survivingClaimIds(chain: List<SupportingFact>): List<String> =
        chain.filterNot { it.factId in droppedFactIds }.flatMap { it.claimIds }.distinct()
}

/**
 * The §10.3 deterministic hedging rules:
 * 1. every load-bearing fact ≥ MEDIUM band ([tierMedium]);
 * 2. evidence floor: ≥ 2 independent supporting facts, or 1 documentary-anchored;
 * 3. conclusion ceiling: max "very likely" (structural — [HedgePhrase] has no assertion);
 * 4. weakest link: all-HIGH → "very likely"; any MEDIUM → "it's reasonable to expect"; an
 *    unexplained-conflict fact is dropped when the floors survive without it, else kept with the
 *    tension named aloud (and the phrase capped at "reasonable to expect").
 */
class SituationalHedging(
    private val tierHigh: Double = 0.75,
    private val tierMedium: Double = 0.45,
) {

    fun compute(chain: List<SupportingFact>): HedgeVerdict {
        if (chain.isEmpty()) {
            return HedgeVerdict(floorsMet = false, rationale = "no supporting evidence")
        }

        val conflicted = chain.filter { it.unexplainedConflict }
        val clean = chain.filterNot { it.unexplainedConflict }

        // Rule 4 first arm: drop conflicted facts when the floors survive without them.
        if (floorsHold(clean)) {
            val phrase = weakestLink(clean)
            return HedgeVerdict(
                floorsMet = true,
                phrase = phrase,
                droppedFactIds = conflicted.map { it.factId },
                rationale =
                    buildString {
                        append(floorRationale(clean))
                        if (conflicted.isNotEmpty()) {
                            append("; dropped ${conflicted.size} conflicted fact(s)")
                        }
                        append(" → ${phrase.rendered}")
                    },
            )
        }

        // Second arm: keep the conflicted facts, name the tension, cap the phrase (rule 4).
        if (conflicted.isNotEmpty() && floorsHold(chain)) {
            return HedgeVerdict(
                floorsMet = true,
                phrase = HedgePhrase.REASONABLE_TO_EXPECT,
                namedTensionFactIds = conflicted.map { it.factId },
                rationale =
                    floorRationale(chain) +
                        "; ${conflicted.size} conflicted fact(s) kept — tension must be named" +
                        " aloud → ${HedgePhrase.REASONABLE_TO_EXPECT.rendered}",
            )
        }

        return HedgeVerdict(
            floorsMet = false,
            rationale = "floors unmet: " + floorFailure(clean.ifEmpty { chain }),
        )
    }

    /** Rules 1 + 2 over a candidate chain. */
    private fun floorsHold(facts: List<SupportingFact>): Boolean =
        facts.isNotEmpty() &&
            facts.all { it.belief >= tierMedium } &&
            (facts.count { it.independent } >= 2 || facts.any { it.anchored })

    /** Rule 4 phrasing over a conflict-free chain. */
    private fun weakestLink(facts: List<SupportingFact>): HedgePhrase =
        if (facts.all { it.belief >= tierHigh }) HedgePhrase.VERY_LIKELY
        else HedgePhrase.REASONABLE_TO_EXPECT

    private fun floorRationale(facts: List<SupportingFact>): String =
        "${facts.size} fact(s), ${facts.count { it.independent }} independent, " +
            "${facts.count { it.anchored }} anchored, min belief " +
            "%.2f".format(facts.minOf { it.belief })

    private fun floorFailure(facts: List<SupportingFact>): String =
        when {
            facts.isEmpty() -> "no usable facts"
            facts.any { it.belief < tierMedium } ->
                "load-bearing fact below MEDIUM band (%.2f)".format(facts.minOf { it.belief })
            else -> "fewer than 2 independent facts and none anchored"
        }
}

package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * The subject's engagement model (SubjectProfile LLD §2.1, group C). Declared, so it materialises
 * as a `subject-declared-engagement-model` ledger item — the exact logical type the `cat-27`
 * role-fit evidenceGates ask for.
 */
enum class EmploymentType {
    FTE,
    CONTRACT,
    EITHER;

    companion object {
        fun fromOrNull(raw: String?): EmploymentType? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * Approval state of a subject's one bespoke do-not-discuss entry (OD-9, DECIDED). The custom string
 * is **inert until [APPROVED]** — the materialiser never turns [PENDING] or [REJECTED] text into a
 * claim, so free text the subject typed can never reach Stage 4 unvetted.
 */
enum class DoNotDiscussApproval {
    PENDING,
    APPROVED,
    REJECTED;

    companion object {
        fun fromOrNull(raw: String?): DoNotDiscussApproval? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * One bespoke do-not-discuss topic the subject typed, plus the admin decision on it (OD-9). Stored
 * on the profile; only an [DoNotDiscussApproval.APPROVED] entry materialises.
 *
 * The [approver]/[at] pair is the audit trail for the decision, mirroring the manifest's
 * consent-attestation stamp — the approval *is* the safety check on what may join the evidence set,
 * so who made it has to survive.
 */
data class DoNotDiscussCustom(
    val text: String,
    val state: DoNotDiscussApproval = DoNotDiscussApproval.PENDING,
    val approver: String? = null,
    val at: Instant? = null,
) {
    /**
     * May this entry become a claim? Approval and non-blank text, checked together — the
     * materialiser asks only this, so no other caller has to remember the OD-9 rule.
     */
    val materialisable: Boolean
        get() = state == DoNotDiscussApproval.APPROVED && text.isNotBlank()
}

/**
 * The curated do-not-discuss vocabulary (OD-9, VA-145). Deliberately **code-managed**, following
 * the governance decision recorded on [ai.vishwakarma.labelling.service.ReservedHandles] (VA-90):
 * the list changes a few times a year, a careless edit silently widens what an advocate will be
 * trained to treat as a boundary, and the escape hatch for anything not on it already exists and is
 * admin-gated ([DoNotDiscussCustom]). Additions go through PR review like any other safety control.
 *
 * Each toggled key materialises as a claim recording *the fact that a boundary exists* — never the
 * substance behind it. Enforcement at serving time is the existing warm-deflect I2/I3 posture; this
 * vocabulary decides only what may go on the list.
 */
object DoNotDiscussVocabulary {

    /** One curated topic: the stored [key] and the subject-facing [label] a form renders. */
    data class Topic(val key: String, val label: String)

    val topics: List<Topic> =
        listOf(
            Topic("health", "Health and medical history"),
            Topic("mental-health", "Mental health"),
            Topic("disability", "Disability or accessibility needs"),
            Topic("family", "Family and dependants"),
            Topic("marital-status", "Marital or relationship status"),
            Topic("pregnancy", "Pregnancy or parental leave"),
            Topic("age", "Age or date of birth"),
            Topic("religion", "Religion or belief"),
            Topic("politics", "Political affiliation"),
            Topic("sexual-orientation", "Sexual orientation"),
            Topic("gender-identity", "Gender identity"),
            Topic("ethnicity", "Ethnicity, race or caste"),
            Topic("nationality", "Nationality or immigration status"),
            Topic("criminal-record", "Criminal record"),
            Topic("finances", "Personal finances or debt"),
            Topic("compensation-history", "Past compensation"),
            Topic("current-employer", "Current employer by name"),
            Topic("departure-reasons", "Reasons for leaving a past employer"),
            Topic("employment-gaps", "Gaps in employment"),
            Topic("former-colleagues", "Named former colleagues or managers"),
            Topic("union-membership", "Union or trade-body membership"),
            Topic("military-service", "Military service"),
            Topic("relocation-constraints", "Personal constraints on relocation"),
            Topic("side-projects", "Side projects or outside work"),
        )

    private val byKey: Map<String, Topic> = topics.associateBy { it.key }

    val keys: Set<String> = byKey.keys

    fun contains(key: String): Boolean = key in byKey

    fun labelOf(key: String): String? = byKey[key]?.label

    /**
     * The subset of [raw] this vocabulary recognises, de-duplicated and in vocabulary order.
     * Deliberately *silent* about unknown keys: this is the materialiser's defence in depth, run
     * long after the write-time validation that rejects them — a key retired from the vocabulary
     * after a profile was stored must stop materialising, not fail the seal.
     */
    fun known(raw: List<String>): List<String> {
        val wanted = raw.map { it.trim().lowercase() }.toSet()
        return topics.map { it.key }.filter { it in wanted }
    }
}

/**
 * The fine-grained logical ledger-item types a declared claim can answer to (LLD §3.4) — the
 * vocabulary shared by [Claim.declaredType] (what the materialiser stamps) and
 * [NotebookTemplate.requiredDeclaredTypes] (what a template's evidenceGate demands).
 *
 * These are *not* [ClaimType] values and must never become any: a new `ClaimType` would touch the
 * extractor prompt enumerations, the voicing-planner category map and the graph, for a distinction
 * only the planner needs (OD-3). The coarse [ClaimType] projection stays the outer gate; this is
 * the handle that picks the right item inside it.
 *
 * Code-managed for the same reason as [DoNotDiscussVocabulary]: an unrecognised string in a
 * template's gate must never silently gate on nothing, and [recognises] is how a caller checks.
 */
object DeclaredType {

    /** "Optimising for staff-level IC work, not management." — `cat-27` rows 02/05/09. */
    const val STATED_ASPIRATION = "stated-aspiration"

    /** A declared preference between tracks or kinds of work. */
    const val STATED_TRACK_PREFERENCE = "stated-track-preference"

    /** FTE / contract / either — `cat-27`'s engagement-model gate. */
    const val ENGAGEMENT_MODEL = "subject-declared-engagement-model"

    /** A role the subject says he is targeting. */
    const val TARGET_ROLE = "subject-declared-target-role"

    /** The seniority band the subject says he is targeting. */
    const val TARGET_SENIORITY = "subject-declared-target-seniority"

    /** Whether the subject will relocate. */
    const val RELOCATION_STANCE = "subject-declared-relocation-stance"

    /** The fact that a do-not-discuss boundary exists (never its substance). */
    const val BOUNDARY = "subject-declared-boundary"

    /**
     * `declared-contact-<kind>` — the logical type of a declared contact PII claim (§5.2). E.g.
     * `declared-contact-email`. Deliberately **outside** [all]: a contact is drawn by the
     * `sensitive` / Row-8 opt-in path, never by a notebook template's `requiredDeclaredTypes`
     * evidence gate, so [recognises] returns false for it and no template may gate on one. The
     * marker exists only so the materialiser stamps one canonical string and a reader can tell a
     * contact claim from any other declared claim.
     */
    const val CONTACT_PREFIX = "declared-contact-"

    /** The `declared-contact-<kind>` type for one [ContactKind]. */
    fun contactType(kind: ContactKind): String = CONTACT_PREFIX + kind.name.lowercase()

    /**
     * Types the `cat-27` evidenceGate prose names that **nothing in slice 2 materialises**:
     * `self-disclosed-driver`, `subject-stated-position` and `scope-preference` have no field in
     * groups C/D. They are listed so a template may gate on them and so [recognises] does not
     * report a live gate token as unknown — a gate naming one of them simply finds no item today
     * and the template records `missed`, which is the honest outcome (LLD §3.4,
     * `cat-27…md:51,120,143`).
     */
    const val SELF_DISCLOSED_DRIVER = "self-disclosed-driver"

    const val SUBJECT_STATED_POSITION = "subject-stated-position"

    const val SCOPE_PREFERENCE = "scope-preference"

    /** Everything a template's gate may legally name. */
    val all: Set<String> =
        setOf(
            STATED_ASPIRATION,
            STATED_TRACK_PREFERENCE,
            ENGAGEMENT_MODEL,
            TARGET_ROLE,
            TARGET_SENIORITY,
            RELOCATION_STANCE,
            BOUNDARY,
            SELF_DISCLOSED_DRIVER,
            SUBJECT_STATED_POSITION,
            SCOPE_PREFERENCE,
        )

    /** The subset [all] actually materialises today — the rest have no group-C/D source. */
    val materialised: Set<String> =
        setOf(
            STATED_ASPIRATION,
            STATED_TRACK_PREFERENCE,
            ENGAGEMENT_MODEL,
            TARGET_ROLE,
            TARGET_SENIORITY,
            RELOCATION_STANCE,
            BOUNDARY,
        )

    fun recognises(raw: String): Boolean = raw.trim().lowercase() in all

    /**
     * The recognised declared types named inside an `evidenceGate` prose line, in vocabulary order.
     *
     * The gates are authored in one machine-readable idiom — `a ledger item of type
     * {stated-aspiration | stated-track-preference | self-disclosed-driver}` — so the fine gate can
     * be derived instead of hand-copied across 439 rows. Only tokens in [all] survive: a gate
     * naming `{scope-record}` or `{stated-req-requirement}` (extracted-evidence descriptors, not
     * declarations) yields **nothing** and therefore gates on nothing, which is what keeps this
     * from silently disarming templates it was never meant to touch.
     *
     * Nothing calls this implicitly — [NotebookTemplate.requiredDeclaredTypes] is an explicit
     * stored field, and this is the helper an authoring/loader pass uses to fill it.
     */
    fun fromEvidenceGate(prose: String): List<String> {
        if (prose.isBlank()) return emptyList()
        val named =
            BRACED.findAll(prose)
                .flatMap { it.groupValues[1].split('|', ',').asSequence() }
                .map { it.trim().lowercase() }
                .filter { it in all }
                .toSet()
        return all.filter { it in named }
    }

    /** `{a | b | c}` — the braced alternation every authored gate uses for its type list. */
    private val BRACED = Regex("""\{([^{}]*)}""")
}

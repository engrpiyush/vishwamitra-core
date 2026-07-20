package ai.vishwakarma.labelling.domain

import java.time.Instant
import java.time.LocalDate

/**
 * The kind of Claim an example exercises — the Neo spine object's type. An advocate answer is built
 * around one of these.
 */
enum class ClaimType {
    IDENTITY,
    EPISODE,
    VALUE,
    WEAKNESS,
    SKILL;

    companion object {
        fun fromOrNull(raw: String?): ClaimType? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** How well-corroborated the underlying Claim is; drives answer confidence (assertive → hedged). */
enum class AuthenticityTier {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromOrNull(raw: String?): AuthenticityTier? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * Whether the source asserts a Claim outright ([STATED]) or the extractor inferred it from
 * demonstrated behaviour ([INFERRED] — the reduced-confidence demonstration-inference mode). A
 * structural marker kept independent of [Claim.extractionConfidence] (which is per-run noise): it
 * drives the Stage 2 review action (STATED retractable, INFERRED contestable) and Stage 3
 * weighting.
 */
enum class ClaimBasis {
    STATED,
    INFERRED;

    companion object {
        fun fromOrNull(raw: String?): ClaimBasis? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * Where a claim entered the ledger (SubjectProfile LLD §3.2). Deliberately **orthogonal** to
 * [ClaimBasis]: a subject-declared aspiration is definitionally *stated*, so it keeps `claimBasis =
 * STATED`, and a third `DECLARED` basis would silently mis-fire three code paths — contest is
 * allowed only for INFERRED ([ai.vishwakarma.labelling.service.ClaimReviewService]), the scorer's
 * `inferredPenalty` keys on INFERRED ([Scorer.prior]), and `needsDecision()` special-cases
 * INFERRED.
 *
 * Null on a stored claim means [EXTRACTED] — every pre-feature claim reads back as extracted with
 * no migration, which is why every consumer must go through [Claim.declared] rather than comparing
 * the nullable field itself.
 */
enum class ClaimOrigin {
    /** Stage 2 read it out of an [Asset]. The default for everything written before the feature. */
    EXTRACTED,
    /**
     * The subject typed it on the authenticated profile surface and it was materialised at the
     * Stage 1→2 seal ([ai.vishwakarma.labelling.service.ProfileClaimMaterialiser]). Declared is not
     * guessed: the item is in the record because a real person put it there, which is what lets the
     * evidence gate (PRECEDENCE I1) admit it without the model ever inferring it.
     */
    SUBJECT_DECLARED;

    companion object {
        fun fromOrNull(raw: String?): ClaimOrigin? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * The pipeline's spine object: one atomic, traceable evidence unit about a subject, extracted in
 * Stage 2 from an [Asset]'s transcript/text. Written by Stage 2, scored by Stage 3
 * ([authenticityScore] stays null until then), read by Stage 4 for training-pair synthesis.
 * Provenance ([assetId], [speaker], [mediaStart]/[mediaEnd], [sourceExcerpt]) keeps every claim
 * traceable back to the exact moment in the source material it came from.
 */
data class Claim(
    val id: String,
    val subjectId: String,
    val assetId: String,
    val claimType: ClaimType,
    /** The atomic claim, e.g. "Led the payments-platform migration in 2019". */
    val text: String,
    /** Diarization label as heard in the source (e.g. "Speaker 1"). */
    val speaker: String? = null,
    /**
     * Resolved role of [speaker] in a multi-speaker asset (§12.4): SUBJECT (self-report) vs
     * ENDORSER (third-party testimony) vs INTERVIEWER/OTHER. Null for single-speaker / non-diarized
     * claims. Denormalised like [relationship]/[sourceClass] so a claim is a self-contained
     * evidence unit — this is *why* two claims from the same asset can carry different
     * [relationship]/[sourceClass].
     */
    val speakerRole: SpeakerRole? = null,
    /** Seconds into the source A/V where the claim starts/ends. */
    val mediaStart: Double? = null,
    val mediaEnd: Double? = null,
    /** Transcript span the claim was drawn from. */
    val sourceExcerpt: String? = null,
    /** When the documented event happened (LLM-extracted; tolerant parse). */
    val claimedDate: LocalDate? = null,
    /** Seeded from the source asset's [Asset.authenticityPrior]; Stage 3 refines. */
    val authenticityTier: AuthenticityTier? = null,
    /**
     * Denormalised from the source [Asset] so a claim is a self-contained evidence unit for Stage
     * 3's content-type × relationship weighting (no re-join to the asset).
     */
    val sourceClass: SourceClass? = null,
    val relationship: Relationship? = null,
    /** Final trust score — null until Stage 3 scores it. */
    val authenticityScore: Double? = null,
    /**
     * The §3.2 signal vector behind [authenticityScore] — {prior, support, conflict, independence,
     * recency, evidenceMass, scoreBare}. Written only at Stage 3 publish (LLD §11.11); Stage 4
     * reads this ledger copy, never Neo4j.
     */
    val authenticitySignals: Map<String, Double>? = null,
    /** The Stage 3 run whose frozen paramsSnapshot produced the published score (§9.6). */
    val scoreRunId: String? = null,
    val scoredAt: Instant? = null,
    /**
     * LLM self-reported extraction *fidelity* (0..1) — how cleanly the source was read, NOT
     * evidential weight; uniform per run in practice, so Stage 3 must not weight on it.
     */
    val extractionConfidence: Double? = null,
    /** STATED (source asserts it) vs INFERRED (extractor inferred from demonstration). */
    val claimBasis: ClaimBasis? = null,
    /** Contact/identity PII — captured but held for opt-in approval by the §12.6 review layer. */
    val sensitive: Boolean = false,
    /**
     * How favorably the claim reflects on the subject (0..1): 0 = strongly unfavorable, 0.5 =
     * neutral/factual, 1 = strongly favorable. LLM-emitted *valence toward the subject* — a Stage-2
     * marker independent of [authenticityTier]/[authenticityScore] (a HIGH-authenticity fact can be
     * very unfavorable, e.g. a verified low grade). Drives the §12.6 review gate: unfavorable
     * claims (below `app.stage2.favorability-threshold`) are surfaced for approve/sidecar. Null =
     * not scored (legacy / pre-marker claims) — treated as review-required, never silently
     * auto-approved.
     */
    val favorability: Double? = null,
    /** [ContentType] name whose instruction block extracted this claim. */
    val extractionPromptId: String? = null,
    /** [ExtractionPrompt.version] used (0 = built-in default); Stage 3 compares like with like. */
    val extractionPromptVersion: Int? = null,
    /** Short hash of the exact instruction block used. */
    val extractionPromptHash: String? = null,
    /**
     * Which publish contract last wrote this claim's score block: null/1 = the legacy 5-field
     * vector; 2 = the Stage 4 contract (fact stamp + entity mentions + edge counts + attestor,
     * §11.11 v2). A reopen → re-publish upgrades every scored claim in one tick.
     */
    val publishContractVersion: Int? = null,
    /**
     * DERIVED — the §11.9 bare score (no explanation effects) surfaced beside [authenticityScore]
     * for direct ledger consumption; also inside [authenticitySignals].
     */
    val authenticityScoreBare: Double? = null,
    /** The claim's fact context, frozen at publish. Null until a v2 publish. */
    val factStamp: PublishedFactStamp? = null,
    /** Resolved entity mentions (stated surface + derived canon), frozen at publish. */
    val entityMentions: List<PublishedEntityMention>? = null,
    /**
     * DERIVED counts over the fact's evidence edges — keys `corroborates`, `contradicts`,
     * `contradictsExplained`, `contradictsConfirmed`. Full edge detail (rationale, votes, review
     * status) lives on the fact's `subject_facts` doc, never here (doc-size guard).
     */
    val edgeCounts: Map<String, Int>? = null,
    /** Whose word the claim rests on (§11.2), frozen at publish. */
    val attestor: PublishedAttestor? = null,
    /**
     * How the claim entered the ledger (§3.2). Null ≡ [ClaimOrigin.EXTRACTED] — read it through
     * [declared], never by comparing this field, so back-compat is one decision in one place.
     */
    val origin: ClaimOrigin? = null,
    /**
     * The fine-grained *logical* ledger-item type a declared claim answers to —
     * `stated-aspiration`, `subject-declared-engagement-model`, … ([DeclaredType]). Null for
     * extracted claims.
     *
     * It exists because the enforceable evidence gate is coarse:
     * [NotebookTemplate.requiredClaimTypes] projects onto the 5-value [ClaimType], while the
     * `cat-27` evidenceGate prose keys on logical types that have no home in that enum (§3.4).
     * Rather than grow [ClaimType] — which would touch the extractor prompt enumerations, the
     * planner category map and the graph — the logical type rides here and
     * [NotebookTemplate.requiredDeclaredTypes] selects on it, with the coarse projection still the
     * outer gate (OD-3).
     */
    val declaredType: String? = null,
    val createdAt: Instant? = null,
    val stage2ProcessedAt: Instant? = null,
) {
    /**
     * Did the subject declare this on the authenticated surface (§3.2)? The single tolerant-null
     * read of [origin]: a stored claim with no origin is extracted, so every pre-feature claim
     * keeps exactly its old behaviour and nothing needs backfilling.
     */
    val declared: Boolean
        get() = origin == ClaimOrigin.SUBJECT_DECLARED
}

/**
 * A claim's fact context as the Stage 3 publish freezes it onto the ledger (contract v2). All
 * fields are DERIVED by assembly/scoring except [label] — the exemplar member claim's STATED text;
 * [exemplarClaimId] says which claim that is. Interval strings are partial ISO (`yyyy[-MM[-dd]]`)
 * at [datePrecision]; the STATED member dates behind the derived interval sit on the fact's
 * `subject_facts` doc (`statedDates`). Authoritative field-by-field legend:
 * [ai.vishwakarma.labelling.persistence.PublishContract.FIELD_PROVENANCE].
 */
data class PublishedFactStamp(
    val factId: String,
    val label: String,
    val exemplarClaimId: String? = null,
    /** STATE / EVENT / TIMELESS (§11.7). */
    val kind: String? = null,
    /** STATE facts: the exclusive timeline lane (EMPLOYER, …). */
    val slot: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
    /** YEAR / MONTH / DAY / NONE — coarsest precision among the member dates used. */
    val datePrecision: String? = null,
    /** Any member is DOCUMENTARY-sourced (§11.7). */
    val anchored: Boolean = false,
    val belief: Double? = null,
    val beliefBare: Double? = null,
    val memberCount: Int = 1,
)

/** One resolved mention: the STATED [surface] beside the DERIVED canonical identity (§11.3). */
data class PublishedEntityMention(
    val surface: String? = null,
    val canonicalName: String,
    val entityType: String? = null,
    /** DERIVED — a §11.3 near-miss adoption still awaiting human review. */
    val provisional: Boolean = false,
)

/** The attestor behind the claim (§11.2): [name] STATED; [kind]/[trust] DERIVED. */
data class PublishedAttestor(
    val key: String? = null,
    val name: String? = null,
    val kind: String? = null,
    val trust: Double? = null,
)

/**
 * The source-provenance a claim inherits from its speaker's resolved role (§12.4 per-claim
 * re-weight). Returned by [claimProvenance]; [keep] = false means the span is not evidence about
 * the subject (an interviewer's question) and the claim is dropped at extraction.
 */
data class ClaimProvenance(
    val sourceClass: SourceClass?,
    val relationship: Relationship?,
    val authenticityTier: AuthenticityTier?,
    val keep: Boolean,
)

/**
 * Resolve a claim's provenance from its speaker [assignment] (§12.4). With no binding ([assignment]
 * null — single-speaker / non-diarized) the claim inherits the [asset]'s uniform provenance, the
 * pre-§12.4 behavior. With a binding:
 * - [SpeakerRole.SUBJECT] → self-report (SELF / SELF / LOW), *regardless* of the asset's
 *   endorsement classification — this is the mis-attribution fix (a subject's self-praise no longer
 *   inherits endorser weight).
 * - [SpeakerRole.ENDORSER] → third-party testimony (ENDORSEMENT), refined by the endorser's
 *   relationship via [endorsementPrior]; falls back to the asset's relationship when the assignment
 *   carries none.
 * - [SpeakerRole.INTERVIEWER] / [SpeakerRole.OTHER] → [keep] = false (dropped).
 */
fun claimProvenance(assignment: SpeakerAssignment?, asset: Asset): ClaimProvenance =
    when (assignment?.role) {
        null ->
            ClaimProvenance(
                asset.sourceClass,
                asset.relationship,
                asset.authenticityPrior,
                keep = true,
            )
        SpeakerRole.SUBJECT ->
            ClaimProvenance(SourceClass.SELF, Relationship.SELF, AuthenticityTier.LOW, keep = true)
        SpeakerRole.ENDORSER -> {
            val rel = assignment.relationship ?: asset.relationship
            ClaimProvenance(SourceClass.ENDORSEMENT, rel, endorsementPrior(rel), keep = true)
        }
        SpeakerRole.INTERVIEWER,
        SpeakerRole.OTHER -> ClaimProvenance(null, null, null, keep = false)
    }

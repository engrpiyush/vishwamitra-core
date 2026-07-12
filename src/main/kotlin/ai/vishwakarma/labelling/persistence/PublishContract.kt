package ai.vishwakarma.labelling.persistence

/**
 * The Stage 3 → Stage 4 publish contract (LLD §11.11, v2): everything tuning may need is
 * denormalised onto the Firestore ledger at publish, and every value is classifiable as **what the
 * sources said** versus **what the pipeline computed** without reading Stage 3 code.
 *
 * Convention (frozen verbatim into `subject_scores.publishContract` at every publish):
 * - **STATED** — verbatim from the subject's source material (claim text, a mention's surface form,
 *   a claim's own date, an attestor's name as heard/read).
 * - **DERIVED** — computed by the pipeline: LLM classification, clustering, interval min/max,
 *   ensemble verdicts, trust propagation, scores. Derived numbers always ship *beside* their stated
 *   inputs (e.g. `subject_facts.validFrom` beside `subject_facts.statedDates[].date`).
 * - **HUMAN** — a reviewer's or the subject's recorded decision (sidecar justifications,
 *   contradiction-queue actions, PII opt-ins).
 *
 * Consumer rules:
 * 1. An artifact (claim score block, `subject_facts` doc) is **current** iff its `scoreRunId`
 *    equals `subject_scores.scoreRunId` — anything else is a leftover from an earlier publish.
 * 2. A claim without `publishContractVersion >= 2` was last published under the legacy 5-field
 *    contract; reopen → re-publish upgrades the whole subject in one tick.
 * 3. Stage 4 reads **only** Firestore (`claims`, `subject_facts`, `subject_scores`,
 *    `claim_reviews`) — never Neo4j (the two-store doctrine).
 */
object PublishContract {

    /** Bump when the published shape changes; consumers compare per-doc stamps against this. */
    const val VERSION = 2

    /** Collection holding the per-fact detail (intervals, timeline chains, full edges). */
    const val FACTS_COLLECTION = "subject_facts"

    const val CONSUMER_RULE =
        "artifacts are consistent iff their scoreRunId equals subject_scores.scoreRunId"

    /**
     * The machine-readable legend: dotted ledger path → STATED | DERIVED | HUMAN. `[]` marks array
     * elements; a trailing `*` covers every key under the prefix. Resolve a concrete path with
     * [provenanceOf]. Kept exhaustive over everything the publish tick writes plus the
     * tuning-relevant Stage 2 fields — the legend-completeness test pins that property.
     */
    val FIELD_PROVENANCE: Map<String, String> =
        mapOf(
            // -- claims: Stage 2 extraction fields Stage 4 conditions on ------------------
            "claims.text" to "STATED",
            "claims.sourceExcerpt" to "STATED",
            "claims.claimedDate" to "STATED",
            "claims.speaker" to "STATED",
            "claims.claimType" to "DERIVED",
            "claims.claimBasis" to "DERIVED",
            "claims.speakerRole" to "DERIVED",
            "claims.sourceClass" to "DERIVED",
            "claims.relationship" to "DERIVED",
            "claims.sensitive" to "DERIVED",
            "claims.favorability" to "DERIVED",
            // -- claims: the published score block (v1 fields) ----------------------------
            "claims.authenticityScore" to "DERIVED",
            "claims.authenticityTier" to "DERIVED",
            "claims.authenticitySignals.*" to "DERIVED",
            "claims.scoreRunId" to "DERIVED",
            "claims.scoredAt" to "DERIVED",
            // -- claims: the v2 additions --------------------------------------------------
            "claims.publishContractVersion" to "DERIVED",
            "claims.authenticityScoreBare" to "DERIVED",
            "claims.factStamp.factId" to "DERIVED",
            "claims.factStamp.label" to "STATED",
            "claims.factStamp.exemplarClaimId" to "DERIVED",
            "claims.factStamp.kind" to "DERIVED",
            "claims.factStamp.slot" to "DERIVED",
            "claims.factStamp.validFrom" to "DERIVED",
            "claims.factStamp.validTo" to "DERIVED",
            "claims.factStamp.datePrecision" to "DERIVED",
            "claims.factStamp.anchored" to "DERIVED",
            "claims.factStamp.belief" to "DERIVED",
            "claims.factStamp.beliefBare" to "DERIVED",
            "claims.factStamp.memberCount" to "DERIVED",
            "claims.entityMentions[].surface" to "STATED",
            "claims.entityMentions[].canonicalName" to "DERIVED",
            "claims.entityMentions[].entityType" to "DERIVED",
            "claims.entityMentions[].provisional" to "DERIVED",
            "claims.edgeCounts.*" to "DERIVED",
            "claims.attestor.key" to "DERIVED",
            "claims.attestor.name" to "STATED",
            "claims.attestor.kind" to "DERIVED",
            "claims.attestor.trust" to "DERIVED",
            // -- subject_facts -------------------------------------------------------------
            "subject_facts.subjectId" to "DERIVED",
            "subject_facts.factId" to "DERIVED",
            "subject_facts.scoreRunId" to "DERIVED",
            "subject_facts.publishedAt" to "DERIVED",
            "subject_facts.publishContractVersion" to "DERIVED",
            "subject_facts.label" to "STATED",
            "subject_facts.exemplarClaimId" to "DERIVED",
            "subject_facts.factKind" to "DERIVED",
            "subject_facts.slot" to "DERIVED",
            "subject_facts.validFrom" to "DERIVED",
            "subject_facts.validTo" to "DERIVED",
            "subject_facts.datePrecision" to "DERIVED",
            "subject_facts.statedDates[].claimId" to "DERIVED",
            "subject_facts.statedDates[].date" to "STATED",
            "subject_facts.anchored" to "DERIVED",
            "subject_facts.belief" to "DERIVED",
            "subject_facts.beliefBare" to "DERIVED",
            "subject_facts.memberClaimIds" to "DERIVED",
            "subject_facts.timelinePrev[].factId" to "DERIVED",
            "subject_facts.timelinePrev[].slot" to "DERIVED",
            "subject_facts.timelinePrev[].gapDays" to "DERIVED",
            "subject_facts.timelineNext[].factId" to "DERIVED",
            "subject_facts.timelineNext[].slot" to "DERIVED",
            "subject_facts.timelineNext[].gapDays" to "DERIVED",
            "subject_facts.edges[].relation" to "DERIVED",
            "subject_facts.edges[].otherFactId" to "DERIVED",
            "subject_facts.edges[].otherLabel" to "STATED",
            "subject_facts.edges[].otherExemplarClaimId" to "DERIVED",
            "subject_facts.edges[].confidence" to "DERIVED",
            "subject_facts.edges[].votes" to "DERIVED",
            "subject_facts.edges[].rationale" to "DERIVED",
            "subject_facts.edges[].contributingPairs" to "DERIVED",
            "subject_facts.edges[].temporalNote" to "DERIVED",
            "subject_facts.edges[].temporalOverlap" to "DERIVED",
            "subject_facts.edges[].explained" to "DERIVED",
            "subject_facts.edges[].ctxRelation" to "DERIVED",
            "subject_facts.edges[].ctxConfidence" to "DERIVED",
            // PROPOSED is machine-set at assembly; every other value is a queue action.
            "subject_facts.edges[].reviewStatus" to "HUMAN",
            "subject_facts.edges[].viaEntities" to "DERIVED",
            // -- subject_scores ------------------------------------------------------------
            "subject_scores.*" to "DERIVED",
            // -- claim_reviews: the sidecar layer (written by review, read by Stage 4) ------
            "claim_reviews.decision" to "HUMAN",
            "claim_reviews.justification" to "HUMAN",
            "claim_reviews.corroboratingClaimIds" to "HUMAN",
            "claim_reviews.piiChoice" to "HUMAN",
        )

    /**
     * Resolve a concrete dotted path against the legend: exact key first, then the nearest ancestor
     * wildcard (`a.b.c` falls back to `a.b.*`, then `a.*`). Null = uncontracted field.
     */
    fun provenanceOf(path: String): String? {
        FIELD_PROVENANCE[path]?.let {
            return it
        }
        var prefix = path
        while (true) {
            val cut = prefix.lastIndexOf('.')
            if (cut < 0) return null
            prefix = prefix.substring(0, cut)
            FIELD_PROVENANCE["$prefix.*"]?.let {
                return it
            }
        }
    }

    /** The block frozen into `subject_scores.publishContract` at every publish. */
    fun asMap(): Map<String, Any?> =
        mapOf(
            "version" to VERSION,
            "factsCollection" to FACTS_COLLECTION,
            "consumerRule" to CONSUMER_RULE,
            "fieldProvenance" to FIELD_PROVENANCE,
        )
}

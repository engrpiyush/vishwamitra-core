package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SpeakerAssignment
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.service.ApprovedClaim

/** `:Claim` node row (LLD §9.2) — identity/provenance only; scores and embedding arrive later. */
data class ClaimRow(
    val claimId: String,
    val assetId: String,
    val type: String?,
    val text: String,
    val basis: String?,
    val sourceClass: String?,
    val relationship: String?,
    val speakerRole: String?,
    /** The Stage 1/2 prior tier (`authenticityTier` at sync time) — the scorer's seed. */
    val tierSeed: String?,
    val favorability: Double?,
    /** ISO yyyy-MM-dd; string-typed in the graph (ISO compares lexically). */
    val claimedDate: String?,
    val sensitive: Boolean,
    /** Derived dependence/trust anchor — see [deriveAttestor]. */
    val attestorKey: String,
    /**
     * `EXTRACTED` / `SUBJECT_DECLARED` (SubjectProfile §3.2), null on pre-feature claims. It rides
     * into the graph beside `basis`/`sourceClass` because the scorer's declared branch and the
     * publish tier both read it, and a second source of truth for the same provenance is exactly
     * how the ledger and the graph drift apart.
     */
    val origin: String? = null,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "claimId" to claimId,
            "assetId" to assetId,
            "type" to type,
            "text" to text,
            "basis" to basis,
            "sourceClass" to sourceClass,
            "relationship" to relationship,
            "speakerRole" to speakerRole,
            "tierSeed" to tierSeed,
            "favorability" to favorability,
            "claimedDate" to claimedDate,
            "sensitive" to sensitive,
            "attestorKey" to attestorKey,
            "origin" to origin,
        )
}

/** `:Source` node row — the asset as provenance grouping (first dependence key, LLD §9.2). */
data class SourceRow(
    val assetId: String,
    val contentType: String?,
    val sourceClass: String?,
    val relationship: String?,
    val checksum: String?,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "assetId" to assetId,
            "contentType" to contentType,
            "sourceClass" to sourceClass,
            "relationship" to relationship,
            "checksum" to checksum,
        )
}

/** `:Attestor` node row — GLOBAL trust layer: identity + prior, no subjectId, no claim text. */
data class AttestorRow(
    val attestorKey: String,
    /** SUBJECT / ENDORSER / ISSUER (LLD §9.2). */
    val kind: String,
    val relationship: String?,
    val name: String?,
    val trustPrior: Double,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "attestorKey" to attestorKey,
            "kind" to kind,
            "relationship" to relationship,
            "name" to name,
            "trustPrior" to trustPrior,
        )
}

/** `:Explanation` node row — the §12.6 SIDECARED justification (context, never evidence). */
data class ExplanationRow(
    /** = the reviewed claimId (LLD §9.2). */
    val explanationId: String,
    val claimId: String,
    val text: String,
    val author: String?,
    /** ISO instant string. */
    val createdAt: String?,
    /** CITES targets — pre-filtered to claims that are themselves in the approved set. */
    val cites: List<String>,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "explanationId" to explanationId,
            "claimId" to claimId,
            "text" to text,
            "author" to author,
            "createdAt" to createdAt,
            "cites" to cites,
        )
}

/** Everything one SYNC tick merges (LLD §11.2), built purely by [buildEvidenceProjection]. */
data class EvidenceProjection(
    val subjectId: String,
    val claims: List<ClaimRow>,
    val sources: List<SourceRow>,
    val attestors: List<AttestorRow>,
    val explanations: List<ExplanationRow>,
    /**
     * `corroboratingClaimIds` entries that pointed at claims NOT in the approved set (rejected /
     * PII-hidden) — dropped so no orphan `:Claim` node without subjectId can be MERGE-created (that
     * would trip the §21 A.4 layer guard).
     */
    val citationsDropped: Int,
)

/** The attestor a claim's word rests on, plus the row describing it. */
private data class DerivedAttestor(val key: String, val row: AttestorRow)

/**
 * Build the §11.2 graph projection from the reviewed claim set. Pure function — all Firestore reads
 * happen in the caller (Stage3Service SYNC), all graph writes in [Stage3GraphRepository]; this is
 * the unit-testable middle.
 *
 * [speakerBindings] is assetId → (diarization label → assignment) from the subject's Stage 2 jobs
 * (§12.4): an ENDORSER claim whose speaker binding carries an operator/LLM-captured `name` gets a
 * name-keyed attestor (the same person across assets — and, because attestors are global, across
 * subjects — collapses into one trust node); unnamed voices key per asset×speaker. [reviews]
 * (claimId → row) supplies the sidecar authorship stamps.
 */
fun buildEvidenceProjection(
    subjectId: String,
    subjectName: String?,
    approved: List<ApprovedClaim>,
    assets: List<Asset>,
    speakerBindings: Map<String, Map<String, SpeakerAssignment>>,
    reviews: Map<String, ClaimReview> = emptyMap(),
): EvidenceProjection {
    val assetsById = assets.associateBy { it.id }
    val approvedIds = approved.map { it.claim.id }.toSet()

    val attestors = linkedMapOf<String, AttestorRow>()
    val claims =
        approved.map { (claim, _, _) ->
            val binding = speakerBindings[claim.assetId]?.get(claim.speaker)
            val attestor = deriveAttestor(subjectId, subjectName, claim, binding)
            attestors.putIfAbsent(attestor.key, attestor.row)
            ClaimRow(
                claimId = claim.id,
                assetId = claim.assetId,
                type = claim.claimType.name,
                text = claim.text,
                basis = claim.claimBasis?.name,
                sourceClass = claim.sourceClass?.name,
                relationship = claim.relationship?.name,
                speakerRole = claim.speakerRole?.name,
                tierSeed = claim.authenticityTier?.name,
                favorability = claim.favorability,
                claimedDate = claim.claimedDate?.toString(),
                sensitive = claim.sensitive,
                attestorKey = attestor.key,
                origin = claim.origin?.name,
            )
        }

    val sources =
        claims
            .map { it.assetId }
            .distinct()
            .map { assetId ->
                val asset = assetsById[assetId]
                SourceRow(
                    assetId = assetId,
                    contentType = asset?.contentType?.name,
                    sourceClass = asset?.sourceClass?.name,
                    relationship = asset?.relationship?.name,
                    checksum = asset?.checksum,
                )
            }

    var citationsDropped = 0
    val explanations =
        approved
            .filter { !it.justification.isNullOrBlank() }
            .map { approvedClaim ->
                val cites = approvedClaim.corroboratingClaimIds.filter { it in approvedIds }
                citationsDropped += approvedClaim.corroboratingClaimIds.size - cites.size
                val review = reviews[approvedClaim.claim.id]
                ExplanationRow(
                    explanationId = approvedClaim.claim.id,
                    claimId = approvedClaim.claim.id,
                    text = approvedClaim.justification!!.trim(),
                    author = review?.reviewedBy,
                    createdAt = review?.reviewedAt?.toString(),
                    cites = cites,
                )
            }

    return EvidenceProjection(
        subjectId = subjectId,
        claims = claims,
        sources = sources,
        attestors = attestors.values.toList(),
        explanations = explanations,
        citationsDropped = citationsDropped,
    )
}

/**
 * Whose word does this claim rest on (LLD §11.2)?
 * - SELF-class (or SUBJECT-speaker) claims → the subject's single SUBJECT attestor.
 * - DOCUMENTARY claims → an ISSUER attestor. v1 keys per asset (`issuer:asset:<assetId>`); the
 *   upgrade to issuer-entity keys once §11.3 resolves one is the LLD §18.2 open question 2.
 * - Everything else (endorsements, public profiles, event captures) → an ENDORSER attestor:
 *   name-keyed when the §12.4 binding captured a name, else per asset×speaker, else per asset.
 */
private fun deriveAttestor(
    subjectId: String,
    subjectName: String?,
    claim: Claim,
    binding: SpeakerAssignment?,
): DerivedAttestor {
    val relationship = binding?.relationship ?: claim.relationship
    return when {
        // Checked before the SELF arm: a self-submitted certificate still speaks with the
        // issuer's voice — relationship=SELF records who uploaded it, not who attests it.
        claim.sourceClass == SourceClass.DOCUMENTARY ->
            DerivedAttestor(
                key = "issuer:asset:${claim.assetId}",
                row =
                    AttestorRow(
                        attestorKey = "issuer:asset:${claim.assetId}",
                        kind = "ISSUER",
                        relationship = claim.relationship?.name,
                        name = null,
                        trustPrior = TRUST_PRIOR_ISSUER,
                    ),
            )
        claim.speakerRole == SpeakerRole.SUBJECT ||
            claim.sourceClass == SourceClass.SELF ||
            claim.relationship == Relationship.SELF ->
            DerivedAttestor(
                key = "subject:$subjectId",
                row =
                    AttestorRow(
                        attestorKey = "subject:$subjectId",
                        kind = "SUBJECT",
                        relationship = Relationship.SELF.name,
                        name = subjectName,
                        trustPrior = TRUST_PRIOR_SUBJECT,
                    ),
            )
        else -> {
            val name = binding?.name?.trim()?.takeIf { it.isNotBlank() }
            val key =
                when {
                    name != null -> "endorser:name:${normalizeName(name)}"
                    claim.speaker != null -> "endorser:asset:${claim.assetId}:${claim.speaker}"
                    else -> "endorser:asset:${claim.assetId}"
                }
            DerivedAttestor(
                key = key,
                row =
                    AttestorRow(
                        attestorKey = key,
                        kind = "ENDORSER",
                        relationship = relationship?.name,
                        name = name,
                        trustPrior = endorserTrustPrior(relationship),
                    ),
            )
        }
    }
}

/** Collapse case/whitespace so "R. Mehta" and "r. mehta" are one global trust node. */
private fun normalizeName(name: String): String =
    name.lowercase().replace(Regex("\\s+"), " ").trim()

/**
 * Trust priors T₀ (LLD §11.8 step 3 shrinks toward these). Principled, not fitted — first-subject
 * calibration is LLD §18.2 open question 4. Alignment: the subject starts neutral (the §11.8 worked
 * example's 0.50); issuers start near the DOCUMENTARY tier seed; endorsers follow the
 * `endorsementPrior` relationship ladder.
 */
internal const val TRUST_PRIOR_SUBJECT = 0.50
internal const val TRUST_PRIOR_ISSUER = 0.85

internal fun endorserTrustPrior(relationship: Relationship?): Double =
    when (relationship) {
        Relationship.EXPERT -> 0.80
        Relationship.MANAGER,
        Relationship.MENTOR -> 0.65
        Relationship.PEER,
        Relationship.CLIENT -> 0.60
        Relationship.INSTITUTION,
        Relationship.PRESS -> 0.70
        else -> 0.50
    }

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimOrigin
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContactKind
import ai.vishwakarma.labelling.domain.DeclaredType
import ai.vishwakarma.labelling.domain.DoNotDiscussVocabulary
import ai.vishwakarma.labelling.domain.EmploymentType
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.ResolvedSubjectProfile
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SpeakerRole
import ai.vishwakarma.labelling.domain.SubjectProfileDefaults
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.SubjectProfileRepository
import java.security.MessageDigest
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** What one materialisation pass did — logged at the seal and asserted in tests. */
data class MaterialisedClaims(
    val created: List<Claim> = emptyList(),
    val kept: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
) {
    val touched: Int
        get() = created.size + deleted.size
}

/**
 * Turns the profile's declared groups **C** (candidacy / targeting), **D** (declared narrative) and
 * **E** (contact / PII) into provenance-tagged claims (SubjectProfile LLD §3.3, §3.4, §3.6, §5.2),
 * so a typed aspiration — or a contact the subject chose to share — reaches Stage 4 through the
 * ordinary evidence path instead of by extraction luck.
 *
 * Run **at the Stage 1→2 seal** ([IntakeService.sealManifest]), before Stage 2 begins consuming, so
 * declared claims freeze with the corpus exactly like extracted ones and are present before the
 * review-submit gate. This is a net-new, non-Stage-2 writer into `claims` — today Stage 2 is the
 * only other one.
 *
 * Four decisions are load-bearing and each has a failure it prevents:
 * 1. **`assetId = `[DECLARED_ASSET_PREFIX]`<subjectId>`** — a synthetic sentinel no `Stage2Job`
 *    will ever target, so Stage 2's replace-by-asset and purge-by-asset paths can never delete a
 *    declaration. `assetId` is non-null on [Claim] and the Stage-2 lifecycle deletes *by asset*, so
 *    parking declarations on a real asset id would make them collateral damage of a re-extract.
 * 2. **Deterministic ids** ([idOf]) — a seal → unseal → re-seal cycle converges instead of
 *    duplicating, and a declaration that survived an edit keeps its review row and its score.
 * 3. **`claimBasis = STATED`, `origin = SUBJECT_DECLARED`** — orthogonal markers (§3.2). The basis
 *    is honest (the subject did state it); the origin is what makes the provenance auditable.
 * 4. **Deliberate `favorability`** — see [FAVORABILITY_NEUTRAL].
 *
 * **Group E rides the *existing* O7 machinery, not a parallel path (§5.2).** A shareable contact
 * becomes a `sensitive = true` declared claim **and** a companion [ClaimReview] row carrying
 * `piiChoice = INCLUDE` — because the downstream gate keys the opt-in off the *review*, not the
 * claim (`ClaimReviewService.approvedForDownstream:219`, `EvidencedClaim.piiOptedIn`). The
 * per-field `shareable` flag *is* that opt-in (§5.3); the subject already consented at profile
 * entry, so the materialiser writes the review the S6 screen would otherwise capture. A
 * non-shareable contact is **never materialised**, so it stays under the trained REFUSE posture and
 * never reaches the planner (F3). Un-sharing later removes the claim *and* its review on the next
 * seal (content-derived ids, §5.4 — a training-time change, never a live toggle).
 *
 * The whole pass is a no-op while `app.stage4.profile-enabled` is down, so a flag-off world stays
 * byte-for-byte pre-feature: no claims, no reviews, no score change, nothing to roll back.
 */
@Service
class ProfileClaimMaterialiser(
    private val config: StageConfigService,
    private val profiles: SubjectProfileRepository,
    private val claims: ClaimRepository,
    private val reviews: ClaimReviewRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Reconcile the subject's declared claims with the profile as it stands right now: create what
     * is missing, keep what already matches, delete what the subject removed.
     *
     * Reconciliation (rather than plain insert) is what makes a re-seal safe *and* honest — a
     * withdrawn aspiration must stop being spoken, and because ids are content-derived, "withdrawn"
     * and "reworded" are the same operation. Claims already scored keep their vectors untouched
     * because an unchanged declaration is never rewritten.
     */
    fun materialise(subjectId: String): MaterialisedClaims {
        if (!config.stage4().let { it.enabled && it.profileEnabled }) return MaterialisedClaims()
        val resolved = SubjectProfileDefaults.resolve(profiles.findBySubject(subjectId))
        val desired = declaredClaims(subjectId, resolved).associateBy { it.id }
        val existing = claims.findByAsset(declaredAssetId(subjectId)).associateBy { it.id }

        val created = desired.filterKeys { it !in existing }.values.toList()
        val stale = existing.filterKeys { it !in desired }.keys.toList()
        created.forEach { claims.save(it) }

        // A shareable contact rides in as a `sensitive` claim whose opt-in lives on the companion
        // review, not the claim — the `INCLUDE` row the S6 PII screen would otherwise capture
        // (§5.2/§5.3). Reconcile that opt-in off the *desired sensitive set*, NOT the created/kept
        // split, and heal any that is absent. The claim and its review are two separate,
        // non-transactional `.await()` writes under one seal (IntakeService.sealManifest:564, and
        // the post-seal re-materialise at SubjectProfileService:318): if the claim lands but the
        // review write fails, the retry seal finds the claim already in `existing`, classifies it
        // `kept`, and a created-branch-only write would never re-run — leaving a `sensitive` claim
        // with no `INCLUDE` forever, which ClaimReviewService.approvedForDownstream:219 silently
        // drops, so the subject's explicitly-shared contact never reaches Stage 3/4. Keying the
        // write on absence keeps it idempotent (no churn, no re-stamp on the happy path) and never
        // clobbers a later operator decision on the same review. Only group-E claims are sensitive;
        // C/D claims never take one, so a subject with no shared contact adds no review read.
        val sensitiveDesired = desired.values.filter { it.sensitive }
        if (sensitiveDesired.isNotEmpty()) {
            val reviewed = reviews.findBySubject(subjectId).mapTo(mutableSetOf()) { it.claimId }
            sensitiveDesired.forEach { c -> if (c.id !in reviewed) reviews.save(includeReview(c)) }
        }

        stale.forEach { id ->
            claims.delete(id)
            // Drop any companion opt-in with the claim it belonged to (a no-op for the C/D claims
            // that never had one), so an un-shared or reworded contact leaves no dangling `INCLUDE`
            // that a later same-id claim could silently inherit (§5.4).
            reviews.delete(id)
        }

        val outcome =
            MaterialisedClaims(
                created = created,
                kept = desired.keys.filter { it in existing },
                deleted = stale,
            )
        if (outcome.touched > 0) {
            log.info(
                "Subject {}: materialised {} declared claim(s), removed {}, kept {}",
                subjectId,
                created.size,
                stale.size,
                outcome.kept.size,
            )
        }
        return outcome
    }

    /**
     * The declared claim set for a resolved profile — pure, so the whole C/D → ledger mapping is
     * unit-testable without Firestore. Order is stable (C then D, declaration order within each).
     */
    fun declaredClaims(subjectId: String, profile: ResolvedSubjectProfile): List<Claim> {
        if (profile.declaredBlank) return emptyList()
        val out = mutableListOf<Claim>()

        // ---- C. Candidacy / targeting → IDENTITY -------------------------------------------
        profile.targetRoles.forEach {
            out +=
                claim(subjectId, ClaimType.IDENTITY, DeclaredType.TARGET_ROLE, "Target role: $it.")
        }
        profile.targetSeniority
            .takeIf { it.isNotBlank() }
            ?.let {
                out +=
                    claim(
                        subjectId,
                        ClaimType.IDENTITY,
                        DeclaredType.TARGET_SENIORITY,
                        "Targeting $it-level roles.",
                    )
            }
        profile.employmentType?.let {
            out +=
                claim(
                    subjectId,
                    ClaimType.IDENTITY,
                    DeclaredType.ENGAGEMENT_MODEL,
                    engagementText(it),
                )
        }
        profile.openToRelocation?.let {
            out +=
                claim(
                    subjectId,
                    ClaimType.IDENTITY,
                    DeclaredType.RELOCATION_STANCE,
                    if (it) "Open to relocating for the right role."
                    else "Not looking to relocate.",
                )
        }

        // ---- D. Declared narrative → VALUE, boundaries → WEAKNESS --------------------------
        // The aspiration text is carried **verbatim**: it is the subject's own sentence about his
        // own direction, and the whole point of the feature is that the advocate speaks it because
        // he said it. Any rewording here would put words in a real person's mouth (§3.7).
        profile.aspirations.forEach {
            out += claim(subjectId, ClaimType.VALUE, DeclaredType.STATED_ASPIRATION, it)
        }
        profile.statedPreferences.forEach {
            out += claim(subjectId, ClaimType.VALUE, DeclaredType.STATED_TRACK_PREFERENCE, it)
        }
        // A boundary claim records only *that the boundary exists*, never the substance behind it —
        // "prefers not to discuss health" says nothing about anyone's health. Enforcement at
        // serving
        // time stays the existing warm-deflect I2/I3 posture; nothing new is invented here (OD-9).
        profile.doNotDiscussChecks.forEach { key ->
            DoNotDiscussVocabulary.labelOf(key)?.let { label ->
                out +=
                    claim(
                        subjectId,
                        ClaimType.WEAKNESS,
                        DeclaredType.BOUNDARY,
                        "Prefers not to discuss ${label.lowercase()}.",
                    )
            }
        }
        // Only an APPROVED entry gets here — `resolve` drops PENDING/REJECTED text on the floor, so
        // an unvetted string physically cannot reach this point (OD-9).
        profile.approvedDoNotDiscussCustom?.let {
            out +=
                claim(
                    subjectId,
                    ClaimType.WEAKNESS,
                    DeclaredType.BOUNDARY,
                    "Prefers not to discuss ${it.trimEnd('.').lowercase()}.",
                )
        }

        // ---- E. Contact / PII → sensitive IDENTITY, opted-in via the review (§5.2) -------------
        // Only a **shareable** contact is materialised: it becomes a `sensitive` claim that the
        // planner voices verbatim on Row 8 (`Stage4VoicingPlanner.claimRow`), gated by the
        // companion
        // INCLUDE review the reconcile pass writes above. A private contact is skipped entirely, so
        // it never enters the ledger, never reaches the planner (F3), and stays under the trained
        // REFUSE posture — the corpus-invariant B2 shape (PRECEDENCE I2). Home address, DOB and
        // government ID are not [ContactKind]s at all: they map to the always-REFUSE O7 safety bar.
        profile.contact
            .filter { it.shareable }
            .forEach {
                out +=
                    claim(
                        subjectId,
                        ClaimType.IDENTITY,
                        DeclaredType.contactType(it.kind),
                        contactText(it.kind, it.value),
                        sensitive = true,
                    )
            }
        return out
    }

    private fun engagementText(type: EmploymentType): String =
        when (type) {
            EmploymentType.FTE -> "Looking for full-time employment."
            EmploymentType.CONTRACT -> "Looking for contract engagements."
            EmploymentType.EITHER -> "Open to either full-time employment or contract engagements."
        }

    private fun claim(
        subjectId: String,
        claimType: ClaimType,
        declaredType: String,
        text: String,
        sensitive: Boolean = false,
    ): Claim =
        Claim(
            id = idOf(subjectId, declaredType, text),
            subjectId = subjectId,
            assetId = declaredAssetId(subjectId),
            claimType = claimType,
            text = text,
            // Denormalised as the self-report it is, so the Stage-3 projection routes it to the
            // subject's own attestor and the dependence key collapses with his other self-claims —
            // a declaration must never look like independent corroboration of itself (§3.3).
            speakerRole = SpeakerRole.SUBJECT,
            sourceClass = SourceClass.SELF,
            relationship = Relationship.SELF,
            // LOW is honest and stays honest: the fixed belief floor lives in the scorer and
            // governs
            // eligibility, not the tier (§3.5, OD-1).
            authenticityTier = AuthenticityTier.LOW,
            claimBasis = ClaimBasis.STATED,
            // Group E only: a shared contact is PII, so it carries the `sensitive` marker the O7
            // gate keys on — un-opted it can never reach the planner (F3), opted-in it is voiced
            // verbatim on Row 8 (§5.2). C/D pass false: an aspiration is not PII.
            sensitive = sensitive,
            favorability = FAVORABILITY_NEUTRAL,
            origin = ClaimOrigin.SUBJECT_DECLARED,
            declaredType = declaredType,
            // extractionPrompt* stay null: there was no extraction, and Stage 3 compares like with
            // like on those fields.
            createdAt = Instant.now(),
        )

    /**
     * The companion review that carries a shared contact's opt-in. The downstream gate reads the
     * PII choice off the *review*, not the claim (`ClaimReviewService.approvedForDownstream:219`),
     * so a `sensitive` claim with no `INCLUDE` review is dropped exactly like a held one. The
     * per-field `shareable` flag was the subject's own consent at profile entry (§5.3), so this
     * stands in for the S6 screen the subject never had to visit for a canonical field.
     */
    private fun includeReview(claim: Claim): ClaimReview =
        ClaimReview(
            claimId = claim.id,
            subjectId = claim.subjectId,
            piiChoice = PiiChoice.INCLUDE,
            reviewedBy = SHAREABLE_OPT_IN_ACTOR,
            reviewedAt = Instant.now(),
        )

    /**
     * How a declared contact is spoken — a short, labelled line the Row-8 voice can read verbatim
     * ("Email: a@b.com."). The value is carried unchanged (it is the subject's own detail); only a
     * human-readable kind label is added.
     */
    private fun contactText(kind: ContactKind, value: String): String {
        val label =
            when (kind) {
                ContactKind.NAME -> "Preferred name"
                ContactKind.EMAIL -> "Email"
                ContactKind.LINKEDIN -> "LinkedIn"
                ContactKind.PORTFOLIO -> "Portfolio"
                ContactKind.PHONE -> "Phone"
            }
        val trimmed = value.trim()
        val period = if (trimmed.endsWith(".")) "" else "."
        return "$label: $trimmed$period"
    }

    private companion object {
        /** `declared:<subjectId>` — the §3.3 synthetic asset sentinel. */
        const val DECLARED_ASSET_PREFIX = "declared:"

        /**
         * The `reviewedBy` stamped on a contact's auto opt-in — a sentinel, not a person, because
         * no operator clicked approve: the consent was the subject's own per-field `shareable` flag
         * at profile entry (§5.3). Making it legible keeps a shared contact's opt-in auditable as
         * derived-from-the-flag rather than passed off as a human PII-review decision.
         */
        const val SHAREABLE_OPT_IN_ACTOR = "profile:shareable-contact"

        /**
         * Every declared claim is **neutral**, and that is a safety decision, not a default.
         *
         * Two independent paths key on favorability rather than provenance. Below
         * `app.stage2.favorability-threshold` (0.5) a claim is forced into human review
         * (`ClaimReviewService.needsDecision`), which would leave a declaration sitting unapproved
         * and unspoken unless an operator happened to clear it. Below Stage 4's `UNFAVORABLE_BELOW`
         * (0.5) the negative lane builds a criticism probe out of the claim — and for a
         * do-not-discuss boundary that means generating "I heard *prefers not to discuss health*
         * didn't reflect well on him — isn't that a red flag?", a probe that names the exact topic
         * the subject asked to keep off the table. Neutral is the only value that is both true and
         * safe: a goal is not a defect, and a boundary is not a defect either.
         *
         * The LLD (§3.5) also anticipates a declared *development area* drawing the One-Negative
         * Rule deliberately. Groups C/D carry no development-area field, so nothing here
         * materialises one; when slice 3 or a later field adds it, it gets its own value and its
         * own argument rather than inheriting this one.
         */
        const val FAVORABILITY_NEUTRAL = 0.5

        fun declaredAssetId(subjectId: String) = "$DECLARED_ASSET_PREFIX$subjectId"

        /**
         * `decl-<sha256(subjectId|declaredType|text)>` — content-derived, so materialisation is
         * idempotent across re-seals and a reworded declaration is a *different* claim (the old one
         * is reconciled away) rather than a silent in-place rewrite of something already scored.
         */
        fun idOf(subjectId: String, declaredType: String, text: String): String {
            val canonical = "$subjectId|$declaredType|${text.trim()}"
            val hex =
                MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            return "decl-${hex.take(24)}"
        }
    }
}

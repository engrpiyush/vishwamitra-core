package ai.vishwakarma.labelling.domain

import java.time.Instant

/** One policy's acceptance stamp (LLD §13.2) — the auditable artifact. */
data class PolicyAcceptance(val version: String, val at: Instant)

/**
 * `terms_acceptances/{email}` (LLD §5.1): per-policy acceptance records for a SUBJECT login. Keys
 * of [acceptances] are the policy slugs (`tnc` / `privacy` / `cookies`); the S1 gate requires the
 * CURRENT version of all three, so bumping a version re-gates every subject at next visit.
 */
data class TermsAcceptance(
    val email: String,
    val acceptances: Map<String, PolicyAcceptance> = emptyMap(),
)

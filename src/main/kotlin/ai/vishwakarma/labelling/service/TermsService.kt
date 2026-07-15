package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.PolicyAcceptance
import ai.vishwakarma.labelling.domain.TermsAcceptance
import ai.vishwakarma.labelling.persistence.TermsAcceptanceRepository
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

/**
 * The S1 acceptance mechanics (VA-30, LLD §13.2). Policy versions are code constants — bumping one
 * re-gates every subject at their next request. Gate lookups are cached 60s per email (invalidated
 * on accept) so the per-request terms gate never hammers Firestore.
 */
@Service
class TermsService(private val records: TermsAcceptanceRepository) {

    private data class Cached(val accepted: Boolean, val at: Instant)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** True when [email] has accepted the CURRENT version of all three policies. */
    fun hasCurrentAcceptances(email: String): Boolean {
        val now = Instant.now()
        cache[email]
            ?.takeIf { Duration.between(it.at, now) < TTL }
            ?.let {
                return it.accepted
            }
        val record = records.find(email)
        val accepted =
            CURRENT_VERSIONS.all { (policy, version) ->
                record?.acceptances?.get(policy)?.version == version
            }
        cache[email] = Cached(accepted, now)
        return accepted
    }

    /**
     * Record acceptance of the current versions. Policies already at the current version keep their
     * original stamp (the earliest acceptance is the auditable artifact); superseded or missing
     * ones get a fresh `{version, now}`.
     */
    fun accept(email: String): TermsAcceptance {
        val now = Instant.now()
        val existing = records.find(email)?.acceptances ?: emptyMap()
        val merged =
            CURRENT_VERSIONS.mapValues { (policy, version) ->
                existing[policy]?.takeIf { it.version == version } ?: PolicyAcceptance(version, now)
            }
        val record = TermsAcceptance(email = email, acceptances = merged)
        records.save(record)
        cache[email] = Cached(accepted = true, at = now)
        return record
    }

    companion object {
        const val POLICY_TNC = "tnc"
        const val POLICY_PRIVACY = "privacy"
        const val POLICY_COOKIES = "cookies"

        /** LLD §13.2: TNC_V1 / PRIVACY_V1 / COOKIES_V1. */
        val CURRENT_VERSIONS: Map<String, String> =
            mapOf(POLICY_TNC to "v1", POLICY_PRIVACY to "v1", POLICY_COOKIES to "v1")

        private val TTL: Duration = Duration.ofSeconds(60)
    }
}

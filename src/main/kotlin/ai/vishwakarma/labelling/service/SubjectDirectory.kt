package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.persistence.SubjectRepository
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

/**
 * Cached handle → subject lookup for host routing (VA-30, LLD §4.3). 60s of staleness is
 * acceptable: handles are immutable (D6), and archiving a subject takes ≤60s to propagate to their
 * host. Misses are cached too (negative caching), so unknown-subdomain scans don't hammer
 * Firestore; the cache is cleared wholesale if scans ever balloon it.
 */
@Service
class SubjectDirectory(private val subjects: SubjectRepository) {

    private data class Cached(val subject: Subject?, val at: Instant)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** The ACTIVE subject owning [handle], or null (unknown / archived — indistinguishable). */
    fun activeByHandle(handle: String): Subject? {
        val now = Instant.now()
        val cached =
            cache[handle]?.takeIf { Duration.between(it.at, now) < TTL }
                ?: run {
                    if (cache.size > MAX_ENTRIES) cache.clear()
                    Cached(subjects.findByHandle(handle), now).also { cache[handle] = it }
                }
        return cached.subject?.takeIf { it.status == SubjectStatus.ACTIVE }
    }

    companion object {
        private val TTL: Duration = Duration.ofSeconds(60)
        private const val MAX_ENTRIES = 10_000
    }
}

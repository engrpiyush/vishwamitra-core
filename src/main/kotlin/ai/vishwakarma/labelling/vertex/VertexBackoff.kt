package ai.vishwakarma.labelling.vertex

import kotlin.random.Random
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClientResponseException

/**
 * Backoff for Vertex/Gemini REST calls — the documented 429 remedy, hardened for parallel callers
 * (2026-07-11): the wait is the server's own `Retry-After` when the 429 carries one, else **equal
 * jitter** — half the configured cap guaranteed, the other half a uniform draw — so simultaneous
 * retries decorrelate (the fixed-step failure mode) without the pure-full-jitter weakness of
 * drawing a 900ms sleep on a 32s rung under *sustained* saturation. The ladder comes from
 * `app.gcp.vertex-backoff-ms`; its values are per-attempt *caps*, and **empty/undefined = backoff
 * disabled** (operator decision): the call runs once and any failure propagates immediately. Only
 * 429 + 5xx retry; everything else — and the final failure — propagates verbatim so a run FAILs
 * with the provider's message and Retry resumes from cursors.
 */
object VertexBackoff {

    private val log = LoggerFactory.getLogger(VertexBackoff::class.java)

    /** Server-advised waits are honored but bounded — a poll request must stay finite. */
    private const val MAX_RETRY_AFTER_MS = 60_000L

    fun <T> retrying(delaysMs: List<Long>, what: String, call: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return call()
            } catch (e: RestClientResponseException) {
                val retryable = e.statusCode.is5xxServerError || e.statusCode.value() == 429
                if (!retryable || attempt == delaysMs.size) throw e
                val advised = retryAfterMs(e)
                val cap = delaysMs[attempt].coerceAtLeast(2)
                val delayMs =
                    advised?.coerceAtMost(MAX_RETRY_AFTER_MS)?.plus(Random.nextLong(0, 500))
                        ?: (cap / 2 + Random.nextLong(cap / 2 + 1))
                log.warn(
                    "Vertex {} returned {} — retrying in {}ms ({}; attempt {}/{})",
                    what,
                    e.statusCode.value(),
                    delayMs,
                    if (advised != null) "server-advised" else "equal jitter",
                    attempt + 1,
                    delaysMs.size,
                )
                Thread.sleep(delayMs)
                attempt++
            }
        }
    }

    /** The seconds form of `Retry-After`; the HTTP-date form (rare here) falls back to jitter. */
    private fun retryAfterMs(e: RestClientResponseException): Long? =
        e.responseHeaders
            ?.getFirst("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.times(1000)
}

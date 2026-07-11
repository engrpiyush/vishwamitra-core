package ai.vishwakarma.labelling.drafting

import ai.vishwakarma.labelling.config.AppProperties
import com.google.auth.oauth2.GoogleCredentials
import org.springframework.web.client.RestClient

/**
 * The HTTP door for one `generateContent` call — everything above it (body assembly, response
 * parsing, the judge/extraction/drafting consumers) is door-agnostic. Two siblings (2026-07-11, for
 * quota-limit testing): [VertexGeminiTransport] (ADC bearer, Dynamic Shared Quota — no hard cap,
 * occasional 429 weather; the default) and [GeminiApiTransport] (the Developer API — API key, fixed
 * paid-tier quotas). Selected once at boot via `app.gcp.gemini-transport`; both take the same
 * request body and return the same response shape, so switching doors never changes
 * judging/extraction semantics.
 */
internal interface GeminiTransport {
    /** Short name for logs and the backoff label. */
    val label: String

    /** POST [body] to this door's generateContent endpoint for [model]; returns the raw JSON. */
    fun post(model: String, body: Map<String, Any>): String
}

/** Vertex AI (`aiplatform.googleapis.com`): the app SA's ADC token, global or in-region. */
internal class VertexGeminiTransport(private val props: AppProperties) : GeminiTransport {

    private val rest = RestClient.create()

    override val label = "vertex"

    override fun post(model: String, body: Map<String, Any>): String {
        // "global" reaches models not served regionally (e.g. gemini-2.5-pro); blank = in-region.
        val location = props.gcp.geminiLocation.ifBlank { props.gcp.region }
        val host =
            if (location == "global") "aiplatform.googleapis.com"
            else "$location-aiplatform.googleapis.com"
        val url =
            "https://$host/v1/projects/${props.gcp.projectId}" +
                "/locations/$location/publishers/google/models/$model:generateContent"
        val token =
            GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform")
                .also { it.refreshIfExpired() }
                .accessToken
                .tokenValue
        return rest
            .post()
            .uri(url)
            .header("Authorization", "Bearer $token")
            .body(body)
            .retrieve()
            .body(String::class.java) ?: error("empty Gemini response")
    }
}

/**
 * The Gemini Developer API (`generativelanguage.googleapis.com`): same models behind fixed, visible
 * paid-tier quotas instead of the shared pool. Key via env `GEMINI_API_KEY` (the same env the
 * embedding transport reads); processed globally — no in-region residency.
 */
internal class GeminiApiTransport(private val props: AppProperties) : GeminiTransport {

    private val rest = RestClient.create()

    override val label = "gemini-api"

    override fun post(model: String, body: Map<String, Any>): String {
        check(props.gcp.geminiApiKey.isNotBlank()) {
            "gemini-transport=gemini-api needs GEMINI_API_KEY set"
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        return rest
            .post()
            .uri(url)
            .header("x-goog-api-key", props.gcp.geminiApiKey)
            .body(body)
            .retrieve()
            .body(String::class.java) ?: error("empty Gemini response")
    }
}

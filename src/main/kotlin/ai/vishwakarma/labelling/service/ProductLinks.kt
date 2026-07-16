package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Absolute URLs onto a subject's own host (VA-30 routing) — the one place email CTAs and the
 * guest-token link agree on the scheme/port story: prod is `https://<handle>.{base-domain}`, dev is
 * plain http with the actual server port (the owner runs on 8090).
 */
@Component
class ProductLinks(
    private val props: AppProperties,
    /** Dev CTA links carry the actual port (PORT env → server.port); prod URLs have none. */
    @Value("\${server.port:8080}") private val serverPort: Int = 8080,
) {

    fun subjectUrl(handle: String, path: String = "/"): String {
        val base = props.product.baseDomain
        return if (base == "localhost") {
            "http://$handle.localhost:$serverPort$path"
        } else {
            "https://$handle.$base$path"
        }
    }
}

package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.service.SubscriptionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Public JSON endpoint behind the coming-soon page's subscribe form. Cross-origin access is
 * restricted to vishwakarma.ai by the CORS config in SecurityConfig; the page itself is
 * same-origin.
 */
@RestController
class SubscribeController(private val subscriptions: SubscriptionService) {

    @PostMapping("/coming-soon/subscribe")
    fun subscribe(@RequestBody body: SubscribeRequest): ResponseEntity<Map<String, String>> =
        try {
            subscriptions.subscribe(body.email)
            ResponseEntity.status(HttpStatus.CREATED).body(mapOf("status" to "ok"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest()
                .body(mapOf("status" to "error", "message" to "Invalid email"))
        }

    data class SubscribeRequest(val email: String = "")
}

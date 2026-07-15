package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.service.NotifySweepService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Cloud Scheduler target (VA-41, LLD §3.4): `subject-notify-poker` every 30 min. Sits outside both
 * UI chains — the `/internal` security chain admits only a verified Google OIDC identity (see
 * InternalOidcFilter); a sweep that fires twice is a state-guarded no-op.
 */
@RestController
@RequestMapping("/internal/notify")
class InternalNotifyController(private val sweeper: NotifySweepService) {

    @PostMapping("/sweep")
    fun sweep(): ResponseEntity<Map<String, Int>> = ResponseEntity.ok(sweeper.sweep())
}

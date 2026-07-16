package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.service.ProvisioningService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Cloud Scheduler target (VA-39, LLD §3.4): `advocate-window-sweeper` every 15 min. Same posture as
 * [InternalNotifyController] — the `/internal` chain admits only a verified Google OIDC identity
 * (InternalOidcFilter); every sweep action is state-guarded, so a double fire is a no-op.
 */
@RestController
@RequestMapping("/internal/provisioning")
class InternalProvisioningController(private val provisioning: ProvisioningService) {

    @PostMapping("/sweep")
    fun sweep(): ResponseEntity<Map<String, Int>> = ResponseEntity.ok(provisioning.sweep())
}

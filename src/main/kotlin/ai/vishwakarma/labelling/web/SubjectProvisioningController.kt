package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.Advocate
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.WindowPreset
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.ProvisioningService
import ai.vishwakarma.labelling.web.SubjectProvisioning.SwitchView
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The subject's window switch — S9 (VA-40, LLD §8.1). External URL `/provisioning` on the subject
 * host, rewritten to `/s/provisioning` and gated subject-of-host by the §4.4 table. Friendly
 * vocabulary only ([SubjectProvisioning]); presets are the whole choice (F10: 1 day / 3 days / 1
 * week); the open page drives `poll` (the training-surface idiom) while a transition is in flight.
 * Machine detail (lastError etc.) never renders here — that's the operator panel's job.
 */
@Controller
@RequestMapping("/s/provisioning")
class SubjectProvisioningController(private val provisioning: ProvisioningService) {

    private fun ctx(request: HttpServletRequest): SubjectCtx =
        SubjectCtx.of(request) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @GetMapping
    fun switchPage(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        val advocate = provisioning.advocate(ctx.subjectId)
        val state = advocate?.state ?: AdvocateState.NOT_BUILT
        val view = SubjectProvisioning.viewFor(state)
        model.addAttribute("pageTitle", "Your advocate — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("view", view)
        model.addAttribute("presets", SubjectProvisioning.presets())
        model.addAttribute("endsAt", advocate?.takeIf { view == SwitchView.ONLINE }?.windowEndsAt)
        model.addAttribute("score", advocate?.aggregateScore)
        return "subject/provisioning"
    }

    @PostMapping("/start")
    fun start(
        request: HttpServletRequest,
        @RequestParam preset: String,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        val chosen =
            WindowPreset.fromOrNull(preset)
                ?: run {
                    ra.addFlashAttribute("error", "Pick how long the window should last.")
                    return "redirect:/provisioning"
                }
        provisioning
            .startWindow(ctx.subjectId, chosen, actor(ctx))
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Starting your advocate — this takes a little while."
                    )
                },
            )
        return "redirect:/provisioning"
    }

    @PostMapping("/end")
    fun end(request: HttpServletRequest, ra: RedirectAttributes): String {
        val ctx = ctx(request)
        provisioning
            .endWindow(ctx.subjectId, "subject switch-off", actor(ctx))
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Your advocate is going offline.") },
            )
        return "redirect:/provisioning"
    }

    /**
     * The open-page driver: advances whatever is in flight and answers the friendly view token ONLY
     * (§12.3 applies to the JSON too) — the JS reloads when the token changes.
     */
    @PostMapping("/poll")
    @ResponseBody
    fun poll(request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        val ctx = ctx(request)
        val view =
            provisioning
                .poll(ctx.subjectId)
                .fold(
                    { SubjectProvisioning.viewFor(AdvocateState.NOT_BUILT) },
                    { adv: Advocate -> SubjectProvisioning.viewFor(adv.state) },
                )
        return ResponseEntity.ok(mapOf("view" to view.name))
    }

    private fun actor(ctx: SubjectCtx): String = CurrentUser.email() ?: "subject:${ctx.subjectId}"
}

package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.TokenEmailStatus
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.TokenService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The token dashboard (VA-37, LLD §6.5 — S8): generate + list + revoke, cap 10 (resend and
 * per-token transcripts are v1.1). Authorization is the subject chain's `/s/tokens/` rule
 * (subject-of-host ∨ operator); the service re-asserts subject scope on every mutation (§12.1).
 */
@Controller
@RequestMapping("/s/tokens")
class SubjectTokenController(private val tokens: TokenService) {

    private fun ctx(request: HttpServletRequest): SubjectCtx =
        SubjectCtx.of(request) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @GetMapping
    fun dashboard(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        model.addAttribute("pageTitle", "Access codes — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("rows", tokens.dashboard(ctx.subjectId))
        model.addAttribute("capReached", tokens.capReached(ctx.subjectId))
        model.addAttribute(
            "advocateBuilt",
            tokens.advocateState(ctx.subjectId) != AdvocateState.NOT_BUILT,
        )
        return "subject/tokens"
    }

    @PostMapping("/generate")
    fun generate(
        request: HttpServletRequest,
        @RequestParam("guestEmail") guestEmail: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        tokens
            .generate(ctx, guestEmail, CurrentUser.email())
            .fold(
                { error -> redirectAttributes.addFlashAttribute("error", error.message) },
                { token ->
                    if (token.emailStatus == TokenEmailStatus.FAILED) {
                        redirectAttributes.addFlashAttribute(
                            "error",
                            "We couldn't deliver to ${token.guestEmail} — revoke that code " +
                                "and generate a new one.",
                        )
                    } else {
                        redirectAttributes.addFlashAttribute(
                            "ok",
                            "Access code sent to ${token.guestEmail}.",
                        )
                    }
                },
            )
        return "redirect:/tokens"
    }

    @PostMapping("/revoke")
    fun revoke(
        request: HttpServletRequest,
        @RequestParam("tokenHash") tokenHash: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        tokens
            .revoke(ctx, tokenHash)
            .fold(
                { error -> redirectAttributes.addFlashAttribute("error", error.message) },
                { redirectAttributes.addFlashAttribute("ok", "Access code revoked.") },
            )
        return "redirect:/tokens"
    }
}

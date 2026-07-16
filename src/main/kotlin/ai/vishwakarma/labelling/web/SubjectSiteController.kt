package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.GuestCtx
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.security.TermsGateInterceptor
import ai.vishwakarma.labelling.service.TermsService
import ai.vishwakarma.labelling.service.TokenService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.server.ResponseStatusException

/**
 * The subject-world face (VA-30). Mappings live under the internal `/s` prefix that
 * [ai.vishwakarma.labelling.security.SubjectHostFilter] rewrites subject-host paths onto — the
 * external URLs are `https://<handle>.{base-domain}/…`. Every handler 404s without a [SubjectCtx]
 * (defense in depth: reaching the internal `/s` paths on the operator host is not a thing). Root
 * renders the split landing until VA-42/43 complete the chat decision tree; the training area lives
 * in [SubjectTrainingController] (VA-32); the terms gate + accept POST are the VA-30 deliverable
 * (the real S1 policy texts ship with VA-45).
 */
@Controller
@RequestMapping("/s")
class SubjectSiteController(
    private val terms: TermsService,
    private val tokens: TokenService,
) {

    private fun ctx(request: HttpServletRequest): SubjectCtx =
        SubjectCtx.of(request) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @GetMapping
    fun root(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        // VA-37: the split landing's guest panel is state-adaptive (§8.3); the full decision
        // tree (chat for signed-in/connected visitors) arrives with VA-42/43.
        GuestPanel.populate(
            model,
            ctx,
            tokens.advocateState(ctx.subjectId),
            connected = GuestCtx.of(request) != null,
        )
        model.addAttribute("signedIn", CurrentUser.email() != null)
        return "subject/landing"
    }

    @GetMapping("/terms")
    fun terms(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        val email = CurrentUser.email()
        model.addAttribute("pageTitle", "Terms — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("versions", TermsService.CURRENT_VERSIONS)
        model.addAttribute("accepted", email != null && terms.hasCurrentAcceptances(email))
        return "subject/terms"
    }

    @PostMapping("/terms/accept")
    fun accept(request: HttpServletRequest): String {
        ctx(request)
        val email = CurrentUser.email() ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        terms.accept(email)
        val saved = request.session.getAttribute(TermsGateInterceptor.RETURN_TO) as? String
        request.session.removeAttribute(TermsGateInterceptor.RETURN_TO)
        // Targets come from our own interceptor, but stay paranoid about redirect targets.
        val target = saved?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/training"
        return "redirect:$target"
    }
}

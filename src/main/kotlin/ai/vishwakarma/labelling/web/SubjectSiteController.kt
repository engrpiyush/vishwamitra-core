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
 * runs the §8.3 v1.1 decision tree ([SubjectRoot], VA-43): chat once a gate has passed, the split
 * landing otherwise; the training area lives in [SubjectTrainingController] (VA-32); the terms gate
 * + accept POST are the VA-30 deliverable (the real S1 policy texts ship with VA-45).
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
        val state = tokens.advocateState(ctx.subjectId)
        val view =
            SubjectRoot.viewFor(
                operator = CurrentUser.isOperator(),
                boundSubjectId = CurrentUser.subjectId(),
                hostSubjectId = ctx.subjectId,
                guest = GuestCtx.of(request) != null,
                state = state,
            )
        if (view == SubjectRoot.View.LANDING) {
            GuestPanel.populate(model, ctx, state)
            model.addAttribute("signedIn", CurrentUser.email() != null)
            return "subject/landing"
        }
        model.addAttribute("pageTitle", ctx.displayName)
        model.addAttribute("ctx", ctx)
        model.addAttribute("mode", if (view == SubjectRoot.View.CHAT_GUEST) "GUEST" else "SELF")
        // STATUS_SELF: no chat surface — the S9 friendly state card + the switch link (§8.3).
        model.addAttribute(
            "status",
            if (view == SubjectRoot.View.STATUS_SELF) SubjectProvisioning.viewFor(state) else null,
        )
        return "subject/advocate"
    }

    /**
     * The landing's subject-panel CTA (VA-43, §8.3): an authenticated-only no-op whose entry-point
     * bounce runs the OIDC dance and lands back on the root — which now renders chat/status.
     */
    @GetMapping("/signin")
    fun signin(request: HttpServletRequest): String {
        ctx(request)
        return "redirect:/"
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

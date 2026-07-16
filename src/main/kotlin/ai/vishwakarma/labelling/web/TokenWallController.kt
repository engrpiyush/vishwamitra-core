package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.GuestSessionFilter
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.RedeemOutcome
import ai.vishwakarma.labelling.service.TokenService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The guest wall (VA-36/37, LLD §6.3): permitAll on the subject chain, rate-limited inside. Failure
 * renders the landing in place (so the 429 status survives); success sets the `adv_session`
 * capability cookie and bounces to the root, where the guest panel shows the connected state (chat
 * itself is VA-43).
 */
@Controller
@RequestMapping("/s/wall")
class TokenWallController(
    private val tokens: TokenService,
    private val props: AppProperties,
) {

    private fun ctx(request: HttpServletRequest): SubjectCtx =
        SubjectCtx.of(request) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    /** §8.3 v1.1: the standalone wall page is gone — the wall IS the landing's guest panel. */
    @GetMapping
    fun wall(request: HttpServletRequest): String {
        ctx(request)
        return "redirect:/"
    }

    @PostMapping("/redeem")
    fun redeem(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam("token") token: String,
        model: Model,
    ): String {
        val ctx = ctx(request)
        when (val outcome = tokens.redeem(ctx, token, clientIp(request))) {
            is RedeemOutcome.Minted -> {
                val session = outcome.session
                val cookie =
                    ResponseCookie.from(GuestSessionFilter.COOKIE_NAME, session.sessionId)
                        .httpOnly(true)
                        // Host-only (no Domain attribute) — bound to this exact subdomain (§6.3).
                        .secure(request.isSecure)
                        .sameSite("Lax")
                        .path("/")
                        .maxAge(props.product.guestSessionTtl)
                        .build()
                response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
                return "redirect:/"
            }
            RedeemOutcome.Locked -> {
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                model.addAttribute("wallLocked", true)
            }
            // The token was deliberately not consumed (§6.1) — show the not-LIVE panel variant.
            RedeemOutcome.NotLive -> {}
            // One generic message for unknown/foreign/used codes — no oracle (§6.3).
            RedeemOutcome.Invalid -> model.addAttribute("wallError", "That code isn't valid.")
        }
        GuestPanel.populate(model, ctx, tokens.advocateState(ctx.subjectId), connected = false)
        model.addAttribute("signedIn", CurrentUser.email() != null)
        return "subject/landing"
    }

    /** The request-provisioning CTA (§8.3): at most one email per subject per day. */
    @PostMapping("/notify")
    fun notify(
        request: HttpServletRequest,
        @RequestParam("email", required = false) email: String?,
        redirectAttributes: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        val sent = tokens.requestProvisioning(ctx, email)
        redirectAttributes.addFlashAttribute(
            "ok",
            if (sent) "We've asked ${ctx.displayName} to switch their advocate on."
            else "${ctx.displayName} has already been notified — check back soon.",
        )
        return "redirect:/"
    }

    /**
     * Client IP for the §6.3 rate bucket. Behind the GCLB edge (VA-28) the verified client IP is
     * the second-from-last `X-Forwarded-For` entry (the LB appends `client, lb`); locally there is
     * no header and the socket address is the truth. Worst case a spoofed header rotates buckets —
     * the per-subject daily surface stays bounded by the token space and lockouts.
     */
    private fun clientIp(request: HttpServletRequest): String {
        val forwarded =
            request
                .getHeader("X-Forwarded-For")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        return when {
            forwarded.size >= 2 -> forwarded[forwarded.size - 2]
            forwarded.size == 1 -> forwarded[0]
            else -> request.remoteAddr ?: "unknown"
        }
    }
}

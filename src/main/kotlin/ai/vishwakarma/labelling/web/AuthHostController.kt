package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.security.AuthHost
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/**
 * The auth host's front door (VA-70, product LLD §4.2 v1.1): validates the `continue` target
 * (open-redirect guard — a bad target gets the auth host's own error page, never a redirect), parks
 * it in the session, and hands off to the standard authorization flow. The auth-host security
 * chain's success handler completes the loop back to the originating subject host.
 */
@Controller
class AuthHostController(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/auth/start")
    fun start(
        request: HttpServletRequest,
        @RequestParam(name = "continue", required = false) continueTo: String?,
    ): String {
        if (!AuthHost.of(request)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val target = AuthHost.safeReturnTarget(continueTo, props.product.baseDomain)
        if (continueTo != null && target == null) {
            log.warn("Refused auth return target: {}", continueTo.take(200))
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "That sign-in link isn't valid.",
            )
        }
        target?.let { request.session.setAttribute(AuthHost.RETURN_TO, it) }
        return "redirect:/oauth2/authorization/google"
    }
}

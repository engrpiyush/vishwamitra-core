package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.CurrentUser
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/** Expose the signed-in identity (and request path) to every view (used by the nav bar). */
@ControllerAdvice
class GlobalModelAdvice {

    @ModelAttribute("currentEmail") fun currentEmail(): String? = CurrentUser.email()

    @ModelAttribute("currentRole") fun currentRole(): String? = CurrentUser.role()?.name

    /** Request path, so the nav can highlight the active section. */
    @ModelAttribute("currentPath")
    fun currentPath(request: HttpServletRequest): String = request.requestURI
}

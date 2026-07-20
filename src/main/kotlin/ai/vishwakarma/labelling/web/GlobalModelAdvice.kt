package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.SubjectProfileService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Model state every view may read without its controller having to supply it: the signed-in
 * identity and request path (the nav bar), plus the surface flags a **shared fragment** depends on
 * — a fragment is rendered by templates from many controllers, so anything it gates on has to come
 * from here or it silently reads null on whichever page was forgotten.
 */
@ControllerAdvice
class GlobalModelAdvice(private val profiles: SubjectProfileService) {

    @ModelAttribute("currentEmail") fun currentEmail(): String? = CurrentUser.email()

    @ModelAttribute("currentRole") fun currentRole(): String? = CurrentUser.role()?.name

    /** Request path, so the nav can highlight the active section. */
    @ModelAttribute("currentPath")
    fun currentPath(request: HttpServletRequest): String = request.requestURI

    /**
     * VA-140: is the "Your details" surface switched on (`app.stage4.profile-enabled`)?
     *
     * It lives here, not on a controller, because the link is in the **shared** subject footer
     * (`subject/layout.html :: foot`, rendered by a dozen templates across five controllers) and
     * `SubjectTrainingController.profile` redirects away whenever the flag is down — the flag
     * defaults off outside the dev profile (`AppProperties.Stage4.profileEnabled`,
     * `application.yml`), so an ungated link would be a permanently visible, permanently dead nav
     * item in production, and dev — the one profile with the flag on — could never reproduce it.
     * Advice-wide so every renderer of that fragment has it; config-only
     * ([SubjectProfileService.surfaceEnabled]) so it costs no Firestore read per page.
     */
    @ModelAttribute("profileEnabled") fun profileEnabled(): Boolean = profiles.surfaceEnabled()
}

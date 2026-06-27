package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.CurrentUser
import jakarta.servlet.http.HttpSession
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** Session flag marking that the dev landing splash was already shown (see DashboardController). */
const val WELCOMED_ATTR = "welcomed"

/**
 * Public landing page shown before sign-in. Same flow in every environment: render the splash with
 * a "Continue with Google" button. In prod the button initiates real OAuth; in dev (auth bypassed
 * via DevAuthFilter) it just navigates home, since the user is already authenticated locally.
 */
@Controller
class LandingController(env: Environment) {

    private val isDev: Boolean = env.acceptsProfiles(Profiles.of("dev"))
    private val loginUrl: String = if (isDev) "/" else "/oauth2/authorization/google"

    @GetMapping("/welcome")
    fun welcome(model: Model, session: HttpSession): String {
        // In prod, a signed-in user has no reason to see the splash again, so skip it. In dev every
        // request is auto-authenticated, so that check would always fire and the landing would
        // never
        // be visible — there we always render it and let the button take the user home.
        if (!isDev && CurrentUser.email() != null) return "redirect:/"
        // Remember the splash was shown so the dev root no longer routes back through it.
        session.setAttribute(WELCOMED_ATTR, true)
        model.addAttribute("pageTitle", "Welcome")
        model.addAttribute("loginUrl", loginUrl)
        return "landing"
    }
}

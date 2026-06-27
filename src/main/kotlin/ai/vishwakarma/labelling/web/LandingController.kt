package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.CurrentUser
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Public landing page served at the root `/` — the welcome splash with a "Continue with Google"
 * button, shown before sign-in. In prod the button initiates real OAuth; in dev (auth bypassed via
 * DevAuthFilter) it just navigates to the dashboard, since the user is already authenticated.
 */
@Controller
class LandingController(env: Environment) {

    private val isDev: Boolean = env.acceptsProfiles(Profiles.of("dev"))
    private val loginUrl: String = if (isDev) "/home" else "/oauth2/authorization/google"

    @GetMapping("/")
    fun index(model: Model): String {
        // In prod a signed-in user skips the splash → dashboard (email() is null for anonymous
        // visitors, so logged-out users fall through and see the landing). In dev every request is
        // auto-authenticated by DevAuthFilter, so that check would always fire and the landing
        // would
        // never be visible — there we always render it and let the button navigate home.
        if (!isDev && CurrentUser.email() != null) return "redirect:/home"
        model.addAttribute("pageTitle", "Welcome")
        model.addAttribute("loginUrl", loginUrl)
        return "landing"
    }
}

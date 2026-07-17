package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.PublicHost
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.server.ResponseStatusException

/**
 * The public-apex world (VA-71, LLD §8.4): the vishwakarma.ai front door — what the product is, how
 * access works, plus the §13.3 policy pages. Mappings live under the internal `/p` prefix
 * [ai.vishwakarma.labelling.security.SubjectHostFilter] rewrites apex paths onto; every handler
 * 404s without the [PublicHost] marker (defense in depth — MVC maps these routes globally, but only
 * the apex host may render them). Friendly capability language only — no stage vocabulary, no model
 * identifiers, no operator links (§12.3).
 */
@Controller
@RequestMapping("/p")
class PublicSiteController {

    private fun requirePublic(request: HttpServletRequest) {
        if (!PublicHost.of(request)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @GetMapping
    fun landing(request: HttpServletRequest, model: Model): String {
        requirePublic(request)
        model.addAttribute("pageTitle", "Personal advocates")
        return "public/landing"
    }

    /** The same §13.3 texts the subject hosts publish, framed for the apex. */
    @GetMapping("/policies/{page}")
    fun policy(request: HttpServletRequest, @PathVariable page: String, model: Model): String {
        requirePublic(request)
        val (fragment, title) =
            SubjectSiteController.POLICY_PAGES[page]
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        model.addAttribute("pageTitle", title)
        model.addAttribute("policy", fragment)
        return "public/policy"
    }
}

package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.security.PublicHost
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException

/**
 * The public-apex world (VA-71, LLD §8.4; matrix-landing round): the vishwakarma.ai brand page at
 * the root (the static matrix landing — forms post to the external landing API, not this app) and
 * the product front door at `/construct` (the VA-71 capability landing). Mappings live under the
 * internal `/p` prefix [ai.vishwakarma.labelling.security.SubjectHostFilter] rewrites apex paths
 * onto; every handler 404s without the [PublicHost] marker (defense in depth — MVC maps these
 * routes globally, but only the apex host may render them). Friendly capability language only — no
 * stage vocabulary, no model identifiers, no operator links (§12.3).
 */
@Controller
@RequestMapping("/p")
class PublicSiteController {

    private fun requirePublic(request: HttpServletRequest) {
        if (!PublicHost.of(request)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    /**
     * The brand landing: the self-contained static matrix page, returned directly as the response
     * body. Deliberately NOT a servlet forward — a forward re-enters the dispatch machinery on top
     * of the host filter's rewritten request and loops (StackOverflowError, found live 2026-07-18).
     * No second dispatch exists on this path, so no loop is possible.
     */
    @GetMapping(produces = ["text/html;charset=UTF-8"])
    @ResponseBody
    fun landing(request: HttpServletRequest): String {
        requirePublic(request)
        return brandPage
    }

    private val brandPage: String by lazy {
        ClassPathResource("static/vishwakarma-ai-landing.html")
            .inputStream
            .readAllBytes()
            .decodeToString()
    }

    /** The product front door (the pre-matrix apex landing, relocated). */
    @GetMapping("/construct")
    fun construct(request: HttpServletRequest, model: Model): String {
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

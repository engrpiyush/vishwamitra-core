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
 * The public-apex world (VA-71, LLD §8.4; VA-173 two-door round): the vishwakarma.ai root is the
 * two-door bifurcation (individuals ⟂ business), each door opening a use-case catalog
 * (`/individuals`, `/business`), plus the individuals product front door at `/construct`. Mappings
 * live under the internal `/p` prefix [ai.vishwakarma.labelling.security.SubjectHostFilter]
 * rewrites apex paths onto; every handler 404s without the [PublicHost] marker (defense in depth —
 * MVC maps these routes globally, but only the apex host may render them). Friendly capability
 * language only — no stage vocabulary, no model identifiers, no operator links (§12.3).
 */
@Controller
@RequestMapping("/p")
class PublicSiteController {

    private fun requirePublic(request: HttpServletRequest) {
        if (!PublicHost.of(request)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    /**
     * VA-173: the apex root is the two-door bifurcation (individuals ⟂ business). Rendered as a
     * Thymeleaf view like every other apex page — safe on the rewritten `/p` request because view
     * rendering writes the response directly; it is NOT a servlet forward, so the dispatch loop
     * that once forced the raw static page to be streamed by hand (StackOverflowError, 2026-07-18)
     * never applies (the sibling `/p/construct` view has proven this). `static/vishwakarma-ai-
     * landing.html` stays on disk but is no longer served from anywhere.
     */
    @GetMapping
    fun landing(request: HttpServletRequest): String {
        requirePublic(request)
        return "public/root"
    }

    /** The product front door (the pre-matrix apex landing, relocated). */
    @GetMapping("/construct")
    fun construct(request: HttpServletRequest, model: Model): String {
        requirePublic(request)
        model.addAttribute("pageTitle", "Personal advocates")
        return "public/landing"
    }

    /**
     * VA-173: the "For individuals" use-case catalog — the individuals door from the two-door root.
     * One use case today (Career Advocate → `/construct`); the grid is built to grow.
     */
    @GetMapping("/individuals")
    fun individuals(request: HttpServletRequest, model: Model): String {
        requirePublic(request)
        model.addAttribute("pageTitle", "For individuals")
        return "public/individuals"
    }

    /**
     * VA-173: the "For businesses" use-case catalog — the business door from the two-door root. One
     * use case today (Sales Rep → `/business/sales-rep`); the grid is built to grow.
     */
    @GetMapping("/business")
    fun business(request: HttpServletRequest, model: Model): String {
        requirePublic(request)
        model.addAttribute("pageTitle", "For businesses")
        return "public/business"
    }

    /** VA-173: the AI Sales Rep brochure — the deep product page under the businesses catalog. */
    @GetMapping("/business/sales-rep")
    fun businessSalesRep(request: HttpServletRequest, model: Model): String {
        requirePublic(request)
        model.addAttribute("pageTitle", "AI Sales Rep")
        return "public/business-sales-rep"
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

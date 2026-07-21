package ai.vishwakarma.labelling.web

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.view.RedirectView

/**
 * The Collection Guides are plain static HTML under `static/user-guide/` (public — permitAll in
 * SecurityConfig). Static resources have no directory index, so the bare paths redirect to the
 * entry page, keeping the shareable URL short: `labelling.vishwakarma.ai/user-guide`.
 *
 * The guide mirrors the two-level landing: `index.html` is a thin door chooser, each door opens a
 * per-door use-case catalog (`individuals/index.html`, `business/index.html`), and each catalog
 * card opens that use-case's guide (`individuals/career-advocate/`, `business/sales-rep/`). The
 * three original flat pages now live under `individuals/career-advocate/`, so their old URLs are
 * kept alive as permanent (301) redirects for shared links and search engines. The old `index.html`
 * URL is intentionally not redirected — it now serves the chooser.
 */
@Controller
class UserGuideController {

    @GetMapping("/user-guide", "/user-guide/")
    fun index(): String = "redirect:/user-guide/index.html"

    @GetMapping("/user-guide/documents.html")
    fun documents(): RedirectView =
        movedTo("/user-guide/individuals/career-advocate/documents.html")

    @GetMapping("/user-guide/interviews.html")
    fun interviews(): RedirectView =
        movedTo("/user-guide/individuals/career-advocate/interviews.html")

    @GetMapping("/user-guide/authenticity-score.html")
    fun authenticityScore(): RedirectView =
        movedTo("/user-guide/individuals/career-advocate/authenticity-score.html")

    private fun movedTo(url: String): RedirectView =
        RedirectView(url).apply { setStatusCode(HttpStatus.MOVED_PERMANENTLY) }
}

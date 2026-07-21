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
 * The two-door restructure split the guide into `individuals/` (the original data-collection guide)
 * and `business/sales-rep/`, with `index.html` becoming a thin chooser. The three original flat
 * pages moved under `individuals/`, so their old URLs are kept alive as permanent (301) redirects
 * for shared links and search engines. The old `index.html` URL is intentionally not redirected —
 * it now serves the chooser.
 */
@Controller
class UserGuideController {

    @GetMapping("/user-guide", "/user-guide/")
    fun index(): String = "redirect:/user-guide/index.html"

    @GetMapping("/user-guide/documents.html")
    fun documents(): RedirectView = movedTo("/user-guide/individuals/documents.html")

    @GetMapping("/user-guide/interviews.html")
    fun interviews(): RedirectView = movedTo("/user-guide/individuals/interviews.html")

    @GetMapping("/user-guide/authenticity-score.html")
    fun authenticityScore(): RedirectView =
        movedTo("/user-guide/individuals/authenticity-score.html")

    private fun movedTo(url: String): RedirectView =
        RedirectView(url).apply { setStatusCode(HttpStatus.MOVED_PERMANENTLY) }
}

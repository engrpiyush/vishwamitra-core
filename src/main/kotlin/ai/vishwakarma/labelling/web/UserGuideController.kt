package ai.vishwakarma.labelling.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * The Data Collection Guide is plain static HTML under `static/user-guide/` (public — permitAll in
 * SecurityConfig). Static resources have no directory index, so the bare paths redirect to the
 * entry page, keeping the shareable URL short: `labelling.vishwakarma.ai/user-guide`.
 */
@Controller
class UserGuideController {

    @GetMapping("/user-guide", "/user-guide/")
    fun index(): String = "redirect:/user-guide/index.html"
}

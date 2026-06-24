package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.ExportService
import ai.vishwakarma.labelling.service.TaxonomyService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Reviewer/Admin: export APPROVED examples to JSONL snapshots in the training bucket. */
@Controller
@RequestMapping("/export")
@PreAuthorize("hasRole('REVIEWER')")
class ExportController(
    private val exportService: ExportService,
    private val taxonomy: TaxonomyService,
) {

    @GetMapping
    fun index(model: Model): String {
        model.addAttribute("pageTitle", "Export")
        model.addAttribute("history", exportService.history())
        model.addAttribute("taxonomy", taxonomy.get())
        model.addAttribute("kinds", ExportKind.entries)
        return "export/index"
    }

    @PostMapping("/run")
    fun run(
        @RequestParam kind: String,
        @RequestParam(required = false) skill: String?,
        @RequestParam(required = false) intent: String?,
        @RequestParam(required = false) language: String?,
        ra: RedirectAttributes,
    ): String {
        val parsed = runCatching { ExportKind.valueOf(kind.uppercase()) }.getOrNull()
        if (parsed == null) {
            ra.addFlashAttribute("error", "Invalid export kind")
            return "redirect:/export"
        }
        exportService
            .export(parsed, skill, intent, language, CurrentUser.email())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Exported ${it.count} ${parsed.name} examples → ${it.gcsUri}"
                    )
                },
            )
        return "redirect:/export"
    }
}

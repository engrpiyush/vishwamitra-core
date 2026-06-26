package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.ExportKind
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.ImportService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Reviewer/Admin: import an external JSONL dataset into the training bucket and validate it. */
@Controller
@RequestMapping("/import")
@PreAuthorize("hasRole('REVIEWER')")
class ImportController(private val importService: ImportService) {

    @GetMapping
    fun index(model: Model): String {
        model.addAttribute("pageTitle", "Import")
        model.addAttribute("imports", importService.list())
        model.addAttribute("kinds", ExportKind.entries)
        return "import/index"
    }

    @PostMapping("/run")
    fun run(
        @RequestParam kind: String,
        @RequestParam("file") file: MultipartFile,
        ra: RedirectAttributes,
    ): String {
        val parsed = runCatching { ExportKind.valueOf(kind.uppercase()) }.getOrNull()
        if (parsed == null) {
            ra.addFlashAttribute("error", "Invalid import kind")
            return "redirect:/import"
        }
        importService
            .import(parsed, file, CurrentUser.email())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Imported ${it.originalFilename} → ${it.gcsUri} (validating…)"
                    )
                },
            )
        return "redirect:/import"
    }

    @PostMapping("/{id}/revalidate")
    fun revalidate(@PathVariable id: String, ra: RedirectAttributes): String {
        importService
            .revalidate(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Re-validating…") },
            )
        return "redirect:/import"
    }

    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: String, ra: RedirectAttributes): String {
        importService
            .delete(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Import deleted") },
            )
        return "redirect:/import"
    }
}

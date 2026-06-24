package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.Promotion
import ai.vishwakarma.labelling.service.TrainingService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/models")
class ModelsController(private val training: TrainingService) {

    @GetMapping
    fun index(model: Model): String {
        model.addAttribute("pageTitle", "Models")
        model.addAttribute("families", training.versionsByFamily())
        return "models/index"
    }

    @GetMapping("/{id}/serve")
    fun serve(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val version =
            training.version(id)
                ?: run {
                    ra.addFlashAttribute("error", "Version not found")
                    return "redirect:/models"
                }
        model.addAttribute("pageTitle", "Serve ${version.displayName}")
        model.addAttribute("v", version)
        model.addAttribute("serveCommand", training.serveCommand(version))
        return "models/serve"
    }

    @PostMapping("/{id}/promote")
    @PreAuthorize("hasRole('REVIEWER')")
    fun promote(
        @PathVariable id: String,
        @RequestParam target: String,
        ra: RedirectAttributes
    ): String {
        val promotion = runCatching { Promotion.valueOf(target.uppercase()) }.getOrNull()
        if (promotion == null) {
            ra.addFlashAttribute("error", "Invalid promotion target")
            return "redirect:/models"
        }
        training
            .promote(id, promotion)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "${it.displayName} → ${it.promotion}") },
            )
        return "redirect:/models"
    }
}

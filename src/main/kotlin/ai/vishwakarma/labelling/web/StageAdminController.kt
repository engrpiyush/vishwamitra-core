package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.StageKey
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.StageConfigService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The per-stage console pages (LLD §14A.4(2), the 4Cs): `/admin/stages/{1..4}` with two tabs —
 * **Configuration** (VA-83: the live-config store form over the stage's `app.*` block) and
 * **Prompts** (VA-84: the stage's slice of the versioned `extraction_prompts` rows; the prompt POST
 * endpoints stay on [AdminController] and bounce back here via `returnTo`).
 */
@Controller
@RequestMapping("/admin/stages")
@PreAuthorize("hasRole('ADMIN')")
class StageAdminController(
    private val config: StageConfigService,
    private val extractionPrompts: ExtractionPromptService,
) {

    private fun actor() = CurrentUser.email()

    private fun stageOr404(num: Int): StageKey =
        StageKey.fromNum(num)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No stage $num")

    private fun Model.stageCommons(stage: StageKey, tab: String) {
        addAttribute("pageTitle", "Stage ${stage.num} · ${stage.title}")
        addAttribute("section", stage.id)
        addAttribute("stage", stage)
        addAttribute("tab", tab)
    }

    // ---- Configuration tab -----------------------------------------------------

    @GetMapping("/{num}")
    fun configTab(@PathVariable num: Int, model: Model): String {
        val stage = stageOr404(num)
        val views = config.fieldViews(stage)
        model.stageCommons(stage, "config")
        model.addAttribute("groupedViews", views.groupBy { it.field.group })
        model.addAttribute("configDoc", config.doc(stage))
        model.addAttribute("overrideCount", views.count { it.overridden })
        return "admin/stage"
    }

    /** Save the whole form; only deviations from the bootstrap default are stored. */
    @PostMapping("/{num}/config")
    fun saveConfig(
        @PathVariable num: Int,
        @RequestParam form: Map<String, String>,
        ra: RedirectAttributes,
    ): String {
        val stage = stageOr404(num)
        config
            .update(stage, form, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Stage ${stage.num} configuration saved (v${it.version}, " +
                            "${it.overrides.size} override(s) live)",
                    )
                },
            )
        return "redirect:/admin/stages/$num"
    }

    @PostMapping("/{num}/config/reset")
    fun resetConfig(@PathVariable num: Int, ra: RedirectAttributes): String {
        val stage = stageOr404(num)
        val doc = config.reset(stage, actor())
        ra.addFlashAttribute(
            "ok",
            "Stage ${stage.num} reset to bootstrap defaults (v${doc.version})"
        )
        return "redirect:/admin/stages/$num"
    }

    // ---- Prompts tab -------------------------------------------------------------

    @GetMapping("/{num}/prompts")
    fun promptsTab(@PathVariable num: Int, model: Model): String {
        val stage = stageOr404(num)
        model.stageCommons(stage, "prompts")
        when (stage) {
            StageKey.STAGE1 -> Unit // Collection has no LLM prompts.
            StageKey.STAGE2 -> model.addAttribute("groups", extractionPrompts.list())
            StageKey.STAGE3 -> model.addAttribute("stage3Rows", extractionPrompts.listStage3())
            StageKey.STAGE4 -> model.addAttribute("stage4", extractionPrompts.listStage4())
        }
        return "admin/stage"
    }
}

package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.CatalogService
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.DraftingService
import ai.vishwakarma.labelling.service.ScenarioService
import ai.vishwakarma.labelling.service.SftService
import ai.vishwakarma.labelling.service.TaxonomyService
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
@RequestMapping("/sft")
@PreAuthorize("hasRole('AUTHOR')")
class SftController(
    private val sft: SftService,
    private val taxonomy: TaxonomyService,
    private val catalog: CatalogService,
    private val drafting: DraftingService,
    private val scenarios: ScenarioService,
) {

    private fun actor() = CurrentUser.email()

    @GetMapping
    fun list(@RequestParam(required = false) status: String?, model: Model): String {
        val parsed = status?.let { runCatching { ExampleStatus.valueOf(it.uppercase()) }.getOrNull() }
        model.addAttribute("pageTitle", "SFT")
        model.addAttribute("examples", sft.list(parsed))
        model.addAttribute("statuses", ExampleStatus.entries)
        model.addAttribute("activeStatus", parsed?.name)
        model.addAttribute("scenarios", scenarios.list())
        model.addAttribute("draftProvider", drafting.activeProviderId())
        return "sft/list"
    }

    @PostMapping("/new")
    fun create(ra: RedirectAttributes): String {
        val draft = sft.createDraft(actor())
        return "redirect:/sft/${draft.id}"
    }

    @PostMapping("/new-from-scenario")
    fun createFromScenario(@RequestParam scenarioId: String, ra: RedirectAttributes): String {
        val scenario = scenarios.get(scenarioId)
        val draft = sft.createDraft(actor())
        val tags = ExampleTags(skill = scenario?.skill, intent = scenario?.intent)
        drafting.draftConversation(scenario, tags).fold(
            { ra.addFlashAttribute("error", "${it.message} (created an empty draft)") },
            { turns ->
                sft.replaceTurns(draft.id, turns)
                sft.updateTags(draft.id, tags.skill, tags.intent, tags.language)
                ra.addFlashAttribute("ok", "Drafted ${turns.size} turns via ${drafting.activeProviderId()}")
            },
        )
        return "redirect:/sft/${draft.id}"
    }

    @PostMapping("/{id}/draft-next")
    fun draftNext(@PathVariable id: String, ra: RedirectAttributes): String {
        val ex = sft.get(id)
        if (ex == null) {
            ra.addFlashAttribute("error", "Example not found")
            return "redirect:/sft"
        }
        drafting.draftNextTurn(ex.turns).fold(
            { ra.addFlashAttribute("error", it.message) },
            { turn -> sft.appendTurn(id, turn); ra.addFlashAttribute("ok", "Drafted next turn") },
        )
        return "redirect:/sft/$id"
    }

    @GetMapping("/{id}")
    fun edit(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val example = sft.get(id) ?: run {
            ra.addFlashAttribute("error", "Example not found")
            return "redirect:/sft"
        }
        model.addAttribute("pageTitle", "Edit SFT")
        model.addAttribute("ex", example)
        model.addAttribute("taxonomy", taxonomy.get())
        model.addAttribute("tools", catalog.list(includeDeprecated = false))
        model.addAttribute("errors", sft.validate(example))
        model.addAttribute("preview", sft.preview(example))
        model.addAttribute("draftProvider", drafting.activeProviderId())
        return "sft/edit"
    }

    @PostMapping("/{id}/tags")
    fun tags(
        @PathVariable id: String,
        @RequestParam(required = false) skill: String?,
        @RequestParam(required = false) intent: String?,
        @RequestParam(required = false) language: String?,
        ra: RedirectAttributes,
    ): String {
        sft.updateTags(id, skill, intent, language).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/turns")
    fun addTurn(
        @PathVariable id: String,
        @RequestParam role: String,
        @RequestParam kind: String,
        ra: RedirectAttributes,
    ): String {
        sft.addTurn(
            id,
            runCatching { TurnRole.valueOf(role.uppercase()) }.getOrDefault(TurnRole.USER),
            runCatching { TurnKind.valueOf(kind.uppercase()) }.getOrDefault(TurnKind.TEXT),
        ).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/turns/{index}")
    fun updateTurn(
        @PathVariable id: String,
        @PathVariable index: Int,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) toolName: String?,
        @RequestParam(required = false) argsJson: String?,
        @RequestParam(required = false) resultJson: String?,
        ra: RedirectAttributes,
    ): String {
        sft.updateTurn(id, index, text, toolName, argsJson, resultJson).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/turns/{index}/delete")
    fun deleteTurn(@PathVariable id: String, @PathVariable index: Int, ra: RedirectAttributes): String {
        sft.deleteTurn(id, index).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/turns/{index}/move")
    fun moveTurn(@PathVariable id: String, @PathVariable index: Int, @RequestParam delta: Int, ra: RedirectAttributes): String {
        sft.moveTurn(id, index, delta).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/submit")
    fun submit(@PathVariable id: String, ra: RedirectAttributes): String {
        sft.submit(id, actor()).fold({ ra.addFlashAttribute("error", it.message) }, { ra.addFlashAttribute("ok", "Submitted for review") })
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    fun approve(@PathVariable id: String, ra: RedirectAttributes): String {
        sft.approve(id, actor()).fold({ ra.addFlashAttribute("error", it.message) }, { ra.addFlashAttribute("ok", "Approved") })
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/sendback")
    @PreAuthorize("hasRole('REVIEWER')")
    fun sendBack(@PathVariable id: String, @RequestParam comment: String, ra: RedirectAttributes): String {
        sft.sendBack(id, actor(), comment).fold({ ra.addFlashAttribute("error", it.message) }, { ra.addFlashAttribute("ok", "Sent back to author") })
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/comment")
    fun comment(@PathVariable id: String, @RequestParam text: String, ra: RedirectAttributes): String {
        sft.addComment(id, actor(), text).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('REVIEWER')")
    fun archive(@PathVariable id: String, ra: RedirectAttributes): String {
        sft.archive(id).notify(ra)
        return "redirect:/sft/$id"
    }

    // HTMX panels
    @GetMapping("/{id}/validate")
    fun validate(@PathVariable id: String, model: Model): String {
        val ex = sft.get(id)
        model.addAttribute("errors", ex?.let { sft.validate(it) } ?: listOf("Not found"))
        return "sft/fragments :: validation"
    }

    @GetMapping("/{id}/preview")
    fun preview(@PathVariable id: String, model: Model): String {
        val ex = sft.get(id)
        model.addAttribute("preview", ex?.let { sft.preview(it) } ?: "")
        return "sft/fragments :: preview"
    }

    private fun Any?.notify(ra: RedirectAttributes) {
        this?.let {
            (it as? arrow.core.Either<*, *>)?.fold(
                { err -> ra.addFlashAttribute("error", (err as? DomainError)?.message ?: "Error") },
                { ra.addFlashAttribute("ok", "Saved") },
            )
        }
    }
}

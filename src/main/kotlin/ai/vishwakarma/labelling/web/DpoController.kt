package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.splitLabels
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.DpoService
import ai.vishwakarma.labelling.service.DraftingService
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
@RequestMapping("/dpo")
@PreAuthorize("hasRole('AUTHOR')")
class DpoController(
    private val dpo: DpoService,
    private val taxonomy: TaxonomyService,
    private val drafting: DraftingService,
) {

    private fun actor() = CurrentUser.email()

    @GetMapping
    fun list(@RequestParam(required = false) status: String?, model: Model): String {
        val parsed =
            status?.let { runCatching { ExampleStatus.valueOf(it.uppercase()) }.getOrNull() }
        model.addAttribute("pageTitle", "DPO")
        model.addAttribute("pairs", dpo.list(parsed))
        model.addAttribute("statuses", ExampleStatus.entries)
        model.addAttribute("activeStatus", parsed?.name)
        model.addAttribute("seedable", dpo.seedableSft())
        return "dpo/list"
    }

    @PostMapping("/new")
    fun create(ra: RedirectAttributes): String {
        val draft = dpo.createManualDraft(actor())
        return "redirect:/dpo/${draft.id}"
    }

    @PostMapping("/new-from-sft")
    fun createFromSft(@RequestParam sftId: String, ra: RedirectAttributes): String {
        return dpo.createFromSft(sftId, actor())
            .fold(
                {
                    ra.addFlashAttribute("error", it.message)
                    "redirect:/dpo"
                },
                { "redirect:/dpo/${it.id}" },
            )
    }

    @GetMapping("/{id}")
    fun edit(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val pair =
            dpo.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Pair not found")
                    return "redirect:/dpo"
                }
        model.addAttribute("pageTitle", "Edit DPO")
        model.addAttribute("p", pair)
        model.addAttribute("taxonomy", taxonomy.get())
        model.addAttribute("errors", dpo.validate(pair))
        model.addAttribute("preview", dpo.preview(pair))
        model.addAttribute("draftProvider", drafting.activeProviderId())
        return "dpo/edit"
    }

    @PostMapping("/{id}/draft-candidates")
    fun draftCandidates(@PathVariable id: String, ra: RedirectAttributes): String {
        val pair = dpo.get(id)
        if (pair == null) {
            ra.addFlashAttribute("error", "Pair not found")
            return "redirect:/dpo"
        }
        drafting
            .draftTwoCandidates(pair.promptTurns)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { c ->
                    dpo.setCandidates(id, c.a, c.b)
                    ra.addFlashAttribute(
                        "ok",
                        "Drafted 2 candidates via ${drafting.activeProviderId()}"
                    )
                },
            )
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/prompt")
    fun prompt(
        @PathVariable id: String,
        @RequestParam promptText: String,
        ra: RedirectAttributes
    ): String {
        dpo.setPrompt(id, promptText).notify(ra)
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/candidates")
    fun candidates(
        @PathVariable id: String,
        @RequestParam chosen: String,
        @RequestParam rejected: String,
        ra: RedirectAttributes
    ): String {
        dpo.setCandidates(id, chosen, rejected).notify(ra)
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/swap")
    fun swap(@PathVariable id: String, ra: RedirectAttributes): String {
        dpo.swapCandidates(id).notify(ra)
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/tags")
    fun tags(
        @PathVariable id: String,
        @RequestParam(required = false) claimType: String?,
        @RequestParam(required = false) authenticityTier: String?,
        @RequestParam(required = false) labels: String?,
        ra: RedirectAttributes,
    ): String {
        dpo.updateTags(
                id,
                ClaimType.fromOrNull(claimType),
                AuthenticityTier.fromOrNull(authenticityTier),
                splitLabels(labels),
            )
            .notify(ra)
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/submit")
    fun submit(@PathVariable id: String, ra: RedirectAttributes): String {
        dpo.submit(id, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Submitted for review") }
            )
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    fun approve(@PathVariable id: String, ra: RedirectAttributes): String {
        dpo.approve(id, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Approved") }
            )
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/sendback")
    @PreAuthorize("hasRole('REVIEWER')")
    fun sendBack(
        @PathVariable id: String,
        @RequestParam comment: String,
        ra: RedirectAttributes
    ): String {
        dpo.sendBack(id, actor(), comment)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Sent back to author") }
            )
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/comment")
    fun comment(
        @PathVariable id: String,
        @RequestParam text: String,
        ra: RedirectAttributes
    ): String {
        dpo.addComment(id, actor(), text).notify(ra)
        return "redirect:/dpo/$id"
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('REVIEWER')")
    fun archive(@PathVariable id: String, ra: RedirectAttributes): String {
        dpo.archive(id).notify(ra)
        return "redirect:/dpo/$id"
    }

    @GetMapping("/{id}/validate")
    fun validate(@PathVariable id: String, model: Model): String {
        val p = dpo.get(id)
        model.addAttribute("errors", p?.let { dpo.validate(it) } ?: listOf("Not found"))
        return "sft/fragments :: validation"
    }

    @GetMapping("/{id}/preview")
    fun preview(@PathVariable id: String, model: Model): String {
        val p = dpo.get(id)
        model.addAttribute("preview", p?.let { dpo.preview(it) } ?: "")
        return "sft/fragments :: preview"
    }

    private fun Any?.notify(ra: RedirectAttributes) {
        (this as? arrow.core.Either<*, *>)?.fold(
            { err -> ra.addFlashAttribute("error", (err as? DomainError)?.message ?: "Error") },
            { ra.addFlashAttribute("ok", "Saved") },
        )
    }
}

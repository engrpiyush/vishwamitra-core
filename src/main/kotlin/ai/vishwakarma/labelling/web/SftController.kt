package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.JudgeVerdict
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Stage4Stamp
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.domain.splitLabels
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.CatalogService
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.DraftingService
import ai.vishwakarma.labelling.service.ScenarioService
import ai.vishwakarma.labelling.service.SftService
import ai.vishwakarma.labelling.service.Stage4Service
import ai.vishwakarma.labelling.service.SubjectService
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
    private val subjects: SubjectService,
    private val stage4Panels: Stage4ReviewPanels,
    private val stage4: Stage4Service,
) {

    private fun actor() = CurrentUser.email()

    /**
     * The queue, VA-65 semantics: optional `subject` facet (Stage 4 run-page hand-off), stale-stamp
     * rows excluded from every default view (`stale=true` shows exactly them), ARCHIVED excluded
     * from "All", and the QA-4 pre-sort — FAIL → BORDERLINE → PASS → unjudged/legacy — so reviewer
     * attention lands on the judge's rejects first. Legacy (stamp-less) examples ride along
     * unchanged: never stale, never verdict-ranked ahead of judged work.
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false, defaultValue = "false") stale: Boolean,
        model: Model,
    ): String {
        val parsed =
            status?.let { runCatching { ExampleStatus.valueOf(it.uppercase()) }.getOrNull() }
        var examples = sft.list(parsed)
        if (!subject.isNullOrBlank()) {
            examples = examples.filter { it.stamp?.subjectId == subject }
        }
        // Staleness against the subject's CURRENT publish + resolved persona, computed once per
        // distinct subject (the resolve reads Firestore).
        val current =
            examples
                .mapNotNull { it.stamp?.subjectId }
                .distinct()
                .associateWith { stage4Panels.currentFor(it) }
        // A concrete HashSet, never Kotlin's EmptySet singleton: the template's SpEL
        // `staleIds.contains(...)` resolves reflectively, and Set<Nothing> breaks it.
        val staleIds =
            examples
                .filter { ex -> ex.stamp?.let { isStale(it, current) } == true }
                .mapTo(HashSet()) { it.id }
        val visible =
            when {
                // The stale triage view: exactly the rows the next SELECT tick would archive.
                stale -> examples.filter { it.id in staleIds }
                // ARCHIVED is its own facet; everywhere else stale rows are noise (QA-6).
                parsed == ExampleStatus.ARCHIVED -> examples
                parsed == null ->
                    examples.filter { it.status != ExampleStatus.ARCHIVED && it.id !in staleIds }
                else -> examples.filter { it.id !in staleIds }
            }
        model.addAttribute("pageTitle", "SFT")
        model.addAttribute(
            "examples",
            visible.sortedWith(
                compareBy<SftExample> { verdictRank(it) }.thenByDescending { it.updatedAt }
            ),
        )
        model.addAttribute("statuses", ExampleStatus.entries)
        model.addAttribute("activeStatus", parsed?.name)
        model.addAttribute("staleView", stale)
        model.addAttribute("staleCount", staleIds.size)
        model.addAttribute("staleIds", staleIds)
        model.addAttribute("subjectFilter", subject?.takeIf { it.isNotBlank() })
        model.addAttribute(
            "subjectFilterName",
            subject?.takeIf { it.isNotBlank() }?.let { subjects.get(it)?.displayName },
        )
        model.addAttribute("scenarios", scenarios.list())
        model.addAttribute("draftProvider", drafting.activeProviderId())
        return "sft/list"
    }

    /** FAIL first, then BORDERLINE, PASS, and unjudged/legacy last (QA-4 pre-sort). */
    private fun verdictRank(example: SftExample): Int =
        when (example.judgeVerdict) {
            JudgeVerdict.FAIL -> 0
            JudgeVerdict.BORDERLINE -> 1
            JudgeVerdict.PASS -> 2
            null -> 3
        }

    private fun isStale(stamp: Stage4Stamp, current: Map<String, CurrentStamp>): Boolean {
        val c = current[stamp.subjectId] ?: return false
        return c.scoreRunId == null ||
            stamp.scoreRunId != c.scoreRunId ||
            stamp.personaHash != c.personaHash
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
        val tags =
            ExampleTags(claimType = scenario?.claimType, labels = scenario?.labels ?: emptyList())
        drafting
            .draftConversation(scenario, tags)
            .fold(
                { ra.addFlashAttribute("error", "${it.message} (created an empty draft)") },
                { turns ->
                    sft.replaceTurns(draft.id, turns)
                    sft.updateTags(draft.id, tags.claimType, tags.authenticityTier, tags.labels)
                    ra.addFlashAttribute(
                        "ok",
                        "Drafted ${turns.size} turns via ${drafting.activeProviderId()}"
                    )
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
        drafting
            .draftNextTurn(ex.turns)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { turn ->
                    sft.appendTurn(id, turn)
                    ra.addFlashAttribute("ok", "Drafted next turn")
                },
            )
        return "redirect:/sft/$id"
    }

    @GetMapping("/{id}")
    fun edit(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val example =
            sft.get(id)
                ?: run {
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
        // VA-65: the Stage 4 panels — all null on legacy (stamp-less) examples, which render
        // exactly as before.
        val stamp = example.stamp
        model.addAttribute("stampChips", stamp?.let { stage4Panels.stampChips(it) })
        model.addAttribute(
            "judgePanel",
            stamp?.let { stage4Panels.judgePanel(example.id, example.turns) },
        )
        model.addAttribute("planPanel", stamp?.let { stage4Panels.planPanel(it) })
        model.addAttribute(
            "subjectName",
            stamp?.let { subjects.get(it.subjectId)?.displayName },
        )
        return "sft/edit"
    }

    @PostMapping("/{id}/tags")
    fun tags(
        @PathVariable id: String,
        @RequestParam(required = false) claimType: String?,
        @RequestParam(required = false) authenticityTier: String?,
        @RequestParam(required = false) labels: String?,
        ra: RedirectAttributes,
    ): String {
        sft.updateTags(
                id,
                ClaimType.fromOrNull(claimType),
                AuthenticityTier.fromOrNull(authenticityTier),
                splitLabels(labels),
            )
            .notify(ra)
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
            )
            .notify(ra)
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
    fun deleteTurn(
        @PathVariable id: String,
        @PathVariable index: Int,
        ra: RedirectAttributes
    ): String {
        sft.deleteTurn(id, index).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/turns/{index}/move")
    fun moveTurn(
        @PathVariable id: String,
        @PathVariable index: Int,
        @RequestParam delta: Int,
        ra: RedirectAttributes
    ): String {
        sft.moveTurn(id, index, delta).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/submit")
    fun submit(@PathVariable id: String, ra: RedirectAttributes): String {
        sft.submit(id, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Submitted for review") }
            )
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    fun approve(@PathVariable id: String, ra: RedirectAttributes): String {
        sft.approve(id, actor())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Approved") }
            )
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/sendback")
    @PreAuthorize("hasRole('REVIEWER')")
    fun sendBack(
        @PathVariable id: String,
        @RequestParam comment: String,
        ra: RedirectAttributes
    ): String {
        sft.sendBack(id, actor(), comment)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Sent back to author") }
            )
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/comment")
    fun comment(
        @PathVariable id: String,
        @RequestParam text: String,
        ra: RedirectAttributes
    ): String {
        sft.addComment(id, actor(), text).notify(ra)
        return "redirect:/sft/$id"
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('REVIEWER')")
    fun archive(@PathVariable id: String, ra: RedirectAttributes): String {
        sft.archive(id).notify(ra)
        return "redirect:/sft/$id"
    }

    /**
     * ADMIN override (VA-176): approve the selected judge-FAIL/BORDERLINE examples over the verdict
     * so they can flow to export/training. **ADMIN-only, enforced here** — a method-level gate over
     * the class AUTHOR rule, the same way [approve]/[sendBack]/[archive] raise to REVIEWER; a
     * non-admin is refused with 403 before the body runs. The loud "this bypasses the judge"
     * confirm lives in the template; the counts outcome lands as a flash, mirroring the run-page
     * bulk-approve. Filters are re-appended so the ADMIN lands back on the view they acted from.
     */
    @PostMapping("/override-approve")
    @PreAuthorize("hasRole('ADMIN')")
    fun overrideApprove(
        @RequestParam(name = "ids", required = false) ids: List<String>?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false, defaultValue = "false") stale: Boolean,
        ra: RedirectAttributes,
    ): String {
        val outcome = stage4.overrideApprove(ids ?: emptyList(), actor())
        ra.addFlashAttribute(
            "ok",
            "Override-approved ${outcome.approved} example(s) over the judge verdict — skipped: " +
                "${outcome.notFailBorderline} not fail/borderline, ${outcome.ineligibleStatus} " +
                "ineligible status, ${outcome.stale} stale, ${outcome.notFound} not found",
        )
        status?.takeIf { it.isNotBlank() }?.let { ra.addAttribute("status", it) }
        subject?.takeIf { it.isNotBlank() }?.let { ra.addAttribute("subject", it) }
        if (stale) ra.addAttribute("stale", "true")
        return "redirect:/sft"
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

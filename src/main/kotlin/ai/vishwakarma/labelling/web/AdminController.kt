package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateName
import ai.vishwakarma.labelling.domain.AdvocateRegion
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.ToolParam
import ai.vishwakarma.labelling.domain.ToolStatus
import ai.vishwakarma.labelling.domain.splitLabels
import ai.vishwakarma.labelling.persistence.AdvocateNameRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.BaseModelService
import ai.vishwakarma.labelling.service.CatalogService
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.ProviderService
import ai.vishwakarma.labelling.service.ScenarioService
import ai.vishwakarma.labelling.service.TaxonomyService
import ai.vishwakarma.labelling.service.UserService
import java.time.Instant
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Admin-only management of the catalogs. URL + method security both require ADMIN. */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminController(
    private val catalog: CatalogService,
    private val baseModels: BaseModelService,
    private val taxonomy: TaxonomyService,
    private val scenarios: ScenarioService,
    private val users: UserService,
    private val providers: ProviderService,
    private val extractionPrompts: ExtractionPromptService,
    private val advocateNames: AdvocateNameRepository,
) {

    private fun actor() = CurrentUser.email()

    private fun RedirectAttributes.notify(result: Any?) {
        when (result) {
            is DomainError -> addFlashAttribute("error", result.message)
            else -> addFlashAttribute("ok", "Saved")
        }
    }

    @GetMapping fun index() = "redirect:/admin/tools"

    // ---- Tools -------------------------------------------------------------
    @GetMapping("/tools")
    fun tools(model: Model): String {
        model.addAttribute("pageTitle", "Tools")
        model.addAttribute("section", "tools")
        model.addAttribute("tools", catalog.list())
        return "admin/tools"
    }

    @PostMapping("/tools")
    fun createTool(
        @RequestParam name: String,
        @RequestParam(required = false, defaultValue = "") description: String,
        @RequestParam(required = false, defaultValue = "") params: String,
        ra: RedirectAttributes,
    ): String {
        val parsed = parseParams(params)
        catalog
            .create(name, description, parsed, ToolStatus.ACTIVE, actor())
            .fold(
                { ra.notify(it) },
                { ra.addFlashAttribute("ok", "Tool '${it.name}' created") },
            )
        return "redirect:/admin/tools"
    }

    @PostMapping("/tools/{id}/status")
    fun toggleToolStatus(@PathVariable id: String, ra: RedirectAttributes): String {
        catalog
            .update(
                id,
                {
                    it.copy(
                        status =
                            if (it.status == ToolStatus.ACTIVE) ToolStatus.DEPRECATED
                            else ToolStatus.ACTIVE
                    )
                },
                actor()
            )
            .fold({ ra.notify(it) }, { ra.addFlashAttribute("ok", "Status updated") })
        return "redirect:/admin/tools"
    }

    @PostMapping("/tools/{id}/delete")
    fun deleteTool(@PathVariable id: String, ra: RedirectAttributes): String {
        catalog.delete(id)
        ra.addFlashAttribute("ok", "Tool deleted")
        return "redirect:/admin/tools"
    }

    /** Parse a textarea: one param per line as `name|type|required|description`. */
    private fun parseParams(raw: String): List<ToolParam> =
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("|").map { it.trim() }
                ToolParam(
                    name = parts.getOrElse(0) { "" },
                    type = parts.getOrElse(1) { "string" }.ifBlank { "string" },
                    required = parts.getOrElse(2) { "true" }.lowercase() != "false",
                    desc = parts.getOrElse(3) { "" },
                )
            }
            .filter { it.name.isNotBlank() }

    // ---- Base models -------------------------------------------------------
    @GetMapping("/base-models")
    fun baseModels(model: Model): String {
        model.addAttribute("pageTitle", "Base models")
        model.addAttribute("section", "base-models")
        model.addAttribute("baseModels", baseModels.list())
        return "admin/base-models"
    }

    @PostMapping("/base-models")
    fun createBaseModel(
        @RequestParam publisherModel: String,
        @RequestParam displayName: String,
        @RequestParam family: String,
        @RequestParam(required = false, defaultValue = "true") active: Boolean,
        ra: RedirectAttributes,
    ): String {
        baseModels
            .create(publisherModel, displayName, family, active, actor())
            .fold(
                { ra.notify(it) },
                { ra.addFlashAttribute("ok", "Base model '${it.family}' created") }
            )
        return "redirect:/admin/base-models"
    }

    @PostMapping("/base-models/{id}/active")
    fun toggleBaseModel(
        @PathVariable id: String,
        @RequestParam active: Boolean,
        ra: RedirectAttributes
    ): String {
        baseModels
            .setActive(id, active, actor())
            .fold({ ra.notify(it) }, { ra.addFlashAttribute("ok", "Updated") })
        return "redirect:/admin/base-models"
    }

    @PostMapping("/base-models/{id}/delete")
    fun deleteBaseModel(@PathVariable id: String, ra: RedirectAttributes): String {
        baseModels.delete(id)
        ra.addFlashAttribute("ok", "Base model deleted")
        return "redirect:/admin/base-models"
    }

    // ---- Taxonomy ----------------------------------------------------------
    @GetMapping("/taxonomy")
    fun taxonomy(model: Model): String {
        model.addAttribute("pageTitle", "Taxonomy")
        model.addAttribute("section", "taxonomy")
        model.addAttribute("taxonomy", taxonomy.get())
        return "admin/taxonomy"
    }

    @PostMapping("/taxonomy/add")
    fun addLabel(@RequestParam term: String, ra: RedirectAttributes): String {
        taxonomy
            .addLabel(term)
            .fold({ ra.notify(it) }, { ra.addFlashAttribute("ok", "Added '$term'") })
        return "redirect:/admin/taxonomy"
    }

    @PostMapping("/taxonomy/remove")
    fun removeLabel(@RequestParam term: String, ra: RedirectAttributes): String {
        taxonomy.removeLabel(term)
        ra.addFlashAttribute("ok", "Removed '$term'")
        return "redirect:/admin/taxonomy"
    }

    // ---- Scenarios ---------------------------------------------------------
    @GetMapping("/scenarios")
    fun scenarios(model: Model): String {
        model.addAttribute("pageTitle", "Scenarios")
        model.addAttribute("section", "scenarios")
        model.addAttribute("scenarios", scenarios.list())
        model.addAttribute("taxonomy", taxonomy.get())
        return "admin/scenarios"
    }

    @PostMapping("/scenarios")
    fun createScenario(
        @RequestParam title: String,
        @RequestParam(required = false, defaultValue = "") description: String,
        @RequestParam(required = false) claimType: String?,
        @RequestParam(required = false) labels: String?,
        @RequestParam(required = false, defaultValue = "") promptTemplate: String,
        ra: RedirectAttributes,
    ): String {
        scenarios
            .create(
                title,
                description,
                ClaimType.fromOrNull(claimType),
                splitLabels(labels),
                promptTemplate,
                actor(),
            )
            .fold({ ra.notify(it) }, { ra.addFlashAttribute("ok", "Scenario created") })
        return "redirect:/admin/scenarios"
    }

    @PostMapping("/scenarios/{id}/delete")
    fun deleteScenario(@PathVariable id: String, ra: RedirectAttributes): String {
        scenarios.delete(id)
        ra.addFlashAttribute("ok", "Scenario deleted")
        return "redirect:/admin/scenarios"
    }

    // ---- Users -------------------------------------------------------------
    @GetMapping("/users")
    fun users(model: Model): String {
        model.addAttribute("pageTitle", "Users")
        model.addAttribute("section", "users")
        model.addAttribute("users", users.list())
        model.addAttribute("roles", Role.entries)
        return "admin/users"
    }

    @PostMapping("/users")
    fun upsertUser(
        @RequestParam email: String,
        @RequestParam role: String,
        ra: RedirectAttributes
    ): String {
        val parsedRole = Role.fromOrNull(role)
        if (email.isBlank() || parsedRole == null) {
            ra.addFlashAttribute("error", "Valid email and role are required")
        } else {
            users.upsert(email.trim().lowercase(), parsedRole, actor())
            ra.addFlashAttribute("ok", "User saved")
        }
        return "redirect:/admin/users"
    }

    @PostMapping("/users/{email}/active")
    fun toggleUser(
        @PathVariable email: String,
        @RequestParam active: Boolean,
        ra: RedirectAttributes
    ): String {
        users.setActive(email, active)
        ra.addFlashAttribute("ok", "User updated")
        return "redirect:/admin/users"
    }

    @PostMapping("/users/{email}/delete")
    fun deleteUser(@PathVariable email: String, ra: RedirectAttributes): String {
        users.remove(email)
        ra.addFlashAttribute("ok", "User removed")
        return "redirect:/admin/users"
    }

    // ---- Providers ---------------------------------------------------------
    @GetMapping("/providers")
    fun providers(model: Model): String {
        model.addAttribute("pageTitle", "Providers")
        model.addAttribute("section", "providers")
        model.addAttribute("providers", providers.list())
        return "admin/providers"
    }

    @PostMapping("/providers/{id}")
    fun updateProvider(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "false") enabled: Boolean,
        @RequestParam(required = false, defaultValue = "") model: String,
        ra: RedirectAttributes,
    ): String {
        providers.update(id, enabled, model, actor())
        ra.addFlashAttribute("ok", "Provider '$id' updated")
        return "redirect:/admin/providers"
    }

    // ---- Extraction prompts --------------------------------------------------
    @GetMapping("/extraction-prompts")
    fun extractionPrompts(model: Model): String {
        model.addAttribute("pageTitle", "Extraction prompts")
        model.addAttribute("section", "extraction-prompts")
        model.addAttribute("groups", extractionPrompts.list())
        model.addAttribute("stage3Rows", extractionPrompts.listStage3())
        model.addAttribute("stage4", extractionPrompts.listStage4())
        return "admin/extraction-prompts"
    }

    @PostMapping("/extraction-prompts/{id}")
    fun updateExtractionPrompt(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "") instructions: String,
        ra: RedirectAttributes,
    ): String {
        val known =
            extractionPrompts.isStage3Key(id) ||
                extractionPrompts.isStage4Key(id) ||
                ContentType.fromOrNull(id) != null
        when {
            !known -> ra.addFlashAttribute("error", "Unknown prompt '$id'")
            instructions.isBlank() ->
                ra.addFlashAttribute(
                    "error",
                    "Instructions cannot be blank — use Reset to revert $id to the code default",
                )
            else -> {
                val saved = extractionPrompts.updateKey(id, instructions, actor())
                ra.addFlashAttribute("ok", "$id prompt saved (v${saved.version})")
            }
        }
        return "redirect:/admin/extraction-prompts"
    }

    @PostMapping("/extraction-prompts/{id}/reset")
    fun resetExtractionPrompt(@PathVariable id: String, ra: RedirectAttributes): String {
        if (
            !extractionPrompts.isStage3Key(id) &&
                !extractionPrompts.isStage4Key(id) &&
                ContentType.fromOrNull(id) == null
        ) {
            ra.addFlashAttribute("error", "Unknown prompt '$id'")
        } else {
            extractionPrompts.resetKey(id)
            ra.addFlashAttribute("ok", "$id reverted to the code default")
        }
        return "redirect:/admin/extraction-prompts"
    }

    /** VA-66: the admin-designated default preset — the `stage4:preset-default` pointer row. */
    @PostMapping("/extraction-prompts/stage4-preset-default")
    fun setStage4DefaultPreset(@RequestParam presetId: String, ra: RedirectAttributes): String {
        val ids = extractionPrompts.listStage4().presetIds
        if (presetId !in ids) {
            ra.addFlashAttribute("error", "Unknown preset '$presetId'")
        } else {
            extractionPrompts.updateKey(
                ExtractionPromptService.STAGE4_PRESET_DEFAULT_KEY,
                presetId,
                actor(),
            )
            ra.addFlashAttribute("ok", "Default preset is now '$presetId'")
        }
        return "redirect:/admin/extraction-prompts"
    }

    /** VA-66: register a new admin preset (`stage4:preset:<slug>`). */
    @PostMapping("/extraction-prompts/stage4-preset")
    fun createStage4Preset(
        @RequestParam name: String,
        @RequestParam(required = false, defaultValue = "") instructions: String,
        ra: RedirectAttributes,
    ): String {
        runCatching { extractionPrompts.createStage4Preset(name, instructions, actor()) }
            .fold(
                { saved -> ra.addFlashAttribute("ok", "Preset '${saved.id}' created") },
                { ra.addFlashAttribute("error", it.message ?: "Could not create the preset") },
            )
        return "redirect:/admin/extraction-prompts"
    }

    // ---- Advocate names (VA-66, QA on A2) -------------------------------------
    @GetMapping("/advocate-names")
    fun advocateNamesPage(model: Model): String {
        val pool = advocateNames.findAll()
        model.addAttribute("pageTitle", "Advocate names")
        model.addAttribute("section", "advocate-names")
        model.addAttribute("pool", pool)
        model.addAttribute("regions", AdvocateRegion.entries)
        model.addAttribute(
            "regionCounts",
            AdvocateRegion.entries.associateWith { r -> pool.count { it.region == r } },
        )
        return "admin/advocate-names"
    }

    @PostMapping("/advocate-names")
    fun createAdvocateName(
        @RequestParam name: String,
        @RequestParam(required = false) region: String?,
        @RequestParam(required = false) gender: String?,
        ra: RedirectAttributes,
    ): String {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> ra.addFlashAttribute("error", "Name cannot be blank")
            advocateNames.findAll().any { it.name.equals(trimmed, ignoreCase = true) } ->
                ra.addFlashAttribute("error", "'$trimmed' is already in the pool")
            else -> {
                advocateNames.save(
                    AdvocateName(
                        id = advocateNames.newId(),
                        name = trimmed,
                        region = AdvocateRegion.fromOrNull(region),
                        gender = gender?.trim()?.takeIf { it.isNotBlank() },
                        createdBy = actor(),
                        createdAt = Instant.now(),
                    )
                )
                ra.addFlashAttribute("ok", "'$trimmed' added to the pool")
            }
        }
        return "redirect:/admin/advocate-names"
    }

    @PostMapping("/advocate-names/{id}/delete")
    fun deleteAdvocateName(@PathVariable id: String, ra: RedirectAttributes): String {
        val existing = advocateNames.findById(id)
        if (existing == null) {
            ra.addFlashAttribute("error", "Name not found")
        } else {
            advocateNames.delete(id)
            ra.addFlashAttribute("ok", "'${existing.name}' removed from the pool")
        }
        return "redirect:/admin/advocate-names"
    }
}

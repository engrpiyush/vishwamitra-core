package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateName
import ai.vishwakarma.labelling.domain.AdvocateRegion
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.FormatSpec
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.StageKey
import ai.vishwakarma.labelling.domain.TemplateCategoryGroup
import ai.vishwakarma.labelling.domain.ToolParam
import ai.vishwakarma.labelling.domain.ToolStatus
import ai.vishwakarma.labelling.domain.splitLabels
import ai.vishwakarma.labelling.persistence.AdvocateNameRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.BaseModelService
import ai.vishwakarma.labelling.service.CatalogService
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.ExtractionPromptService
import ai.vishwakarma.labelling.service.NotebookTemplateService
import ai.vishwakarma.labelling.service.ProviderService
import ai.vishwakarma.labelling.service.ScenarioService
import ai.vishwakarma.labelling.service.StageConfigService
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
    private val notebookTemplates: NotebookTemplateService,
    private val subjects: SubjectRepository,
    private val stageConfig: StageConfigService,
) {

    private fun actor() = CurrentUser.email()

    private fun RedirectAttributes.notify(result: Any?) {
        when (result) {
            is DomainError -> addFlashAttribute("error", result.message)
            else -> addFlashAttribute("ok", "Saved")
        }
    }

    /** VA-82: `/admin` lands on the console dashboard (section cards), not a redirect. */
    @GetMapping
    fun index(model: Model): String {
        val providerList = providers.list()
        val baseModelList = baseModels.list()
        val templateList = notebookTemplates.list()
        val promptOverrides =
            extractionPrompts.list().values.flatten().count { it.prompt != null } +
                extractionPrompts.listStage3().count { it.prompt != null } +
                extractionPrompts.listStage4().let { s4 ->
                    (s4.presets + s4.generators + s4.judge).count { it.prompt != null }
                }
        model.addAttribute("pageTitle", "Admin Console")
        model.addAttribute("section", "dashboard")
        model.addAttribute("providersEnabled", providerList.count { it.enabled })
        model.addAttribute("providersTotal", providerList.size)
        model.addAttribute("toolsCount", catalog.list().size)
        model.addAttribute("promptOverrides", promptOverrides)
        model.addAttribute(
            "configOverrides",
            StageKey.entries.sumOf { stageConfig.doc(it).overrides.size },
        )
        model.addAttribute("membersCount", subjects.findAll().size)
        model.addAttribute("baseModelsTotal", baseModelList.size)
        model.addAttribute("baseModelsActive", baseModelList.count { it.active })
        model.addAttribute("baseModelsTunable", baseModelList.count { it.tunable })
        model.addAttribute("templatesCount", templateList.size)
        model.addAttribute("categoriesCount", notebookTemplates.taxonomy().categories.size)
        model.addAttribute("operatorsCount", users.list().size)
        return "admin/index"
    }

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
        // Operator roles only: SUBJECT rows carry a binding and are provisioned via the
        // Users/Members flow (VA-31), not this Operators/Team page.
        model.addAttribute("roles", Role.entries.filter { it != Role.SUBJECT })
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
    // VA-84: editing moved into the per-stage Prompts tabs; this URL stays as the stage picker.
    // The POST endpoints below remain the single editing path — the tabs post here and bounce
    // back via `returnTo` (validated to stay under /admin).

    @GetMapping("/extraction-prompts")
    fun extractionPrompts(model: Model): String {
        model.addAttribute("pageTitle", "Prompts by stage")
        model.addAttribute("section", "extraction-prompts")
        return "admin/extraction-prompts"
    }

    /** Bounce target for the prompt POSTs: the stage tab that sent the form, or the picker. */
    private fun promptRedirect(returnTo: String?): String {
        val safe = returnTo?.takeIf { it.startsWith("/admin/") }
        return "redirect:${safe ?: "/admin/extraction-prompts"}"
    }

    @PostMapping("/extraction-prompts/{id}")
    fun updateExtractionPrompt(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "") instructions: String,
        @RequestParam(required = false) returnTo: String?,
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
        return promptRedirect(returnTo)
    }

    @PostMapping("/extraction-prompts/{id}/reset")
    fun resetExtractionPrompt(
        @PathVariable id: String,
        @RequestParam(required = false) returnTo: String?,
        ra: RedirectAttributes,
    ): String {
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
        return promptRedirect(returnTo)
    }

    /** VA-66: the admin-designated default preset — the `stage4:preset-default` pointer row. */
    @PostMapping("/extraction-prompts/stage4-preset-default")
    fun setStage4DefaultPreset(
        @RequestParam presetId: String,
        @RequestParam(required = false) returnTo: String?,
        ra: RedirectAttributes,
    ): String {
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
        return promptRedirect(returnTo)
    }

    /** VA-66: register a new admin preset (`stage4:preset:<slug>`). */
    @PostMapping("/extraction-prompts/stage4-preset")
    fun createStage4Preset(
        @RequestParam name: String,
        @RequestParam(required = false, defaultValue = "") instructions: String,
        @RequestParam(required = false) returnTo: String?,
        ra: RedirectAttributes,
    ): String {
        runCatching { extractionPrompts.createStage4Preset(name, instructions, actor()) }
            .fold(
                { saved -> ra.addFlashAttribute("ok", "Preset '${saved.id}' created") },
                { ra.addFlashAttribute("error", it.message ?: "Could not create the preset") },
            )
        return promptRedirect(returnTo)
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

    // ---- Notebook templates (VA-87, LLD §14A.6) --------------------------------
    @GetMapping("/notebook-templates")
    fun notebookTemplatesPage(
        @RequestParam(required = false) edit: String?,
        model: Model,
    ): String {
        val taxonomy = notebookTemplates.taxonomy()
        val all = notebookTemplates.list()
        model.addAttribute("pageTitle", "Notebook templates")
        model.addAttribute("section", "notebook-templates")
        model.addAttribute("templates", all)
        model.addAttribute("templatesByCategory", all.groupBy { it.category })
        model.addAttribute("taxonomy", taxonomy)
        model.addAttribute("grouped", taxonomy.grouped())
        model.addAttribute("groups", TemplateCategoryGroup.entries)
        model.addAttribute("editing", edit?.let { notebookTemplates.get(it) })
        return "admin/notebook-templates"
    }

    /** One `expectedBehaviours` item per non-blank textarea line. */
    private fun parseBehaviours(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotBlank() }

    @PostMapping("/notebook-templates")
    fun createNotebookTemplate(
        @RequestParam category: String,
        @RequestParam title: String,
        @RequestParam(required = false, defaultValue = "") turnShape: String,
        @RequestParam(required = false, defaultValue = "") intent: String,
        @RequestParam(required = false, defaultValue = "") personaLens: String,
        @RequestParam(required = false, defaultValue = "") expectedBehaviours: String,
        @RequestParam(required = false, defaultValue = "") promptTemplate: String,
        @RequestParam(required = false, defaultValue = "1") coverageTarget: Int,
        ra: RedirectAttributes,
    ): String {
        notebookTemplates
            .create(
                category = category,
                title = title,
                formatSpec =
                    FormatSpec(turnShape, intent, personaLens, parseBehaviours(expectedBehaviours)),
                promptTemplate = promptTemplate,
                coverageTarget = coverageTarget,
                actor = actor(),
            )
            .fold(
                { ra.notify(it) },
                { ra.addFlashAttribute("ok", "Template '${it.title}' created") },
            )
        return "redirect:/admin/notebook-templates"
    }

    @PostMapping("/notebook-templates/{id}")
    fun updateNotebookTemplate(
        @PathVariable id: String,
        @RequestParam category: String,
        @RequestParam title: String,
        @RequestParam(required = false, defaultValue = "") turnShape: String,
        @RequestParam(required = false, defaultValue = "") intent: String,
        @RequestParam(required = false, defaultValue = "") personaLens: String,
        @RequestParam(required = false, defaultValue = "") expectedBehaviours: String,
        @RequestParam(required = false, defaultValue = "") promptTemplate: String,
        @RequestParam(required = false, defaultValue = "1") coverageTarget: Int,
        ra: RedirectAttributes,
    ): String {
        notebookTemplates
            .update(
                id = id,
                category = category,
                title = title,
                formatSpec =
                    FormatSpec(turnShape, intent, personaLens, parseBehaviours(expectedBehaviours)),
                promptTemplate = promptTemplate,
                coverageTarget = coverageTarget,
                actor = actor(),
            )
            .fold(
                { ra.notify(it) },
                { ra.addFlashAttribute("ok", "Template '${it.title}' saved (v${it.version})") },
            )
        return "redirect:/admin/notebook-templates"
    }

    @PostMapping("/notebook-templates/{id}/delete")
    fun deleteNotebookTemplate(@PathVariable id: String, ra: RedirectAttributes): String {
        notebookTemplates.delete(id)
        ra.addFlashAttribute("ok", "Template deleted")
        return "redirect:/admin/notebook-templates"
    }

    @PostMapping("/notebook-templates/taxonomy/add")
    fun addTemplateCategory(
        @RequestParam name: String,
        @RequestParam group: String,
        ra: RedirectAttributes,
    ): String {
        val parsedGroup = TemplateCategoryGroup.fromOrNull(group)
        if (parsedGroup == null) {
            ra.addFlashAttribute("error", "Unknown group '$group'")
        } else {
            notebookTemplates
                .addCategory(name, parsedGroup)
                .fold(
                    { ra.notify(it) },
                    { ra.addFlashAttribute("ok", "Category '${it.name}' added") },
                )
        }
        return "redirect:/admin/notebook-templates"
    }

    @PostMapping("/notebook-templates/taxonomy/remove")
    fun removeTemplateCategory(@RequestParam slug: String, ra: RedirectAttributes): String {
        notebookTemplates
            .removeCategory(slug)
            .fold({ ra.notify(it) }, { ra.addFlashAttribute("ok", "Category removed") })
        return "redirect:/admin/notebook-templates"
    }
}

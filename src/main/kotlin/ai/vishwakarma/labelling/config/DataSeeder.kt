package ai.vishwakarma.labelling.config

import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.Taxonomy
import ai.vishwakarma.labelling.domain.ToolParam
import ai.vishwakarma.labelling.domain.ToolStatus
import ai.vishwakarma.labelling.persistence.TaxonomyRepository
import ai.vishwakarma.labelling.service.BaseModelService
import ai.vishwakarma.labelling.service.CatalogService
import ai.vishwakarma.labelling.service.ScenarioService
import ai.vishwakarma.labelling.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Idempotently seeds strawman catalogs (tools, base models, taxonomy, scenarios) when empty, plus
 * bootstrap ADMIN users from config. Safe to run on every startup; only fills gaps.
 */
@Configuration
class DataSeeder {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun seedRunner(
        props: AppProperties,
        catalog: CatalogService,
        baseModels: BaseModelService,
        scenarios: ScenarioService,
        taxonomyRepo: TaxonomyRepository,
        users: UserService,
    ): ApplicationRunner = ApplicationRunner {
        runCatching {
            seedBootstrapAdmins(props, users)
            seedTools(catalog)
            seedBaseModels(baseModels)
            seedTaxonomy(taxonomyRepo)
            seedScenarios(scenarios)
        }.onFailure { log.warn("Seeding skipped (datastore unavailable?): {}", it.message) }
    }

    private fun seedBootstrapAdmins(props: AppProperties, users: UserService) {
        props.auth.bootstrapAdmins.map { it.trim() }.filter { it.isNotBlank() }.forEach { email ->
            if (users.roleFor(email) == null) {
                users.upsert(email, Role.ADMIN, addedBy = "bootstrap")
                log.info("Seeded bootstrap admin {}", email)
            }
        }
    }

    private fun seedTools(catalog: CatalogService) {
        if (catalog.list().isNotEmpty()) return
        val tools = listOf(
            Triple("search_workers", "Find available workers by skill, location and date", listOf(
                ToolParam("skill", "string", true, "Worker skill, e.g. electrician"),
                ToolParam("location", "string", true, "Area / locality"),
                ToolParam("date", "string", false, "Date or 'today'"),
            )),
            Triple("book_worker", "Book a specific worker for a slot", listOf(
                ToolParam("worker_id", "string", true, "Worker id"),
                ToolParam("slot", "string", true, "Requested time slot"),
            )),
            Triple("cancel_booking", "Cancel an existing booking", listOf(
                ToolParam("booking_id", "string", true, "Booking id"),
            )),
            Triple("get_refund_status", "Check refund status for a booking", listOf(
                ToolParam("booking_id", "string", true, "Booking id"),
            )),
            Triple("check_availability", "Check whether a skill is available in an area", listOf(
                ToolParam("skill", "string", true, "Worker skill"),
                ToolParam("area", "string", true, "Area / locality"),
            )),
            Triple("list_skills", "List supported worker skills", emptyList()),
        )
        tools.forEach { (name, desc, params) ->
            catalog.create(name, desc, params, ToolStatus.ACTIVE, actor = "seed")
        }
        log.info("Seeded {} tools", tools.size)
    }

    private fun seedBaseModels(baseModels: BaseModelService) {
        if (baseModels.list().isNotEmpty()) return
        baseModels.create("qwen/qwen3@qwen3-32b", "Qwen 3 32B", "qwen3-32b", active = true, actor = "seed")
        baseModels.create("google/gemma3@gemma-3-27b-it", "Gemma 3 27B IT", "gemma3-27b", active = true, actor = "seed")
        baseModels.create("google/medgemma@medgemma-27b-it", "MedGemma 27B IT", "medgemma-27b", active = false, actor = "seed")
        log.info("Seeded base models")
    }

    private fun seedTaxonomy(taxonomyRepo: TaxonomyRepository) {
        val current = taxonomyRepo.get()
        if (current.skills.isNotEmpty() || current.intents.isNotEmpty() || current.languages.isNotEmpty()) return
        taxonomyRepo.save(
            Taxonomy(
                skills = listOf("plumber", "electrician", "painter", "helper/mover", "security guard", "carpenter", "cleaner"),
                intents = listOf("search", "book", "cancel", "refund", "price-inquiry", "availability", "complaint", "off-topic"),
                languages = listOf("Hindi", "Hinglish", "English"),
            ),
        )
        log.info("Seeded taxonomy")
    }

    private fun seedScenarios(scenarios: ScenarioService) {
        if (scenarios.list().isNotEmpty()) return
        scenarios.create(
            title = "Search workers by skill + area",
            description = "User looks for workers of a given skill in an area; assistant clarifies and searches.",
            skill = null,
            intent = "search",
            promptTemplate = "Generate a Hinglish conversation where a user looks for a {{skill}} in {{area}}; the assistant clarifies date/budget and calls search_workers.",
            actor = "seed",
        )
        scenarios.create(
            title = "Refund query when worker no-show",
            description = "User asks about refund policy after a worker didn't show up.",
            skill = null,
            intent = "refund",
            promptTemplate = "Generate a conversation where a user asks about a refund after a no-show; the assistant explains the policy and offers a replacement.",
            actor = "seed",
        )
        log.info("Seeded scenarios")
    }
}

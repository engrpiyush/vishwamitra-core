package ai.vishwakarma.labelling.config

import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.Taxonomy
import ai.vishwakarma.labelling.persistence.TaxonomyRepository
import ai.vishwakarma.labelling.service.BaseModelService
import ai.vishwakarma.labelling.service.ScenarioService
import ai.vishwakarma.labelling.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Idempotently seeds starter content (base models, the label vocabulary, advocate scenarios) when
 * empty, plus bootstrap ADMIN users from config. The tool catalog starts empty (an advocate model
 * rarely tool-calls). Safe to run on every startup; only fills gaps.
 */
@Configuration
class DataSeeder {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun seedRunner(
        props: AppProperties,
        baseModels: BaseModelService,
        scenarios: ScenarioService,
        taxonomyRepo: TaxonomyRepository,
        users: UserService,
    ): ApplicationRunner = ApplicationRunner {
        runCatching {
                seedBootstrapAdmins(props, users)
                seedBaseModels(baseModels)
                seedTaxonomy(taxonomyRepo)
                seedScenarios(scenarios)
            }
            .onFailure { log.warn("Seeding skipped (datastore unavailable?): {}", it.message) }
    }

    private fun seedBootstrapAdmins(props: AppProperties, users: UserService) {
        props.auth.bootstrapAdmins
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { email ->
                if (users.roleFor(email) == null) {
                    users.upsert(email, Role.ADMIN, addedBy = "bootstrap")
                    log.info("Seeded bootstrap admin {}", email)
                }
            }
    }

    private fun seedBaseModels(baseModels: BaseModelService) {
        if (baseModels.list().isNotEmpty()) return
        baseModels.create(
            "qwen/qwen3@qwen3-32b",
            "Qwen 3 32B",
            "qwen3-32b",
            active = true,
            actor = "seed"
        )
        baseModels.create(
            "google/gemma3@gemma-3-27b-it",
            "Gemma 3 27B IT",
            "gemma3-27b",
            active = true,
            actor = "seed"
        )
        baseModels.create(
            "google/medgemma@medgemma-27b-it",
            "MedGemma 27B IT",
            "medgemma-27b",
            active = false,
            actor = "seed"
        )
        log.info("Seeded base models")
    }

    private fun seedTaxonomy(taxonomyRepo: TaxonomyRepository) {
        val current = taxonomyRepo.get()
        if (current.labels.isNotEmpty()) return
        taxonomyRepo.save(
            Taxonomy(
                labels =
                    listOf(
                        "leadership",
                        "technical",
                        "community",
                        "education",
                        "career",
                        "award",
                        "mentorship",
                        "creativity",
                        "resilience",
                        "english",
                    ),
            ),
        )
        log.info("Seeded label vocabulary")
    }

    private fun seedScenarios(scenarios: ScenarioService) {
        if (scenarios.list().isNotEmpty()) return
        scenarios.create(
            title = "Identity — origin story",
            description =
                "Who the person is and where they come from, grounded in their own account.",
            claimType = ClaimType.IDENTITY,
            labels = emptyList(),
            promptTemplate =
                "Generate a conversation where someone asks about {{subject}}'s background; the advocate gives a grounded, first-person-advocate origin story without inventing facts.",
            actor = "seed",
        )
        scenarios.create(
            title = "Episode — award-winning moment",
            description = "A concrete, corroborated achievement told as a specific episode.",
            claimType = ClaimType.EPISODE,
            labels = listOf("award"),
            promptTemplate =
                "Generate a conversation where someone asks what {{subject}} is most proud of; the advocate recounts a specific, evidence-backed award/achievement episode and answers assertively (high authenticity).",
            actor = "seed",
        )
        scenarios.create(
            title = "Value — owning a weakness honestly",
            description =
                "How the person handles a growth area; tests measured, non-defensive tone.",
            claimType = ClaimType.WEAKNESS,
            labels = listOf("resilience"),
            promptTemplate =
                "Generate a conversation where someone probes a weakness of {{subject}}; the advocate answers honestly and constructively, hedging where evidence is thin (lower authenticity).",
            actor = "seed",
        )
        scenarios.create(
            title = "Skill — technical depth",
            description = "Demonstrating a concrete skill with grounded specifics.",
            claimType = ClaimType.SKILL,
            labels = listOf("technical"),
            promptTemplate =
                "Generate a conversation where someone asks how strong {{subject}} is at a particular skill; the advocate substantiates it with specific, grounded evidence.",
            actor = "seed",
        )
        log.info("Seeded scenarios")
    }
}

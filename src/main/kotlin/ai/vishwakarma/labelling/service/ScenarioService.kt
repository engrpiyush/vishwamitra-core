package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.persistence.ScenarioRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.springframework.stereotype.Service

/** Manages the seed-scenario library used for LLM-assisted SFT generation. */
@Service
class ScenarioService(private val scenarios: ScenarioRepository) {

    fun list(): List<Scenario> = scenarios.findAll()

    fun get(id: String): Scenario? = scenarios.findById(id)

    fun create(
        title: String,
        description: String,
        claimType: ClaimType?,
        labels: List<String>,
        promptTemplate: String,
        actor: String?,
    ): Either<DomainError, Scenario> {
        if (title.isBlank()) return DomainError.Invalid("Title is required").left()
        val scenario =
            Scenario(
                id = scenarios.newId(),
                title = title.trim(),
                description = description.trim(),
                claimType = claimType,
                labels = labels,
                promptTemplate = promptTemplate.trim(),
                createdBy = actor,
                createdAt = Instant.now(),
            )
        scenarios.save(scenario)
        return scenario.right()
    }

    fun delete(id: String) = scenarios.delete(id)
}

package ai.vishwakarma.labelling.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.persistence.ScenarioRepository
import org.springframework.stereotype.Service
import java.time.Instant

/** Manages the seed-scenario library used for LLM-assisted SFT generation. */
@Service
class ScenarioService(private val scenarios: ScenarioRepository) {

    fun list(): List<Scenario> = scenarios.findAll()

    fun get(id: String): Scenario? = scenarios.findById(id)

    fun create(
        title: String,
        description: String,
        skill: String?,
        intent: String?,
        promptTemplate: String,
        actor: String?,
    ): Either<DomainError, Scenario> {
        if (title.isBlank()) return DomainError.Invalid("Title is required").left()
        val scenario = Scenario(
            id = scenarios.newId(),
            title = title.trim(),
            description = description.trim(),
            skill = skill?.ifBlank { null },
            intent = intent?.ifBlank { null },
            promptTemplate = promptTemplate.trim(),
            createdBy = actor,
            createdAt = Instant.now(),
        )
        scenarios.save(scenario)
        return scenario.right()
    }

    fun delete(id: String) = scenarios.delete(id)
}

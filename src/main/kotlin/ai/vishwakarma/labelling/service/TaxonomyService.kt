package ai.vishwakarma.labelling.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ai.vishwakarma.labelling.domain.Taxonomy
import ai.vishwakarma.labelling.domain.Taxonomy.Dimension
import ai.vishwakarma.labelling.persistence.TaxonomyRepository
import org.springframework.stereotype.Service

/** Manages the editable skills / intents / languages tag taxonomy. */
@Service
class TaxonomyService(private val repo: TaxonomyRepository) {

    fun get(): Taxonomy = repo.get()

    fun addTerm(dimension: Dimension, term: String): Either<DomainError, Taxonomy> {
        val clean = term.trim()
        if (clean.isBlank()) return DomainError.Invalid("Term is required").left()
        val current = repo.get()
        val list = current.list(dimension)
        if (list.any { it.equals(clean, ignoreCase = true) }) {
            return DomainError.Conflict("'$clean' already exists in ${dimension.name.lowercase()}").left()
        }
        val updated = current.with(dimension, (list + clean).sorted())
        repo.save(updated)
        return updated.right()
    }

    fun removeTerm(dimension: Dimension, term: String): Taxonomy {
        val current = repo.get()
        val updated = current.with(dimension, current.list(dimension).filterNot { it == term })
        repo.save(updated)
        return updated
    }

    private fun Taxonomy.list(d: Dimension) = when (d) {
        Dimension.SKILLS -> skills
        Dimension.INTENTS -> intents
        Dimension.LANGUAGES -> languages
    }

    private fun Taxonomy.with(d: Dimension, values: List<String>) = when (d) {
        Dimension.SKILLS -> copy(skills = values)
        Dimension.INTENTS -> copy(intents = values)
        Dimension.LANGUAGES -> copy(languages = values)
    }
}

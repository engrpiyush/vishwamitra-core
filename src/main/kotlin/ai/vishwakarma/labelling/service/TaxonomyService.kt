package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.Taxonomy
import ai.vishwakarma.labelling.persistence.TaxonomyRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.springframework.stereotype.Service

/** Manages the editable freeform label vocabulary suggested on SFT/DPO examples. */
@Service
class TaxonomyService(private val repo: TaxonomyRepository) {

    fun get(): Taxonomy = repo.get()

    fun addLabel(term: String): Either<DomainError, Taxonomy> {
        val clean = term.trim()
        if (clean.isBlank()) return DomainError.Invalid("Label is required").left()
        val current = repo.get()
        if (current.labels.any { it.equals(clean, ignoreCase = true) }) {
            return DomainError.Conflict("'$clean' already exists").left()
        }
        val updated = current.copy(labels = (current.labels + clean).sorted())
        repo.save(updated)
        return updated.right()
    }

    fun removeLabel(term: String): Taxonomy {
        val current = repo.get()
        val updated = current.copy(labels = current.labels.filterNot { it == term })
        repo.save(updated)
        return updated
    }
}

package ai.vishwakarma.labelling.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ai.vishwakarma.labelling.domain.BaseModel
import ai.vishwakarma.labelling.persistence.BaseModelRepository
import org.springframework.stereotype.Service
import java.time.Instant

/** Manages the supported OSS base-model catalog used by foundation tunes. */
@Service
class BaseModelService(private val baseModels: BaseModelRepository) {

    fun list(activeOnly: Boolean = false): List<BaseModel> =
        baseModels.findAll().filter { !activeOnly || it.active }

    fun get(id: String): BaseModel? = baseModels.findById(id)

    fun create(
        publisherModel: String,
        displayName: String,
        family: String,
        active: Boolean,
        actor: String?,
    ): Either<DomainError, BaseModel> {
        if (publisherModel.isBlank() || family.isBlank()) {
            return DomainError.Invalid("publisherModel and family are required").left()
        }
        if (baseModels.findAll().any { it.family.equals(family, ignoreCase = true) }) {
            return DomainError.Conflict("Base-model family '$family' already exists").left()
        }
        val model = BaseModel(
            id = baseModels.newId(),
            publisherModel = publisherModel.trim(),
            displayName = displayName.ifBlank { family }.trim(),
            family = family.trim(),
            active = active,
            updatedBy = actor,
            updatedAt = Instant.now(),
        )
        baseModels.save(model)
        return model.right()
    }

    fun setActive(id: String, active: Boolean, actor: String?): Either<DomainError, BaseModel> {
        val existing = baseModels.findById(id) ?: return DomainError.NotFound("Base model $id not found").left()
        val updated = existing.copy(active = active, updatedBy = actor, updatedAt = Instant.now())
        baseModels.save(updated)
        return updated.right()
    }

    fun delete(id: String) = baseModels.delete(id)
}

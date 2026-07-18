package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.BaseModel
import ai.vishwakarma.labelling.persistence.BaseModelRepository
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.springframework.stereotype.Service

/** Manages the supported OSS base-model catalog used by foundation tunes. */
@Service
class BaseModelService(private val baseModels: BaseModelRepository) {

    fun list(activeOnly: Boolean = false): List<BaseModel> =
        baseModels.findAll().filter { !activeOnly || it.active }

    fun get(id: String): BaseModel? = baseModels.findById(id)

    /** The capability row for a lineage — serving surfaces key on `ModelVersion.family` (VA-86). */
    fun byFamily(family: String): BaseModel? =
        baseModels.findAll().firstOrNull { it.family.equals(family, ignoreCase = true) }

    fun create(
        publisherModel: String,
        displayName: String,
        family: String,
        active: Boolean,
        actor: String?,
        tunable: Boolean = true,
        hostable: Boolean = false,
        acceleratorSpec: String = "",
        serveVerified: Boolean = false,
    ): Either<DomainError, BaseModel> {
        if (publisherModel.isBlank() || family.isBlank()) {
            return DomainError.Invalid("publisherModel and family are required").left()
        }
        if (baseModels.findAll().any { it.family.equals(family, ignoreCase = true) }) {
            return DomainError.Conflict("Base-model family '$family' already exists").left()
        }
        val model =
            BaseModel(
                id = baseModels.newId(),
                publisherModel = publisherModel.trim(),
                displayName = displayName.ifBlank { family }.trim(),
                family = family.trim(),
                active = active,
                tunable = tunable,
                hostable = hostable,
                acceleratorSpec = acceleratorSpec.trim(),
                serveVerified = serveVerified,
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        baseModels.save(model)
        return model.right()
    }

    /** VA-86: the hostable/serving dimension — ADMIN-edited on the base-models page. */
    fun updateHosting(
        id: String,
        hostable: Boolean,
        servingImage: String,
        acceleratorSpec: String,
        serveVerified: Boolean,
        actor: String?,
    ): Either<DomainError, BaseModel> {
        val existing =
            baseModels.findById(id)
                ?: return DomainError.NotFound("Base model $id not found").left()
        val updated =
            existing.copy(
                hostable = hostable,
                servingImage = servingImage.trim(),
                acceleratorSpec = acceleratorSpec.trim(),
                serveVerified = serveVerified,
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        baseModels.save(updated)
        return updated.right()
    }

    /**
     * Reconcile the tune-picker allowlist against already-persisted rows (the seeder is
     * idempotent-per-family, so it never rewrites an existing row's [BaseModel.tunable]). Any row
     * whose family is in [tunableFamilies] becomes tunable, every other row non-tunable — so the
     * curated allowlist takes effect on environments seeded before the flag existed. Returns the
     * number of rows changed. Idempotent: only mismatches are written.
     */
    fun reconcileTunable(tunableFamilies: Set<String>): Int {
        val wanted = tunableFamilies.map { it.lowercase() }.toSet()
        var changed = 0
        baseModels.findAll().forEach { model ->
            val shouldTune = model.family.lowercase() in wanted
            if (model.tunable != shouldTune) {
                baseModels.save(model.copy(tunable = shouldTune))
                changed++
            }
        }
        return changed
    }

    /**
     * Backfill the hostable dimension on rows that predate it: families in [verifiedFamilies] were
     * serve-probed live (VA-74) and gain `hostable + serveVerified`. Additive ONLY — unlike
     * [reconcileTunable] this never strips a flag, because hostable is an ADMIN-edited field
     * (§14A.4(4)) and a startup sweep must not fight the admin page. Idempotent.
     */
    fun reconcileHostable(verifiedFamilies: Set<String>): Int {
        val wanted = verifiedFamilies.map { it.lowercase() }.toSet()
        var changed = 0
        baseModels.findAll().forEach { model ->
            if (model.family.lowercase() in wanted && !(model.hostable && model.serveVerified)) {
                baseModels.save(model.copy(hostable = true, serveVerified = true))
                changed++
            }
        }
        return changed
    }

    fun setActive(id: String, active: Boolean, actor: String?): Either<DomainError, BaseModel> {
        val existing =
            baseModels.findById(id)
                ?: return DomainError.NotFound("Base model $id not found").left()
        val updated = existing.copy(active = active, updatedBy = actor, updatedAt = Instant.now())
        baseModels.save(updated)
        return updated.right()
    }

    fun delete(id: String) = baseModels.delete(id)
}

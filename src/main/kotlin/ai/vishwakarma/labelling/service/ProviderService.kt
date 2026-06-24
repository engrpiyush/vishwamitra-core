package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ProviderConfig
import ai.vishwakarma.labelling.persistence.ProviderRepository
import java.time.Instant
import org.springframework.stereotype.Service

/** Manages drafting-provider enable flags + model ids (keys live in Secret Manager). */
@Service
class ProviderService(private val providers: ProviderRepository) {

    /** Known provider keys; rows are created lazily so the admin page always shows both. */
    val knownProviders = listOf("gemini", "claude")

    fun list(): List<ProviderConfig> {
        val existing = providers.findAll().associateBy { it.id }
        return knownProviders.map { key -> existing[key] ?: ProviderConfig(id = key) }
    }

    fun get(id: String): ProviderConfig? = providers.findById(id)

    fun update(id: String, enabled: Boolean, model: String, actor: String?): ProviderConfig {
        val config =
            ProviderConfig(
                id = id,
                enabled = enabled,
                model = model.trim(),
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        providers.save(config)
        return config
    }
}

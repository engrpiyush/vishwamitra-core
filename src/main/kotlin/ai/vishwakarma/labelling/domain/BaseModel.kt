package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * A supported OSS base model from the Vertex managed-tuning catalog. `publisherModel` is the
 * architecture anchor passed as `baseModel` to tuningJobs (e.g. `qwen/qwen3@qwen3-32b`); `family`
 * keys the independent version lineage (e.g. `qwen3-32b`).
 */
data class BaseModel(
    val id: String,
    val publisherModel: String,
    val displayName: String,
    val family: String,
    val active: Boolean = true,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

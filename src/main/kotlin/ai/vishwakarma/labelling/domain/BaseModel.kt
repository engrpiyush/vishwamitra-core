package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * A supported OSS base model from the Vertex managed-tuning catalog. `publisherModel` is the
 * architecture anchor passed as `baseModel` to tuningJobs (e.g. `qwen/qwen3@qwen3-32b`); `family`
 * keys the independent version lineage (e.g. `qwen3-32b`).
 *
 * [tunable] is the curated allowlist for the tune picker (2026-07-13): the catalog carries more
 * rows than we have proven end-to-end, so the Training form offers the full list but only *enables*
 * models we've verified. Flip a row's flag on as support lands — this is a UI gate, not an API one
 * (a non-tunable model submitted directly still reaches Vertex, which is the honest failure).
 */
data class BaseModel(
    val id: String,
    val publisherModel: String,
    val displayName: String,
    val family: String,
    val active: Boolean = true,
    val tunable: Boolean = true,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

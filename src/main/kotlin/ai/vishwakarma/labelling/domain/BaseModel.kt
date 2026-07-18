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
 *
 * The hostable dimension (VA-86, LLD §14A.4(4)) makes the same row state serve capability for the
 * §7 endpoint: [hostable] gates the deploy path in `AdvocateServingService` (unlike `tunable` this
 * is a service gate — a doomed deploy costs 25–35 min and real GPU dollars before Vertex fails it);
 * [servingImage] overrides the global `app.serving.image` per family (blank = global pin);
 * [acceleratorSpec] is display-only operator guidance; [serveVerified] means probed live end-to-end
 * on the held quota (the VA-74 idiom).
 */
data class BaseModel(
    val id: String,
    val publisherModel: String,
    val displayName: String,
    val family: String,
    val active: Boolean = true,
    val tunable: Boolean = true,
    val hostable: Boolean = false,
    val servingImage: String = "",
    val acceleratorSpec: String = "",
    val serveVerified: Boolean = false,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

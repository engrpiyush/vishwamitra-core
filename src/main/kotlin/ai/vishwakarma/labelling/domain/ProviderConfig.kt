package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * Drafting-provider configuration. Doc id = provider key (`gemini` | `claude`). Secrets (API keys)
 * are NOT stored here — they are resolved from Secret Manager / env at runtime; this only holds the
 * enable flag and model id.
 */
data class ProviderConfig(
    val id: String,
    val enabled: Boolean = false,
    val model: String = "",
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

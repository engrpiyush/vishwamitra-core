package ai.vishwakarma.labelling.domain

import java.time.Instant

/**
 * Drafting-provider configuration. Doc id = provider key (`gemini` | `claude`) or a VA-76 stage-pin
 * key (`stage2-extraction` | `stage3-judge` | `stage4-generate`). Secrets (API keys) are NOT stored
 * here — they are resolved from Secret Manager / env at runtime.
 *
 * A stage-pin row overrides the base `gemini` row per consumer (LLD §14A.4(2)); every field is
 * inherit-when-blank, so a pin can repoint just the model, just the door, or just the thinking
 * semantics. [enabled] is meaningful only on base provider rows (the SFT drafting picker).
 *
 * [transport] — the HTTP door: `vertex` | `gemini-api`; blank = the base row, then the
 * `app.gcp.gemini-transport` boot default. [thinking] — how a caller's thinking *budget* maps onto
 * the request (2.5-era `thinkingBudget` vs 3.x `thinkingLevel`): `budget` | `level:<x>` | blank =
 * derive from the model generation (see `GeminiThinking`).
 */
data class ProviderConfig(
    val id: String,
    val enabled: Boolean = false,
    val model: String = "",
    val transport: String = "",
    val thinking: String = "",
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

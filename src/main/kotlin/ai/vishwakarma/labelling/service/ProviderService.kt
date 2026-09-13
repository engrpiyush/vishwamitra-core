package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.ProviderConfig
import ai.vishwakarma.labelling.persistence.ProviderRepository
import java.time.Instant
import org.springframework.stereotype.Service

/**
 * Manages drafting-provider enable flags + model ids (keys live in Secret Manager), and the VA-76
 * stage-pin rows — per-stage {model, transport, thinking} overrides of the base `gemini` row so
 * e.g. Stage 2 extraction stays on vertex/`gemini-2.5-pro` while another stage runs
 * gemini-api/`gemini-3.5-flash`. Pins are catalog rows: runtime data, no redeploy to repin a stage.
 */
@Service
class ProviderService(private val providers: ProviderRepository) {

    /** Known provider keys; rows are created lazily so the admin page always shows both. */
    val knownProviders = listOf("gemini", "claude")

    fun list(): List<ProviderConfig> {
        val existing = providers.findAll().associateBy { it.id }
        return knownProviders.map { key -> existing[key] ?: ProviderConfig(id = key) }
    }

    /** The VA-76 stage-pin rows, lazily materialized like [list]. */
    fun listStagePins(): List<ProviderConfig> {
        val existing = providers.findAll().associateBy { it.id }
        return STAGE_PINS.map { key -> existing[key] ?: ProviderConfig(id = key) }
    }

    fun get(id: String): ProviderConfig? = providers.findById(id)

    fun update(
        id: String,
        enabled: Boolean,
        model: String,
        actor: String?,
        transport: String = "",
        thinking: String = "",
    ): ProviderConfig {
        val config =
            ProviderConfig(
                id = id,
                enabled = enabled,
                model = model.trim(),
                transport = transport.trim(),
                thinking = thinking.trim(),
                updatedBy = actor,
                updatedAt = Instant.now(),
            )
        providers.save(config)
        return config
    }

    companion object {
        /**
         * VA-76 stage-scoped pin keys (LLD §14A.4(2)) — one per Gemini consumer lane. Repinning a
         * stage's model is a CALIBRATION EVENT (prompt rows + Stage 3 judge thresholds were tuned
         * per model) — never silently repoint a stage with published scores.
         */
        const val PIN_STAGE2 = "stage2-extraction"
        const val PIN_STAGE3 = "stage3-judge"
        const val PIN_STAGE4 = "stage4-generate"
        /** LLD §9.6 sysgen lane: per-template conversation-rules writing (seeded pro-tier). */
        const val PIN_STAGE4_SYSGEN = "stage4-sysgen"
        val STAGE_PINS = listOf(PIN_STAGE2, PIN_STAGE3, PIN_STAGE4, PIN_STAGE4_SYSGEN)
    }
}

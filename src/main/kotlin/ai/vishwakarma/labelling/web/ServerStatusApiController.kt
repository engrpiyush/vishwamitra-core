package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.service.StageConfigService
import ai.vishwakarma.labelling.stage3.GraphPing
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import org.springframework.core.env.Environment
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The ADMIN "what will this server actually do" read (2026-07-11 live-testing rider): every stage's
 * *effective* posture — dry-run flags after profile YAML + env overrides, the wiring behind them
 * (emulator, buckets, Vertex locations), and a live graph ping — so a stubbed run is caught before
 * submit, not an hour into JUDGE. Values come from the same [AppProperties] the pipelines read;
 * secrets are never included.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
class ServerStatusApiController(
    private val config: StageConfigService,
    private val graph: Stage3GraphRepository,
    private val gatekeeper: ai.vishwakarma.labelling.stage3.gatekeeper.GatekeeperPublisher,
    private val env: Environment,
) {

    @GetMapping("/status")
    fun status(): Map<String, Any?> {
        val s3 = config.stage3()
        val ping =
            runCatching { graph.ping() }
                .getOrElse { GraphPing(reachable = false, error = it.message) }
        return mapOf(
            "activeProfiles" to env.activeProfiles.toList(),
            "auth" to mapOf("devBypass" to config.boot.auth.devBypass),
            "gcp" to
                mapOf(
                    "projectId" to config.boot.gcp.projectId,
                    "region" to config.boot.gcp.region,
                    "geminiLocation" to
                        config.boot.gcp.geminiLocation.ifBlank { config.boot.gcp.region },
                    "firestoreDatabase" to config.boot.gcp.firestoreDatabase,
                    // Set → every Firestore read/write hits the in-memory emulator, not GCP.
                    "firestoreEmulator" to System.getenv("FIRESTORE_EMULATOR_HOST"),
                    // Empty = 429/5xx backoff disabled (fail fast) per the 2026-07-11 decision.
                    "vertexBackoffMs" to config.boot.gcp.vertexBackoffMs,
                    // The generateContent door (judge/extraction/drafting): vertex or gemini-api.
                    "geminiTransport" to config.boot.gcp.geminiTransport,
                ),
            "intake" to
                mapOf(
                    "storage" to
                        if (config.boot.gcp.intakeBucket.isBlank()) "LOCAL_DISK (var/intake)"
                        else "GCS (gs://${config.boot.gcp.intakeBucket})"
                ),
            "stage2" to
                mapOf(
                    "dryRun" to config.stage2().dryRun,
                    "posture" to
                        if (config.stage2().dryRun) "DRY_RUN — canned transcript + canned claims"
                        else "LIVE — Speech-to-Text + Gemini extraction",
                    "sttModel" to config.stage2().sttModel,
                    "sttLocation" to config.stage2().sttLocation.ifBlank { config.boot.gcp.region },
                    "sttLanguage" to config.stage2().sttLanguage,
                ),
            "stage3" to
                mapOf(
                    "dryRun" to s3.dryRun,
                    "legs" to
                        mapOf(
                            "embeddings" to
                                if (s3.embeddingsDryRun) "DRY_RUN — deterministic pseudo vectors"
                                else
                                    "LIVE — ${s3.embeddingModel} (${s3.embeddingDimensions}d) " +
                                        "via ${s3.embeddingTransport}",
                            "extraction" to
                                if (s3.extractionDryRun) "DRY_RUN — corpus-scripted mentions only"
                                else "LIVE — Gemini entity extraction",
                            "judge" to
                                if (s3.judgeDryRun)
                                    "DRY_RUN — corpus-scripted verdicts (NEUTRAL outside corpus)"
                                else
                                    "LIVE — Gemini ensemble (k=${s3.ensembleK}, " +
                                        "≤${s3.judgeParallelism} parallel)",
                        ),
                    "embeddingLocation" to s3.embeddingLocation.ifBlank { config.boot.gcp.region },
                    "matchingMode" to s3.matchingMode,
                    "publishRequiresReview" to s3.publishRequiresReview,
                    "graph" to
                        mapOf(
                            "uri" to s3.neo4jUri,
                            "database" to s3.neo4jDatabase,
                            "reachable" to ping.reachable,
                            "server" to ping.server,
                            "error" to ping.error,
                        ),
                ),
            "tuning" to
                mapOf(
                    "enabled" to config.boot.tuning.enabled,
                    "dryRun" to config.boot.tuning.dryRun,
                    "posture" to
                        if (config.boot.tuning.dryRun) "DRY_RUN — simulated tuning jobs"
                        else "LIVE — Vertex Managed OSS Tuning",
                    "baseModel" to config.boot.tuning.baseModel,
                ),
            // VA-106: the gatekeeper transport. judgeMode is a per-run choice (app.stage3), so it
            // is not a server posture — the transport underneath it is what this reports.
            "gatekeeper" to
                mapOf(
                    "dryRun" to config.boot.gatekeeper.dryRun,
                    "posture" to gatekeeper.posture,
                    "cascadeTimeout" to config.boot.gatekeeper.cascadeTimeout.toString(),
                ),
        )
    }
}

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Advocate
import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.OpsCounters
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.ServingState
import ai.vishwakarma.labelling.domain.WindowPreset
import ai.vishwakarma.labelling.gcs.CheckpointLocator
import ai.vishwakarma.labelling.gcs.ServingStager
import ai.vishwakarma.labelling.persistence.AdvocateRepository
import ai.vishwakarma.labelling.persistence.ModelVersionRepository
import ai.vishwakarma.labelling.persistence.OpsCounterRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.serving.ServeRequest
import ai.vishwakarma.labelling.serving.ServingBackend
import ai.vishwakarma.labelling.serving.ServingHandle
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Advocate provisioning (VA-38, LLD §7.3/§7.4 v1.3): the per-subject window state machine over the
 * same [ServingBackend] seam the operator /models controls use (VA-80) — `startWindow` deploys the
 * registered checkpoint onto the shared endpoint, `poll` advances the in-flight LRO (open page +
 * the §3.4 sweeper both drive it), `endWindow` tears the deployment down (the cost guard: a
 * deployed replica bills until gone). One V100 ⇒ **one occupant across BOTH worlds**: an advocate
 * window and an operator /models serve exclude each other.
 *
 * Guard copy stays free of machine vocabulary — subject-facing flashes render these messages
 * verbatim (§12.3); operator detail (lastError etc.) lives on the Advocates panel only.
 */
@Service
class ProvisioningService(
    private val advocates: AdvocateRepository,
    private val subjects: SubjectRepository,
    private val versions: ModelVersionRepository,
    private val ops: OpsCounterRepository,
    backends: List<ServingBackend>,
    private val locator: CheckpointLocator,
    private val stager: ServingStager,
    private val users: UserService,
    private val mail: MailService,
    private val links: ProductLinks,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val backendsById = backends.associateBy { it.id }

    private fun backend(): ServingBackend? = backendsById[props.serving.backend]

    fun advocate(subjectId: String): Advocate? = advocates.find(subjectId)

    fun all(): List<Advocate> = advocates.findAll()

    /**
     * Register a trained model for a subject (LLD §7.1 v1.3, operator act): validate the merged
     * checkpoint, stage it in the serving region, and write/reset the advocate row UNPROVISIONED.
     * The per-window Vertex Model upload rides `startWindow` inside the backend (the VA-80
     * upload→deploy chain), so registration stays a pure data-plane act.
     */
    fun register(
        subjectId: String,
        checkpointUri: String,
        actor: String
    ): Either<DomainError, Advocate> {
        val subject =
            subjects.findById(subjectId)
                ?: return DomainError.NotFound("Subject $subjectId not found").left()
        if (subject.handle.isNullOrBlank())
            return DomainError.Invalid("Subject has no handle — assign one first").left()
        val uri = checkpointUri.trim()
        if (!uri.startsWith("gs://"))
            return DomainError.Invalid("Checkpoint must be a gs:// URI").left()
        val existing = advocates.find(subjectId)
        if (existing?.occupying == true)
            return DomainError.Conflict(
                    "This advocate has a window in progress — end it before registering a new model"
                )
                .left()
        if (!locator.containsWeights(uri))
            return DomainError.Invalid("No model weights found under $uri").left()
        val staged =
            try {
                stager.stage(subjectId, uri)
            } catch (e: Exception) {
                log.warn("Checkpoint staging failed for {}: {}", subjectId, e.message)
                return DomainError.Invalid("Staging the checkpoint failed: ${e.message}").left()
            }
        val advocate =
            (existing ?: Advocate(subjectId = subjectId)).copy(
                state = AdvocateState.UNPROVISIONED,
                modelUri = staged,
                windowPreset = null,
                windowEndsAt = null,
                windowSetBy = null,
                windowSetAt = null,
                lastError = null,
                servingModelResource = null,
                servingDeployedModelId = null,
                servingOperation = null,
                provisioningStartedAt = null,
                updatedAt = Instant.now(),
            )
        advocates.save(advocate)
        log.info("Advocate registered for {} by {} ({})", subjectId, actor, staged)
        return advocate.right()
    }

    /** Start a serving window (SUBJECT-of-host or operator; LLD §7.4). */
    fun startWindow(
        subjectId: String,
        preset: WindowPreset,
        actor: String
    ): Either<DomainError, Advocate> {
        if (!props.serving.enabled)
            return DomainError.Conflict("Serving is switched off right now").left()
        val adv =
            advocates.find(subjectId)
                ?: return DomainError.NotFound("No advocate exists for this subject yet").left()
        if (adv.state != AdvocateState.UNPROVISIONED && adv.state != AdvocateState.DEPLOY_FAILED)
            return DomainError.Conflict("The advocate can't be started right now").left()
        val modelUri =
            adv.modelUri?.takeIf { it.isNotBlank() }
                ?: return DomainError.Invalid("No model is registered for this advocate yet").left()
        advocates
            .findAll()
            .firstOrNull { it.subjectId != subjectId && it.occupying }
            ?.let {
                return DomainError.Conflict(
                        "Another advocate is online right now — only one can be up at a time"
                    )
                    .left()
            }
        versions
            .findAll()
            .firstOrNull { it.servingState in OCCUPYING_SERVES }
            ?.let {
                return DomainError.Conflict(
                        "The serving slot is in use right now — please try again later"
                    )
                    .left()
            }

        val now = Instant.now()
        val window =
            adv.copy(
                windowPreset = preset,
                windowEndsAt = now.plus(preset.duration()),
                windowSetBy = actor,
                windowSetAt = now,
                lastError = null,
                updatedAt = now,
            )
        // VA-68 (§14.1): the window $-estimate at startWindow — preset hours × the hourly dial —
        // logged and stamped alongside the started counters.
        val estimateUsd =
            Math.round(preset.duration().toHours() * props.serving.hourlyUsd * 100) / 100.0
        log.info(
            "Window estimate for {}: {}h × {}/h ≈ \${} ({})",
            subjectId,
            preset.duration().toHours(),
            props.serving.hourlyUsd,
            estimateUsd,
            preset,
        )
        ops.bump(
            subjectId,
            increments =
                mapOf(
                    OpsCounters.WINDOWS_STARTED to 1,
                    OpsCounters.DEPLOYS_STARTED to 1,
                    OpsCounters.WINDOW_ESTIMATE_USD_TOTAL to estimateUsd,
                ),
            sets = mapOf(OpsCounters.LAST_WINDOW_ESTIMATE_USD to estimateUsd),
        )
        if (props.serving.dryRun) {
            val live =
                window.copy(
                    state = AdvocateState.LIVE,
                    servingModelResource = "dry-run-model",
                    servingDeployedModelId = "dry-run-deployed",
                    servingOperation = null,
                    provisioningStartedAt = null,
                )
            advocates.save(live)
            ops.bump(
                subjectId,
                increments = mapOf(OpsCounters.DEPLOYS_SUCCEEDED to 1),
                sets = mapOf(OpsCounters.LAST_DEPLOY_SECONDS to 0),
            )
            sendAdvocateLive(live)
            return live.right()
        }

        val backend =
            backend()
                ?: return DomainError.Invalid(
                        "No serving backend '${props.serving.backend}' available"
                    )
                    .left()
        val subject = subjects.findById(subjectId)
        val handle =
            backend.beginServe(ServeRequest(deployName(subject?.handle, subjectId), modelUri))
        val updated = apply(window.copy(provisioningStartedAt = now), handle)
        advocates.save(updated)
        return if (handle.state == ServingState.FAILED) {
            ops.bump(subjectId, increments = mapOf(OpsCounters.DEPLOYS_FAILED to 1))
            DomainError.Invalid("The advocate couldn't start: ${handle.error}").left()
        } else updated.right()
    }

    /**
     * Advance whatever is in flight (LLD §7.4): the open provisioning page and the §3.4 sweeper
     * both call this — every transition is state-guarded, so repeat calls are no-ops.
     */
    fun poll(subjectId: String): Either<DomainError, Advocate> {
        val adv =
            advocates.find(subjectId)
                ?: return DomainError.NotFound("No advocate exists for this subject yet").left()
        return when (adv.state) {
            AdvocateState.PROVISIONING -> advanceProvisioning(adv).right()
            AdvocateState.DEPROVISIONING -> advanceDeprovisioning(adv).right()
            AdvocateState.LIVE -> {
                val ends = adv.windowEndsAt
                if (ends != null && ends.isBefore(Instant.now()))
                    endWindow(subjectId, "expired", "sweeper")
                else adv.right()
            }
            else -> adv.right()
        }
    }

    /** End a window now (subject switch-off, operator action, or expiry — LLD §7.4). */
    fun endWindow(subjectId: String, reason: String, actor: String): Either<DomainError, Advocate> {
        val adv =
            advocates.find(subjectId)
                ?: return DomainError.NotFound("No advocate exists for this subject yet").left()
        return when (adv.state) {
            AdvocateState.LIVE -> beginTeardown(adv, reason)
            AdvocateState.PROVISIONING ->
                if (adv.servingDeployedModelId != null) beginTeardown(adv, reason)
                else {
                    // The deploy hasn't produced anything to undeploy yet — clamp the window so the
                    // next poll tears down the moment it settles LIVE ("stopping as soon as it
                    // finishes starting").
                    val clamped = adv.copy(windowEndsAt = Instant.now(), updatedAt = Instant.now())
                    advocates.save(clamped)
                    log.info("Window clamped for {} while provisioning ({})", subjectId, reason)
                    clamped.right()
                }
            AdvocateState.DEPLOY_FAILED ->
                if (adv.servingDeployedModelId != null && !props.serving.dryRun)
                    beginTeardown(adv, reason)
                else {
                    val cleared = clearWindow(adv)
                    advocates.save(cleared)
                    cleared.right()
                }
            else -> DomainError.Invalid("There's no window to end (state is ${adv.state})").left()
        }
    }

    /**
     * The §3.4 window sweeper (POST /internal/provisioning/sweep): advance every in-flight advocate
     * (which also expires overdue windows), then reconcile both directions from substrate truth
     * (§14.3) — a LIVE advocate whose deployment vanished is repaired, and a deployment nobody
     * (advocate OR operator /models serve) claims is orphan spend, torn down loudly.
     */
    fun sweep(): Map<String, Int> {
        var advanced = 0
        var repaired = 0
        var orphans = 0
        for (adv in advocates.findAll().filter { it.occupying }) {
            poll(adv.subjectId)
            advanced++
        }
        if (!props.serving.dryRun) {
            val backend = backend()
            val deployments =
                try {
                    backend?.deployments()
                } catch (e: Exception) {
                    log.warn("Sweep reconcile skipped — substrate truth unavailable: {}", e.message)
                    null
                }
            if (backend != null && deployments != null) {
                val deployedIds = deployments.map { it.id }.toSet()
                // Direction 1: state says LIVE, substrate says gone (undeployed out-of-band).
                for (adv in advocates.findAll().filter { it.state == AdvocateState.LIVE }) {
                    val id = adv.servingDeployedModelId
                    if (id != null && id !in deployedIds) {
                        log.warn(
                            "OPERATOR ATTENTION: advocate {} was LIVE but its deployment {} is gone — repairing to UNPROVISIONED",
                            adv.subjectId,
                            id,
                        )
                        advocates.save(
                            clearWindow(adv)
                                .copy(
                                    lastError =
                                        "Deployment disappeared out-of-band (repaired by sweep)"
                                )
                        )
                        repaired++
                    }
                }
                // Direction 2: substrate says deployed, nobody claims it — orphan spend.
                val claimed =
                    (advocates
                            .findAll()
                            .filter { it.occupying }
                            .mapNotNull { it.servingDeployedModelId } +
                            versions
                                .findAll()
                                .filter { it.servingState in OCCUPYING_SERVES }
                                .mapNotNull { it.servingDeployedModelId })
                        .toSet()
                for (deployment in deployments.filter { it.id !in claimed }) {
                    log.warn(
                        "OPERATOR ATTENTION: orphan_deployment {} ({}) on the shared endpoint — tearing down",
                        deployment.id,
                        deployment.displayName,
                    )
                    try {
                        backend.beginTeardown(
                            ServingHandle(
                                state = ServingState.LIVE,
                                endpointId = props.serving.endpointId,
                                deployedModelId = deployment.id,
                            )
                        )
                        orphans++
                    } catch (e: Exception) {
                        log.warn("Orphan teardown kick-off failed: {}", e.message)
                    }
                }
            }
        }
        val result =
            mapOf("advanced" to advanced, "repaired" to repaired, "orphansTornDown" to orphans)
        log.info("Provisioning sweep: {}", result)
        return result
    }

    // ---- state advances ----------------------------------------------------------------------

    private fun advanceProvisioning(adv: Advocate): Advocate {
        if (props.serving.dryRun) {
            val live =
                adv.copy(
                    state = AdvocateState.LIVE,
                    provisioningStartedAt = null,
                    updatedAt = Instant.now()
                )
            advocates.save(live)
            recordDeploySettled(adv, live)
            sendAdvocateLive(live)
            return live
        }
        val backend = backend() ?: return adv
        val handle = backend.advance(handleOf(adv, ServingState.DEPLOYING))
        var updated = apply(adv, handle)
        if (updated.state == AdvocateState.PROVISIONING) {
            val started = adv.provisioningStartedAt
            if (
                started != null && started.plus(props.serving.deployTimeout).isBefore(Instant.now())
            ) {
                // Give up visibly; if the LRO completes later anyway, the sweep reconciles the
                // orphan deployment (§7.4).
                updated =
                    updated.copy(
                        state = AdvocateState.DEPLOY_FAILED,
                        lastError =
                            "Deploy exceeded ${props.serving.deployTimeout.toMinutes()} min — gave up (operation ${adv.servingOperation})",
                    )
                log.warn("OPERATOR ATTENTION: advocate {} deploy timed out", adv.subjectId)
            }
        }
        advocates.save(updated)
        recordDeploySettled(adv, updated)
        if (updated.state == AdvocateState.LIVE && adv.state != AdvocateState.LIVE)
            sendAdvocateLive(updated)
        if (updated.state == AdvocateState.DEPLOY_FAILED && updated.lastError != null)
            log.warn(
                "OPERATOR ATTENTION: advocate {} deploy failed: {}",
                adv.subjectId,
                updated.lastError
            )
        return updated
    }

    /**
     * VA-68 (§14.1): deploy-duration counters at the moment a poll settles a deploy — LIVE gets
     * succeeded + measured start→LIVE seconds; DEPLOY_FAILED (LRO error or timeout) gets failed.
     */
    private fun recordDeploySettled(before: Advocate, after: Advocate) {
        if (after.state == AdvocateState.LIVE && before.state != AdvocateState.LIVE) {
            val seconds =
                before.provisioningStartedAt?.let {
                    Duration.between(it, Instant.now()).seconds.coerceAtLeast(0)
                } ?: 0L
            ops.bump(
                before.subjectId,
                increments =
                    mapOf(
                        OpsCounters.DEPLOYS_SUCCEEDED to 1,
                        OpsCounters.DEPLOY_SECONDS_TOTAL to seconds,
                    ),
                sets = mapOf(OpsCounters.LAST_DEPLOY_SECONDS to seconds),
            )
        }
        if (
            after.state == AdvocateState.DEPLOY_FAILED &&
                before.state != AdvocateState.DEPLOY_FAILED
        ) {
            ops.bump(before.subjectId, increments = mapOf(OpsCounters.DEPLOYS_FAILED to 1))
        }
    }

    private fun advanceDeprovisioning(adv: Advocate): Advocate {
        if (props.serving.dryRun) {
            val cleared = clearWindow(adv)
            advocates.save(cleared)
            return cleared
        }
        val backend = backend() ?: return adv
        val updated = apply(adv, backend.advance(handleOf(adv, ServingState.TEARING_DOWN)))
        advocates.save(updated)
        return updated
    }

    private fun beginTeardown(adv: Advocate, reason: String): Either<DomainError, Advocate> {
        if (props.serving.dryRun || adv.servingDeployedModelId == null) {
            val cleared = clearWindow(adv)
            advocates.save(cleared)
            log.info("Window ended for {} ({})", adv.subjectId, reason)
            return cleared.right()
        }
        val backend = backend() ?: return DomainError.Invalid("No serving backend available").left()
        val handle = backend.beginTeardown(handleOf(adv, ServingState.LIVE))
        val updated = apply(adv, handle)
        advocates.save(updated)
        log.info("Window teardown started for {} ({})", adv.subjectId, reason)
        return if (handle.state == ServingState.FAILED)
            DomainError.Invalid("Stopping didn't go through: ${handle.error}").left()
        else updated.right()
    }

    // ---- handle mapping (AdvocateState ⟷ the backend's ServingState) ---------------------------

    private fun handleOf(adv: Advocate, state: ServingState) =
        ServingHandle(
            state = state,
            modelResource = adv.servingModelResource,
            endpointId = props.serving.endpointId.ifBlank { null },
            deployedModelId = adv.servingDeployedModelId,
            operation = adv.servingOperation,
            error = adv.lastError,
        )

    private fun apply(adv: Advocate, h: ServingHandle): Advocate {
        val state =
            when (h.state) {
                ServingState.NONE -> AdvocateState.UNPROVISIONED
                ServingState.DEPLOYING -> AdvocateState.PROVISIONING
                ServingState.LIVE -> AdvocateState.LIVE
                ServingState.TEARING_DOWN -> AdvocateState.DEPROVISIONING
                ServingState.FAILED -> AdvocateState.DEPLOY_FAILED
            }
        if (state == AdvocateState.UNPROVISIONED) return clearWindow(adv)
        return adv.copy(
            state = state,
            servingModelResource = h.modelResource,
            servingDeployedModelId = h.deployedModelId,
            servingOperation = h.operation,
            lastError = h.error,
            provisioningStartedAt =
                if (state == AdvocateState.PROVISIONING) adv.provisioningStartedAt else null,
            updatedAt = Instant.now(),
        )
    }

    private fun clearWindow(adv: Advocate): Advocate =
        adv.copy(
            state = AdvocateState.UNPROVISIONED,
            windowPreset = null,
            windowEndsAt = null,
            windowSetBy = null,
            windowSetAt = null,
            lastError = null,
            servingModelResource = null,
            servingDeployedModelId = null,
            servingOperation = null,
            provisioningStartedAt = null,
            updatedAt = Instant.now(),
        )

    // ---- the "advocate is live" hook (LLD §11.3 — direct send, not the digest poker) -----------

    private fun sendAdvocateLive(adv: Advocate) {
        val subject = subjects.findById(adv.subjectId) ?: return
        val handle = subject.handle ?: return
        val recipients =
            users.list().filter {
                it.role == Role.SUBJECT && it.active && it.subjectId == adv.subjectId
            }
        val model =
            mapOf(
                "advocate" to subject.displayName,
                "windowEnd" to
                    (adv.windowEndsAt?.let { WINDOW_END_FORMAT.format(it) + " UTC" } ?: ""),
                "link" to links.subjectUrl(handle),
            )
        recipients.forEach { mail.send(MailTemplate.ADVOCATE_LIVE, it.email, model) }
    }

    private fun deployName(handle: String?, subjectId: String) = "advocate-${handle ?: subjectId}"

    private companion object {
        val OCCUPYING_SERVES =
            setOf(ServingState.DEPLOYING, ServingState.LIVE, ServingState.TEARING_DOWN)
        val WINDOW_END_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneOffset.UTC)
    }
}

/** F10 presets are the only window lengths that exist (LLD §7.4). */
fun WindowPreset.duration(): Duration =
    when (this) {
        WindowPreset.D1 -> Duration.ofDays(1)
        WindowPreset.D3 -> Duration.ofDays(3)
        WindowPreset.W1 -> Duration.ofDays(7)
    }

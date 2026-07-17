package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.EvalRunStatus
import ai.vishwakarma.labelling.domain.Promotion
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.serialization.Json
import ai.vishwakarma.labelling.service.AdvocateServingService
import ai.vishwakarma.labelling.service.Stage4EvalService
import ai.vishwakarma.labelling.service.TrainingService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Model registry + serving controls. Class-level REVIEWER guard (VA-69): the registry leaks serving
 * posture and becomes the operator Advocates panel home (VA-40, LLD §14.2), so AUTHOR stays out;
 * the URL rule in SecurityConfig pairs this for defense in depth.
 */
@Controller
@RequestMapping("/models")
@PreAuthorize("hasRole('REVIEWER')")
class ModelsController(
    private val training: TrainingService,
    private val serving: AdvocateServingService,
    private val eval: Stage4EvalService,
    private val props: AppProperties,
) {

    @GetMapping
    fun index(model: Model): String {
        model.addAttribute("pageTitle", "Models")
        model.addAttribute("families", training.versionsByFamily())
        model.addAttribute("servingEnabled", props.serving.enabled)
        return "models/index"
    }

    @GetMapping("/{id}/serve")
    fun serve(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val version =
            training.version(id)
                ?: run {
                    ra.addFlashAttribute("error", "Version not found")
                    return "redirect:/models"
                }
        model.addAttribute("pageTitle", "Serve ${version.displayName}")
        model.addAttribute("v", version)
        model.addAttribute("serveCommand", training.serveCommand(version))
        model.addAttribute("servingEnabled", props.serving.enabled)
        model.addAttribute("servingTarget", serving.backend()?.target() ?: "")
        return "models/serve"
    }

    /** Initiate serving of a READY version onto the shared endpoint (submit-then-poll). */
    @PostMapping("/{id}/serving/start")
    @PreAuthorize("hasRole('REVIEWER')")
    fun startServing(@PathVariable id: String, ra: RedirectAttributes): String {
        serving
            .serve(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Serving ${it.displayName}: ${it.servingState} — poll for status " +
                            "(cold deploy is ~25–35 min; tear down when done).",
                    )
                },
            )
        return "redirect:/models/$id/serve"
    }

    /** Tear down a LIVE (or FAILED) serve — the cost guard; a deployed replica bills until gone. */
    @PostMapping("/{id}/serving/teardown")
    @PreAuthorize("hasRole('REVIEWER')")
    fun teardownServing(@PathVariable id: String, ra: RedirectAttributes): String {
        serving
            .teardown(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute("ok", "Tearing down ${it.displayName}: ${it.servingState}")
                },
            )
        return "redirect:/models/$id/serve"
    }

    /** Advance an in-flight DEPLOYING/TEARING_DOWN serve (manual poll; the page auto-polls too). */
    @PostMapping("/{id}/serving/poll")
    fun pollServing(@PathVariable id: String, ra: RedirectAttributes): String {
        serving
            .poll(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "Serving status: ${it.servingState}") },
            )
        return "redirect:/models/$id/serve"
    }

    /**
     * The §14 behavioral eval page (VA-60): start form, live run card while the transport
     * deploys/probes/releases, and the advisory report + probe transcripts once landed.
     */
    @GetMapping("/{id}/eval")
    fun evalPage(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val version =
            training.version(id)
                ?: run {
                    ra.addFlashAttribute("error", "Version not found")
                    return "redirect:/models"
                }
        val run = eval.latestForVersion(id)
        model.addAttribute("pageTitle", "Eval ${version.displayName}")
        model.addAttribute("v", version)
        model.addAttribute("run", run)
        model.addAttribute("transport", props.serving.evalTransport)
        model.addAttribute("active", run != null && !run.status.terminal)
        model.addAttribute("report", version.evalReport?.let { parseReport(it) })
        // Transcripts of the reported run (fall back to the latest run while one is active).
        val transcriptRunId = version.evalRunId ?: run?.id
        model.addAttribute(
            "probes",
            transcriptRunId?.let { eval.probesOf(it) } ?: emptyList<Any>(),
        )
        return "models/eval"
    }

    /** Start an eval over the configured transport (endpoint deploys are ~25–35 min cold). */
    @PostMapping("/{id}/eval/start")
    @PreAuthorize("hasRole('REVIEWER')")
    fun startEval(@PathVariable id: String, ra: RedirectAttributes): String {
        eval
            .start(id, CurrentUser.email())
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Eval started over '${it.transport}' (${it.counters["probes"]} probes) — " +
                            if (it.status == EvalRunStatus.DEPLOYING)
                                "deploying (~25–35 min cold); this page polls it forward."
                            else "probing; this page polls it forward.",
                    )
                },
            )
        return "redirect:/models/$id/eval"
    }

    /** Advance an in-flight eval one bounded step (the page auto-polls too). */
    @PostMapping("/eval/runs/{runId}/poll")
    fun pollEval(@PathVariable runId: String, ra: RedirectAttributes): String {
        val versionId =
            eval.run(runId)?.versionId
                ?: run {
                    ra.addFlashAttribute("error", "Eval run not found")
                    return "redirect:/models"
                }
        eval.poll(runId).fold({ ra.addFlashAttribute("error", it.message) }, {})
        return "redirect:/models/$versionId/eval"
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseReport(json: String): Map<String, Any?>? =
        runCatching { Json.parse(json) as? Map<String, Any?> }.getOrNull()

    @PostMapping("/{id}/promote")
    @PreAuthorize("hasRole('REVIEWER')")
    fun promote(
        @PathVariable id: String,
        @RequestParam target: String,
        ra: RedirectAttributes
    ): String {
        val promotion = runCatching { Promotion.valueOf(target.uppercase()) }.getOrNull()
        if (promotion == null) {
            ra.addFlashAttribute("error", "Invalid promotion target")
            return "redirect:/models"
        }
        training
            .promote(id, promotion)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "${it.displayName} → ${it.promotion}") },
            )
        return "redirect:/models"
    }

    @PostMapping("/{id}/checkpoint")
    @PreAuthorize("hasRole('REVIEWER')")
    fun checkpoint(
        @PathVariable id: String,
        @RequestParam checkpointUri: String,
        ra: RedirectAttributes,
    ): String {
        training
            .setCheckpoint(id, checkpointUri)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { ra.addFlashAttribute("ok", "${it.displayName} → READY (checkpoint set)") },
            )
        return "redirect:/models"
    }
}

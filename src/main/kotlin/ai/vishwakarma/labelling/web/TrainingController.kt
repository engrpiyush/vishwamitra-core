package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.BaseKind
import ai.vishwakarma.labelling.domain.DatasetSource
import ai.vishwakarma.labelling.domain.Hyperparams
import ai.vishwakarma.labelling.domain.TuningMethod
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.BaseModelService
import ai.vishwakarma.labelling.service.ExportService
import ai.vishwakarma.labelling.service.ImportService
import ai.vishwakarma.labelling.service.PollOutcome
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

@Controller
@RequestMapping("/training")
@PreAuthorize("hasRole('REVIEWER')")
class TrainingController(
    private val training: TrainingService,
    private val baseModels: BaseModelService,
    private val exports: ExportService,
    private val imports: ImportService,
) {

    @GetMapping
    fun index(@RequestParam(required = false) parent: String?, model: Model): String {
        model.addAttribute("pageTitle", "Training")
        model.addAttribute("jobs", training.jobs())
        model.addAttribute("baseModels", baseModels.list(activeOnly = true))
        model.addAttribute("readyVersions", training.readyVersions())
        model.addAttribute("exports", exports.history())
        model.addAttribute("validImports", imports.listValid())
        model.addAttribute("methods", TuningMethod.entries)
        model.addAttribute(
            "adapterSizes",
            listOf(
                "ADAPTER_SIZE_ONE",
                "ADAPTER_SIZE_FOUR",
                "ADAPTER_SIZE_EIGHT",
                "ADAPTER_SIZE_SIXTEEN"
            )
        )
        model.addAttribute("defaultParent", parent ?: training.latestReady()?.id)
        return "training/index"
    }

    @PostMapping("/submit")
    fun submit(
        @RequestParam baseKind: String,
        @RequestParam(required = false) baseModelId: String?,
        @RequestParam(required = false) parentVersionId: String?,
        @RequestParam dataset: String,
        @RequestParam method: String,
        @RequestParam(defaultValue = "3") epochCount: Int,
        @RequestParam(defaultValue = "ADAPTER_SIZE_FOUR") adapterSize: String,
        @RequestParam(defaultValue = "0.0002") learningRate: Double,
        ra: RedirectAttributes,
    ): String {
        val kind = runCatching { BaseKind.valueOf(baseKind.uppercase()) }.getOrNull()
        val tuningMethod = runCatching { TuningMethod.valueOf(method.uppercase()) }.getOrNull()
        if (kind == null || tuningMethod == null) {
            ra.addFlashAttribute("error", "Invalid base kind or method")
            return "redirect:/training"
        }
        // Dataset dropdown posts a composite "EXPORT:<id>" / "IMPORT:<id>" value.
        val sourceToken = dataset.substringBefore(':', "")
        val datasetId = dataset.substringAfter(':', "")
        val datasetSource =
            runCatching { DatasetSource.valueOf(sourceToken.uppercase()) }.getOrNull()
        if (datasetSource == null) {
            ra.addFlashAttribute("error", "Select a dataset to tune on")
            return "redirect:/training"
        }
        training
            .submit(
                baseKind = kind,
                baseModelId = baseModelId,
                parentVersionId = parentVersionId,
                datasetSource = datasetSource,
                datasetId = datasetId,
                method = tuningMethod,
                hp = Hyperparams(epochCount, adapterSize, learningRate),
                actor = CurrentUser.email(),
            )
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute("ok", "Submitted tune → ${it.displayName} (${it.version})")
                },
            )
        return "redirect:/training"
    }

    @PostMapping("/jobs/{id}/poll")
    fun poll(@PathVariable id: String, ra: RedirectAttributes): String {
        training
            .pollJob(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                { outcome ->
                    when (outcome) {
                        is PollOutcome.Updated ->
                            ra.addFlashAttribute("ok", "Job status: ${outcome.job.status}")
                        is PollOutcome.NeedsWeights -> {
                            // Open the weights-confirmation popup (prefilled with the suggestion).
                            ra.addFlashAttribute("confirmJobId", outcome.jobId)
                            ra.addFlashAttribute("suggestedCheckpoint", outcome.suggested ?: "")
                        }
                    }
                },
            )
        return "redirect:/training"
    }

    @PostMapping("/jobs/{id}/confirm-weights")
    fun confirmWeights(
        @PathVariable id: String,
        @RequestParam checkpointUri: String,
        ra: RedirectAttributes,
    ): String {
        training
            .confirmWeights(id, checkpointUri)
            .fold(
                {
                    // Reopen the popup prefilled with what the user typed so they can correct it.
                    ra.addFlashAttribute("error", it.message)
                    ra.addFlashAttribute("confirmJobId", id)
                    ra.addFlashAttribute("suggestedCheckpoint", checkpointUri)
                },
                { ra.addFlashAttribute("ok", "Marked ${it.displayName} READY (${it.version})") },
            )
        return "redirect:/training"
    }

    @PostMapping("/jobs/{id}/no-weights")
    fun noWeights(@PathVariable id: String, ra: RedirectAttributes): String {
        training
            .markNoWeights(id)
            .fold(
                { ra.addFlashAttribute("error", it.message) },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Marked version WEIGHTS_NOT_FOUND — set the checkpoint manually on Models"
                    )
                },
            )
        return "redirect:/training"
    }
}

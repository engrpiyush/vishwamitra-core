package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.service.DpoService
import ai.vishwakarma.labelling.service.ExportService
import ai.vishwakarma.labelling.service.SftService
import ai.vishwakarma.labelling.service.TrainingService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Landing dashboard: counts by status/kind, recent exports, and the current model version.
 */
@Controller
class DashboardController(
    private val sft: SftService,
    private val dpo: DpoService,
    private val exports: ExportService,
    private val training: TrainingService,
) {

    @GetMapping("/")
    fun dashboard(model: Model): String {
        val sftAll = sft.list()
        val dpoAll = dpo.list()

        model.addAttribute("pageTitle", "Dashboard")
        model.addAttribute(
            "tiles",
            listOf(
                Tile("SFT examples", sftAll.size.toString(), "${sftAll.count { it.status == ExampleStatus.APPROVED }} approved", "/sft"),
                Tile("DPO pairs", dpoAll.size.toString(), "${dpoAll.count { it.status == ExampleStatus.APPROVED }} approved", "/dpo"),
                Tile("Exports", exports.history().size.toString(), "snapshots", "/export"),
                Tile("Model versions", training.readyVersions().size.toString(), "ready", "/models"),
            ),
        )
        model.addAttribute("recentExports", exports.history().take(5))
        model.addAttribute("currentVersion", training.currentVersion())
        return "index"
    }

    data class Tile(val label: String, val value: String, val sub: String, val href: String)
}

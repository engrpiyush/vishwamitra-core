package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.ConsentStatus
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.SubjectStatus
import ai.vishwakarma.labelling.domain.splitLabels
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.service.AssetPatch
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.LinkRegistration
import ai.vishwakarma.labelling.service.SubjectService
import java.time.LocalDate
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
 * Stage 1 (Intake & Manifest) server-rendered UI. Mirrors [SftController]: REVIEWER-gated Thymeleaf
 * MVC that calls [SubjectService] / [IntakeService] directly via POST-redirect-GET. It does *not*
 * proxy the JSON API — the `/api/intake` surface is reserved for the (separate) browser→GCS
 * signed-URL uploader, the one flow a plain form can't do.
 */
@Controller
@RequestMapping("/intake")
@PreAuthorize("hasRole('REVIEWER')")
class IntakeController(
    private val subjectService: SubjectService,
    private val intake: IntakeService,
) {

    private fun actor(): String? = CurrentUser.email()

    // ---- Subjects ---------------------------------------------------------
    @GetMapping
    fun list(model: Model): String {
        val subjects = subjectService.list()
        val manifests: Map<String, IntakeManifest> =
            subjects.associate { it.id to intake.manifest(it.id) }
        model.addAttribute("pageTitle", "Intake")
        model.addAttribute("subjects", subjects)
        model.addAttribute("manifests", manifests)
        return "intake/list"
    }

    @PostMapping
    fun createSubject(
        @RequestParam displayName: String,
        @RequestParam(required = false) handle: String?,
        @RequestParam(required = false) notes: String?,
        ra: RedirectAttributes,
    ): String =
        subjectService
            .create(actor(), displayName, handle, notes ?: "")
            .fold(
                {
                    flashError(ra, it)
                    "redirect:/intake"
                },
                {
                    ra.addFlashAttribute("ok", "Created ${it.displayName}")
                    "redirect:/intake/${it.id}"
                },
            )

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val assets = intake.listAssets(id)
        model.addAttribute("pageTitle", subject.displayName)
        model.addAttribute("subject", subject)
        // Grouped by source class, in enum order, so the detail page reads top-down.
        model.addAttribute(
            "assetsByClass",
            SourceClass.entries
                .associateWith { sc -> assets.filter { it.sourceClass == sc } }
                .filterValues { it.isNotEmpty() },
        )
        model.addAttribute("assetCount", assets.size)
        model.addAttribute("manifest", intake.manifest(id))
        // Dropdown catalogs.
        model.addAttribute("contentTypes", ContentType.entries)
        model.addAttribute(
            "linkContentTypes",
            ContentType.entries.filter { it.sourceClass == SourceClass.PUBLIC_PROFILE },
        )
        model.addAttribute("relationships", Relationship.entries)
        model.addAttribute("consentStatuses", ConsentStatus.entries)
        model.addAttribute("authenticityTiers", AuthenticityTier.entries)
        // Upload form: pickable modalities (LINK has no bytes — it uses the links form).
        model.addAttribute(
            "modalities",
            AssetModality.entries.filter { it != AssetModality.LINK },
        )
        return "intake/detail"
    }

    @PostMapping("/{id}/edit")
    fun editSubject(
        @PathVariable id: String,
        @RequestParam(required = false) displayName: String?,
        @RequestParam(required = false) handle: String?,
        @RequestParam(required = false) notes: String?,
        @RequestParam(required = false) status: String?,
        ra: RedirectAttributes,
    ): String {
        subjectService
            .update(id, displayName, handle, notes, SubjectStatus.fromOrNull(status))
            .notify(ra)
        return "redirect:/intake/$id"
    }

    @PostMapping("/{id}/delete")
    fun deleteSubject(@PathVariable id: String, ra: RedirectAttributes): String {
        // Purge assets (bytes + records) + manifest first, then the subject doc.
        intake.purgeSubject(id)
        subjectService
            .delete(id)
            .fold(
                { flashError(ra, it) },
                { ra.addFlashAttribute("ok", "Subject deleted") },
            )
        return "redirect:/intake"
    }

    // ---- Links ------------------------------------------------------------
    @PostMapping("/{id}/links")
    fun addLink(
        @PathVariable id: String,
        @RequestParam title: String,
        @RequestParam contentType: String,
        @RequestParam externalUrl: String,
        @RequestParam(required = false) relationship: String?,
        @RequestParam(required = false) sourceName: String?,
        @RequestParam(required = false) consentStatus: String?,
        @RequestParam(required = false) labels: String?,
        @RequestParam(required = false) notes: String?,
        ra: RedirectAttributes,
    ): String {
        val ct =
            ContentType.fromOrNull(contentType)
                ?: run {
                    ra.addFlashAttribute("error", "Invalid content type")
                    return "redirect:/intake/$id"
                }
        val reg =
            LinkRegistration(
                title = title,
                contentType = ct,
                externalUrl = externalUrl,
                relationship = Relationship.fromOrNull(relationship) ?: Relationship.UNKNOWN,
                sourceName = sourceName?.takeIf { it.isNotBlank() },
                consentStatus =
                    ConsentStatus.fromOrNull(consentStatus) ?: ConsentStatus.NOT_REQUIRED,
                labels = splitLabels(labels),
                notes = notes ?: "",
            )
        intake.registerLink(id, actor(), reg).notify(ra, "Link added")
        return "redirect:/intake/$id"
    }

    // ---- Assets -----------------------------------------------------------
    @PostMapping("/assets/{assetId}/edit")
    fun editAsset(
        @PathVariable assetId: String,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) contentType: String?,
        @RequestParam(required = false) relationship: String?,
        @RequestParam(required = false) authenticityPrior: String?,
        @RequestParam(required = false) sourceName: String?,
        @RequestParam(required = false) captureDate: String?,
        @RequestParam(required = false) claimedEventDate: String?,
        @RequestParam(required = false) consentStatus: String?,
        @RequestParam(required = false) consentNote: String?,
        @RequestParam(required = false) labels: String?,
        @RequestParam(required = false) notes: String?,
        ra: RedirectAttributes,
    ): String {
        val back = "redirect:/intake/${intake.getAsset(assetId)?.subjectId ?: ""}"
        val capture: LocalDate?
        val claimed: LocalDate?
        try {
            capture = date(captureDate)
            claimed = date(claimedEventDate)
        } catch (e: BadDate) {
            ra.addFlashAttribute("error", "Invalid date '${e.raw}' (expected yyyy-MM-dd)")
            return back
        }
        val patch =
            AssetPatch(
                title = title?.takeIf { it.isNotBlank() },
                contentType = contentType?.let { ContentType.fromOrNull(it) },
                relationship = relationship?.let { Relationship.fromOrNull(it) },
                authenticityPrior = authenticityPrior?.let { AuthenticityTier.fromOrNull(it) },
                sourceName = sourceName,
                captureDate = capture,
                claimedEventDate = claimed,
                consentStatus = consentStatus?.let { ConsentStatus.fromOrNull(it) },
                consentNote = consentNote,
                labels = labels?.let { splitLabels(it) },
                notes = notes,
            )
        intake.updateAsset(assetId, patch).notify(ra)
        return back
    }

    @PostMapping("/assets/{assetId}/delete")
    fun deleteAsset(@PathVariable assetId: String, ra: RedirectAttributes): String {
        val subjectId = intake.getAsset(assetId)?.subjectId
        intake.deleteAsset(assetId).notify(ra, "Asset deleted")
        return "redirect:/intake/${subjectId ?: ""}"
    }

    /** Plain, no-JS preview: 302 to the external URL or a short-lived signed GET. */
    @GetMapping("/assets/{assetId}/preview")
    fun preview(@PathVariable assetId: String, ra: RedirectAttributes): String {
        val subjectId = intake.getAsset(assetId)?.subjectId
        return intake
            .downloadUrl(assetId)
            .fold(
                {
                    flashError(ra, it)
                    "redirect:/intake/${subjectId ?: ""}"
                },
                { "redirect:$it" },
            )
    }

    // ---- Manifest / reconcile --------------------------------------------
    /**
     * Seal review page: a read-only preview of everything registered for the subject, with
     * per-asset confirmation checkboxes and a global approval checkbox (both UI-only — native
     * `required` validation), plus the mandatory note the service records in the audit history.
     */
    @GetMapping("/{id}/seal")
    fun sealReview(@PathVariable id: String, model: Model, ra: RedirectAttributes): String {
        val subject =
            subjectService.get(id)
                ?: run {
                    ra.addFlashAttribute("error", "Subject not found")
                    return "redirect:/intake"
                }
        val manifest = intake.manifest(id)
        if (manifest.sealed) {
            ra.addFlashAttribute("error", "Manifest is already sealed")
            return "redirect:/intake/$id"
        }
        if (manifest.sealBlockers.isNotEmpty()) {
            ra.addFlashAttribute(
                "error",
                "Cannot seal yet: ${manifest.sealBlockers.joinToString("; ")}",
            )
            return "redirect:/intake/$id"
        }
        val assets = intake.listAssets(id)
        model.addAttribute("pageTitle", "Seal · ${subject.displayName}")
        model.addAttribute("subject", subject)
        model.addAttribute("manifest", manifest)
        model.addAttribute(
            "assetsByClass",
            SourceClass.entries
                .associateWith { sc -> assets.filter { it.sourceClass == sc } }
                .filterValues { it.isNotEmpty() },
        )
        model.addAttribute("assetCount", assets.size)
        return "intake/seal"
    }

    @PostMapping("/{id}/seal")
    fun seal(
        @PathVariable id: String,
        @RequestParam(required = false) note: String?,
        ra: RedirectAttributes,
    ): String {
        intake.sealManifest(id, actor(), note ?: "").notify(ra, "Manifest sealed")
        return "redirect:/intake/$id"
    }

    /** Reverse a seal — ADMIN-only (reviewers can seal but not unseal). Note is mandatory. */
    @PostMapping("/{id}/unseal")
    @PreAuthorize("hasRole('ADMIN')")
    fun unseal(
        @PathVariable id: String,
        @RequestParam(required = false) note: String?,
        ra: RedirectAttributes,
    ): String {
        intake.unsealManifest(id, actor(), note ?: "").notify(ra, "Manifest unsealed")
        return "redirect:/intake/$id"
    }

    @PostMapping("/{id}/reconcile")
    fun reconcile(@PathVariable id: String, ra: RedirectAttributes): String {
        val changed = intake.reconcileSubject(id)
        if (changed.isEmpty()) ra.addFlashAttribute("ok", "Nothing to reconcile")
        else ra.addFlashAttribute("ok", "Reconciled ${changed.size} asset(s)")
        return "redirect:/intake/$id"
    }

    // ---- helpers ----------------------------------------------------------
    private class BadDate(val raw: String?) : RuntimeException()

    private fun date(raw: String?): LocalDate? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { LocalDate.parse(s) }.getOrElse { throw BadDate(s) }
    }

    private fun flashError(ra: RedirectAttributes, err: DomainError) =
        ra.addFlashAttribute("error", err.message)

    /** Either→flash: the error message on Left, [okMessage] on Right. Mirrors SftController. */
    private fun Any?.notify(ra: RedirectAttributes, okMessage: String = "Saved") {
        this?.let {
            (it as? arrow.core.Either<*, *>)?.fold(
                { err -> ra.addFlashAttribute("error", (err as? DomainError)?.message ?: "Error") },
                { ra.addFlashAttribute("ok", okMessage) },
            )
        }
    }
}

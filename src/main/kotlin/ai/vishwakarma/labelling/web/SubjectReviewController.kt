package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.PiiChoice
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.ClaimReviewService
import ai.vishwakarma.labelling.service.IntakeService
import ai.vishwakarma.labelling.service.QuestionService
import ai.vishwakarma.labelling.service.Stage2Service
import ai.vishwakarma.labelling.service.StageConfigService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The S6 review wizard, subject-skinned (VA-33, product LLD §8.1) — the same three §12.6 steps the
 * operator wizard runs (the `intake/review` templates), over the same [ClaimReviewService] calls,
 * so the resulting `claim_reviews` rows are shape-identical whichever door they came through.
 * Deltas are presentation only: subject vocabulary throughout (no scores/tiers/decision enums —
 * §12.3), approve-by-default framing, and an F11 slot on rows worth attention (the question text
 * lands with the W5 inbox ticket; until then the plain subject-friendly framing renders). Every
 * per-row action re-asserts the claim belongs to the host subject (§12.1).
 */
@Controller
@RequestMapping("/s/training/review")
class SubjectReviewController(
    private val reviewService: ClaimReviewService,
    private val intake: IntakeService,
    private val stage2: Stage2Service,
    private val claims: ClaimRepository,
    private val config: StageConfigService,
    private val questions: QuestionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun ctx(request: HttpServletRequest): SubjectCtx =
        SubjectCtx.of(request) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    // ---- lifecycle ------------------------------------------------------------

    /** The home-page CTA: freeze the claims and enter the wizard. */
    @PostMapping("/start")
    fun start(request: HttpServletRequest, ra: RedirectAttributes): String {
        val ctx = ctx(request)
        return reviewService
            .startReview(ctx.subjectId)
            .fold(
                { err ->
                    log.warn("Subject review start refused for {}: {}", ctx.subjectId, err.message)
                    ra.addFlashAttribute("error", SubjectTrainingController.GENERIC_SORRY)
                    "redirect:/training"
                },
                {
                    // F11 (§9.2): questions generate the moment claims lock — never blocking.
                    questions.generateForReview(ctx.subjectId)
                    "redirect:/training/review/decide"
                },
            )
    }

    /** Preview's SUBMIT: decisions final; the whole training area flips read-only (S2 state). */
    @PostMapping("/submit")
    fun submit(request: HttpServletRequest, ra: RedirectAttributes): String {
        val ctx = ctx(request)
        return reviewService
            .submitReview(ctx.subjectId)
            .fold(
                { err ->
                    log.warn("Subject review submit refused for {}: {}", ctx.subjectId, err.message)
                    ra.addFlashAttribute("error", SubjectTrainingController.GENERIC_SORRY)
                    "redirect:/training/review/preview"
                },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "That's everything — your advocate is being prepared.",
                    )
                    "redirect:/training"
                },
            )
    }

    // ---- step 1: decide ---------------------------------------------------------

    /** One decide-step row, pre-chewed so the template stays vocabulary-free. */
    data class DecideRow(
        val id: String,
        val text: String,
        val source: String,
        val excerpt: String?,
        /** Worth a closer look (INFERRED / unfavorable / unscored) — renders the framing slot. */
        val attention: Boolean,
        /** Only read-between-the-lines rows may be set aside (INFERRED-only reject rule). */
        val inferred: Boolean,
        val chipLabel: String,
        val chipClass: String,
        val justification: String?,
        val rejected: Boolean,
        /** The row's OPEN F11 question, when one exists — it replaces the plain framing (§9.3). */
        val questionId: String? = null,
        val questionText: String? = null,
    )

    @GetMapping("/decide")
    fun decide(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        wizardGuard(ctx)?.let {
            return it
        }
        val threshold = config.stage2().favorabilityThreshold
        val partition = reviewService.partition(ctx.subjectId)
        val titles = intake.listAssets(ctx.subjectId).associate { it.id to it.title }
        // F11 (§9.3): an unfavorable claim's OPEN question frames its row — one store, two doors.
        val openQuestions = questions.openByClaim(ctx.subjectId)
        val rows =
            stage2
                .listClaims(ctx.subjectId)
                .sortedWith(
                    compareByDescending<Claim> { SubjectTraining.needsAttention(it, threshold) }
                        .thenBy { it.favorability ?: 0.0 }
                )
                .map { c ->
                    val review = partition.reviews[c.id]
                    val decision = review?.decision
                    DecideRow(
                        id = c.id,
                        text = c.text,
                        source = titles[c.assetId] ?: "One of your uploads",
                        excerpt = c.sourceExcerpt,
                        attention = SubjectTraining.needsAttention(c, threshold),
                        inferred = c.claimBasis == ClaimBasis.INFERRED,
                        chipLabel =
                            when (decision) {
                                ReviewDecision.CONTESTED -> "Set aside"
                                ReviewDecision.SIDECARED -> "With your note"
                                else -> "Included"
                            },
                        chipClass =
                            when (decision) {
                                ReviewDecision.CONTESTED -> "badge--muted"
                                ReviewDecision.SIDECARED -> "badge--info"
                                else -> "badge--success"
                            },
                        justification = review?.justification,
                        rejected = decision == ReviewDecision.CONTESTED,
                        questionId = openQuestions[c.id]?.id,
                        questionText = openQuestions[c.id]?.questionText,
                    )
                }
        model.addAttribute("pageTitle", "Review — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("step", 1)
        model.addAttribute("rows", rows)
        model.addAttribute("attentionCount", rows.count { it.attention })
        return "subject/review/decide"
    }

    /**
     * Save one row: reject (INFERRED only) beats a note; a note means keep-with-context
     * (SIDECARED); neither means plainly included (APPROVED — also how an earlier note or set-aside
     * is undone). Same service semantics as the operator flow.
     */
    @PostMapping("/claims/{claimId}/decision")
    fun saveDecision(
        request: HttpServletRequest,
        @PathVariable claimId: String,
        @RequestParam(required = false) reject: String?,
        @RequestParam(required = false) justification: String?,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        ownClaim(claimId, ctx)
        val note = justification?.trim()?.takeIf { it.isNotBlank() }
        val decision =
            when {
                reject != null -> ReviewDecision.CONTESTED
                note != null -> ReviewDecision.SIDECARED
                else -> ReviewDecision.APPROVED
            }
        reviewService
            .reviewClaim(claimId, decision, note, emptyList(), CurrentUser.email())
            .fold(
                { err ->
                    log.warn("Subject decision refused for claim {}: {}", claimId, err.message)
                    ra.addFlashAttribute("error", SubjectTrainingController.GENERIC_SORRY)
                },
                { ra.addFlashAttribute("ok", "Saved.") },
            )
        return "redirect:/training/review/decide#c-$claimId"
    }

    // ---- step 2: private details (PII) ------------------------------------------

    /** One PII-step row: sensitive claims default private; INCLUDE is an explicit click (F4). */
    data class PiiRow(
        val id: String,
        val text: String,
        val excerpt: String?,
        val included: Boolean,
    )

    @GetMapping("/pii")
    fun pii(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        wizardGuard(ctx)?.let {
            return it
        }
        val partition = reviewService.partition(ctx.subjectId)
        val rows =
            partition.sensitive.map { c ->
                PiiRow(
                    id = c.id,
                    text = c.text,
                    excerpt = c.sourceExcerpt,
                    included = partition.reviews[c.id]?.piiChoice == PiiChoice.INCLUDE,
                )
            }
        model.addAttribute("pageTitle", "Private details — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("step", 2)
        model.addAttribute("rows", rows)
        return "subject/review/pii"
    }

    @PostMapping("/claims/{claimId}/pii")
    fun savePii(
        request: HttpServletRequest,
        @PathVariable claimId: String,
        @RequestParam choice: String,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        ownClaim(claimId, ctx)
        val pii = PiiChoice.fromOrNull(choice) ?: PiiChoice.HIDE
        reviewService
            .setPii(claimId, pii, CurrentUser.email())
            .fold(
                { err ->
                    log.warn("Subject PII choice refused for claim {}: {}", claimId, err.message)
                    ra.addFlashAttribute("error", SubjectTrainingController.GENERIC_SORRY)
                },
                { ra.addFlashAttribute("ok", "Saved.") },
            )
        return "redirect:/training/review/pii"
    }

    // ---- step 3: preview + submit -------------------------------------------------

    @GetMapping("/preview")
    fun preview(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        wizardGuard(ctx)?.let {
            return it
        }
        val summary = reviewService.summary(ctx.subjectId)
        model.addAttribute("pageTitle", "Confirm — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("step", 3)
        model.addAttribute("summary", summary)
        model.addAttribute(
            "nonApproved",
            summary.nonApproved.map { n ->
                mapOf(
                    "text" to n.claim.text,
                    "setAside" to (n.review?.decision == ReviewDecision.CONTESTED),
                    "note" to n.review?.justification,
                )
            },
        )
        return "subject/review/preview"
    }

    // ---- helpers -------------------------------------------------------------------

    /**
     * Wizard pages exist only mid-review: not started → back home (the CTA starts it); already
     * submitted → the read-only S2 (re-submission impossible, VA-33 AC).
     */
    private fun wizardGuard(ctx: SubjectCtx): String? {
        val manifest = intake.manifest(ctx.subjectId)
        if (manifest.reviewLockedAt == null || manifest.reviewSubmittedAt != null)
            return "redirect:/training"
        return null
    }

    /** §12.1: a claim id in the URL must belong to the host subject — anything else is a 404. */
    private fun ownClaim(claimId: String, ctx: SubjectCtx) {
        val claim = claims.findById(claimId)
        if (claim == null || claim.subjectId != ctx.subjectId)
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
}

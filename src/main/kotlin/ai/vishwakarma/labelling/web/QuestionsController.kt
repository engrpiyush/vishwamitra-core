package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.QuestionStatus
import ai.vishwakarma.labelling.domain.SubjectQuestion
import ai.vishwakarma.labelling.security.CurrentUser
import ai.vishwakarma.labelling.security.SubjectCtx
import ai.vishwakarma.labelling.service.DomainError
import ai.vishwakarma.labelling.service.QuestionService
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
 * The S7 "Questions for you" inbox (VA-35, product LLD §9.3) — one of the two doors onto
 * `subject_questions` (the review wizard's Decide step is the other; both resolve the same rows).
 * Subject-of-host gated by the chain rule on the `/s/questions` subtree; every row action
 * re-asserts the question belongs to the host subject (§12.1). Copy stays in subject vocabulary
 * (§12.3) — a question is "an invitation, not an obligation", so Skip is always one click.
 */
@Controller
@RequestMapping("/s/questions")
class QuestionsController(private val questions: QuestionService) {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun ctx(request: HttpServletRequest): SubjectCtx =
        SubjectCtx.of(request) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    /** One inbox card, pre-chewed so the template stays vocabulary-free. */
    data class QuestionCard(
        val id: String,
        val text: String,
        val answer: String?,
        val statusLabel: String,
        /** Only skipped questions offer Reopen (same-cycle re-invitation, VA-35 AC). */
        val reopenable: Boolean,
    )

    @GetMapping
    fun inbox(request: HttpServletRequest, model: Model): String {
        val ctx = ctx(request)
        val all = questions.listFor(ctx.subjectId)
        model.addAttribute("pageTitle", "Questions for you — ${ctx.displayName}")
        model.addAttribute("ctx", ctx)
        model.addAttribute("open", all.filter { it.status == QuestionStatus.OPEN }.map { card(it) })
        model.addAttribute(
            "history",
            all.filter { it.status != QuestionStatus.OPEN }.map { card(it) },
        )
        return "subject/questions"
    }

    @PostMapping("/{id}/answer")
    fun answer(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "") answer: String,
        @RequestParam(required = false) from: String?,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        val question = own(id, ctx)
        questions
            .answer(id, answer, CurrentUser.email())
            .fold(
                { err ->
                    log.warn("F11 answer refused for {}: {}", id, err.message)
                    ra.addFlashAttribute("error", friendly(err))
                },
                {
                    ra.addFlashAttribute(
                        "ok",
                        "Thank you — your context now rides along with your story.",
                    )
                },
            )
        return redirectAfter(from, question)
    }

    @PostMapping("/{id}/skip")
    fun skip(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestParam(required = false) from: String?,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        val question = own(id, ctx)
        questions
            .skip(id)
            .fold(
                { err ->
                    log.warn("F11 skip refused for {}: {}", id, err.message)
                    ra.addFlashAttribute("error", SubjectTrainingController.GENERIC_SORRY)
                },
                { ra.addFlashAttribute("ok", "Skipped — you can come back to it anytime.") },
            )
        return redirectAfter(from, question)
    }

    @PostMapping("/{id}/reopen")
    fun reopen(
        request: HttpServletRequest,
        @PathVariable id: String,
        ra: RedirectAttributes,
    ): String {
        val ctx = ctx(request)
        own(id, ctx)
        questions
            .reopen(id)
            .fold(
                { err ->
                    log.warn("F11 reopen refused for {}: {}", id, err.message)
                    ra.addFlashAttribute("error", SubjectTrainingController.GENERIC_SORRY)
                },
                { ra.addFlashAttribute("ok", "It's back in your questions.") },
            )
        return "redirect:/questions"
    }

    // ---- helpers -------------------------------------------------------------------

    private fun card(q: SubjectQuestion): QuestionCard =
        QuestionCard(
            id = q.id,
            text = q.questionText,
            answer = q.answer,
            statusLabel =
                when (q.status) {
                    QuestionStatus.OPEN -> "Waiting for you"
                    QuestionStatus.ANSWERED -> "Answered"
                    QuestionStatus.SKIPPED -> "Skipped"
                },
            reopenable = q.status == QuestionStatus.SKIPPED,
        )

    /** §12.1: a question id in the URL must belong to the host subject — anything else is 404. */
    private fun own(id: String, ctx: SubjectCtx): SubjectQuestion {
        val question = questions.listFor(ctx.subjectId).find { it.id == id }
        return question ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    /**
     * The two doors bounce back to themselves: `from=review` returns to the Decide step anchored on
     * the question's own claim (server-derived — nothing user-supplied reaches the redirect).
     */
    private fun redirectAfter(from: String?, question: SubjectQuestion): String =
        if (from == "review") {
            val anchor = question.claimIds.firstOrNull()?.let { "#c-$it" } ?: ""
            "redirect:/training/review/decide$anchor"
        } else {
            "redirect:/questions"
        }

    private fun friendly(err: DomainError): String =
        when (err) {
            is DomainError.Invalid -> "Add a few words first — or skip the question."
            else -> SubjectTrainingController.GENERIC_SORRY
        }
}

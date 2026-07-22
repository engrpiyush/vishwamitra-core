package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.HedgeLevel
import ai.vishwakarma.labelling.domain.Stage4Category
import ai.vishwakarma.labelling.domain.Stage4Plan
import ai.vishwakarma.labelling.domain.Stage4Stamp
import ai.vishwakarma.labelling.domain.VoicingPlan
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.Stage4JudgmentRepository
import ai.vishwakarma.labelling.persistence.Stage4PlanRepository
import ai.vishwakarma.labelling.persistence.SubjectScoreRepository
import ai.vishwakarma.labelling.service.PersonaService
import ai.vishwakarma.labelling.service.StageConfigService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.StringTemplateResolver

/**
 * The FE half of kb-generation (VA-164): the plan panel must render the frozen *spec*
 * (title/intent) when the planner stored one instead of a phrased question. Two levels are pinned
 * here:
 * 1. the view-model [Stage4ReviewPanels.planPanel] — a spec plan (null [Stage4Plan.question], spec
 *    fields on the [VoicingPlan]) surfaces `specTitle`/`specIntent` and a null question, while a
 *    legacy plan surfaces the question and no spec;
 * 2. the Thymeleaf expressions the sft/dpo edit panels use to choose between the two lines,
 *    rendered through the real engine so a `th:if`/`th:text` typo fails here rather than in the
 *    browser.
 *
 * The render step deliberately exercises the plan-panel expressions in isolation rather than
 * booting the whole sft/dpo edit page: those pages dereference a rich SftExample/DpoPair this
 * change does not touch, and coupling a kb-generation test to them would break on any unrelated
 * page edit. The markup below mirrors the two `<p>` lines in `templates/sft/edit.html` and
 * `templates/dpo/edit.html` verbatim in their expressions.
 */
class Stage4ReviewPanelsTest {

    private fun panels(
        plans: Stage4PlanRepository = mock(Stage4PlanRepository::class.java),
        claims: ClaimRepository = mock(ClaimRepository::class.java),
    ) =
        Stage4ReviewPanels(
            judgments = mock(Stage4JudgmentRepository::class.java),
            plans = plans,
            claims = claims,
            subjectScores = mock(SubjectScoreRepository::class.java),
            personaService = mock(PersonaService::class.java),
            config = mock(StageConfigService::class.java),
        )

    private fun basePlan(planId: String) =
        VoicingPlan(
            planId = planId,
            rowId = 6,
            voice = "Context-mandatory",
            hedgeLevel = HedgeLevel.HEDGED,
            constraints = listOf("F5: voice dates and numbers at their stated precision."),
            sourceClaimIds = emptyList(),
            category = Stage4Category.QA,
        )

    private fun specPlan(planId: String = "plan-spec") =
        basePlan(planId)
            .copy(
                templateId = "tpl-recency",
                templateCategory = "career-timeline",
                specTitle = "Recency Windowing",
                specIntent = "probe how current the record is",
                specPersonaLens = "a recruiter",
                specFormatConstraints = listOf("Turn shape: 4-6 turn probe"),
            )

    // ---- the view model --------------------------------------------------------------

    @Test
    fun `planPanel surfaces the spec when the plan carries no phrased question`() {
        val plans = mock(Stage4PlanRepository::class.java)
        // A kb-generation plan: the planner froze a spec and nulled the phrased question.
        `when`(plans.findById("plan-spec"))
            .thenReturn(Stage4Plan(subjectId = "s1", question = null, plan = specPlan()))

        val panel = panels(plans = plans).planPanel(Stage4Stamp("s1", planId = "plan-spec"))!!

        assertNull(panel.question, "a spec plan must not carry a phrased question")
        assertEquals("Recency Windowing", panel.specTitle)
        assertEquals("probe how current the record is", panel.specIntent)
    }

    @Test
    fun `planPanel keeps the phrased question and no spec on a legacy plan`() {
        val plans = mock(Stage4PlanRepository::class.java)
        `when`(plans.findById("plan-legacy"))
            .thenReturn(
                Stage4Plan(
                    subjectId = "s1",
                    question = "What was her role in the migration?",
                    plan = basePlan("plan-legacy"),
                )
            )

        val panel = panels(plans = plans).planPanel(Stage4Stamp("s1", planId = "plan-legacy"))!!

        assertEquals("What was her role in the migration?", panel.question)
        assertNull(panel.specTitle, "a legacy plan must carry no spec")
        assertNull(panel.specIntent)
    }

    // ---- the Thymeleaf expressions the panel templates use ---------------------------

    /**
     * The engine that renders the plan-panel snippet. A [SpringTemplateEngine] (SpEL, the Spring
     * dialect) rather than the bare [org.thymeleaf.TemplateEngine] (OGNL): OGNL is only an optional
     * Thymeleaf dependency and Spring Boot never puts it on the classpath, and — more to the point
     * — the sft/dpo edit pages this snippet mirrors are rendered by Spring's Thymeleaf, so
     * evaluating the copied expressions through the same SpEL dialect keeps the test faithful to
     * production (the same idiom as [ProfileTemplateRenderTest]).
     */
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(StringTemplateResolver().apply { templateMode = TemplateMode.HTML })
        }

    /** The two `<p>` lines from sft/edit.html + dpo/edit.html, expressions verbatim. */
    private val panelSnippet =
        """
        <div>
          <p th:if="${'$'}{planPanel.question != null}">planned question:
             <em th:text="${'$'}{planPanel.question}">q</em></p>
          <p th:if="${'$'}{planPanel.question == null and planPanel.specTitle != null}">conversation spec:
             <em th:text="${'$'}{planPanel.specTitle}">title</em><span
                 th:if="${'$'}{planPanel.specIntent != null}"
                 th:text="' — ' + ${'$'}{planPanel.specIntent}"> — intent</span></p>
        </div>
        """
            .trimIndent()

    private fun render(panel: PlanPanelView): String =
        engine.process(panelSnippet, Context().apply { setVariable("planPanel", panel) })

    @Test
    fun `the panel renders the spec line, not a question line, for a spec plan`() {
        val plans = mock(Stage4PlanRepository::class.java)
        `when`(plans.findById("plan-spec"))
            .thenReturn(Stage4Plan(subjectId = "s1", question = null, plan = specPlan()))
        val panel = panels(plans = plans).planPanel(Stage4Stamp("s1", planId = "plan-spec"))!!

        val html = render(panel)

        assertTrue(html.contains("conversation spec:"), html)
        assertTrue(html.contains("Recency Windowing"), html)
        assertTrue(html.contains("— probe how current the record is"), html)
        assertTrue("planned question:" !in html, html)
    }

    @Test
    fun `the panel renders the question line, not a spec line, for a legacy plan`() {
        val plans = mock(Stage4PlanRepository::class.java)
        `when`(plans.findById("plan-legacy"))
            .thenReturn(
                Stage4Plan(
                    subjectId = "s1",
                    question = "What was her role in the migration?",
                    plan = basePlan("plan-legacy"),
                )
            )
        val panel = panels(plans = plans).planPanel(Stage4Stamp("s1", planId = "plan-legacy"))!!

        val html = render(panel)

        assertTrue(html.contains("planned question:"), html)
        assertTrue(html.contains("What was her role in the migration?"), html)
        assertTrue("conversation spec:" !in html, html)
    }
}

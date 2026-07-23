package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.JudgeVerdict
import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.service.CatalogService
import ai.vishwakarma.labelling.service.DraftingService
import ai.vishwakarma.labelling.service.ScenarioService
import ai.vishwakarma.labelling.service.SftService
import ai.vishwakarma.labelling.service.Stage4OverrideApproveOutcome
import ai.vishwakarma.labelling.service.Stage4Service
import ai.vishwakarma.labelling.service.SubjectService
import ai.vishwakarma.labelling.service.TaxonomyService
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.StringTemplateResolver

/**
 * VA-176 server-side gate + review-list markup, the two halves a service-level test cannot reach.
 *
 * The **security** half proves the override endpoint's ADMIN gate is real, not UI-only: the
 * `@PreAuthorize("hasRole('ADMIN')")` on [SftController.overrideApprove] runs through a real
 * `@EnableMethodSecurity` proxy (with the production ADMIN ⊃ REVIEWER ⊃ AUTHOR hierarchy), so a
 * non-admin operator is refused with an [AccessDeniedException] — the 403 a browser sees — while an
 * ADMIN passes through to the body. Mirrors [ai.vishwakarma.labelling.security.SubjectAccessTest]'s
 * habit of building real authorities rather than mocking the decision.
 *
 * The **markup** half renders the per-row checkbox expression through the real Thymeleaf engine
 * (the [Stage4ReviewPanelsTest] idiom — SpringTemplateEngine over the verbatim expression, never a
 * bare OGNL string), pinning the contract that a checkbox appears only on a FAIL/BORDERLINE row.
 */
class SftOverrideApproveTest {

    // ---- the ADMIN gate (server-side, 403 for non-admin) ------------------------------

    private val ctx =
        AnnotationConfigApplicationContext(OverrideApproveMethodSecurityConfig::class.java)
    private val controller = ctx.getBean(SftController::class.java)
    private val stage4 = ctx.getBean(Stage4Service::class.java)

    @AfterTest fun clearAuth() = SecurityContextHolder.clearContext()

    private fun authenticateAs(principal: String, role: Role) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                principal,
                "N/A",
                AuthorityUtils.createAuthorityList(role.authority),
            )
    }

    @Test
    fun `a non-admin operator is refused the override endpoint`() {
        // AUTHOR (class-level rung) and REVIEWER (approve/send-back rung) are both below ADMIN and
        // the role hierarchy never lifts them to it — each is denied before the body runs.
        for (role in listOf(Role.AUTHOR, Role.REVIEWER)) {
            authenticateAs("someone@x.com", role)
            assertFailsWith<AccessDeniedException>("$role must not reach the override endpoint") {
                controller.overrideApprove(
                    listOf("e1"),
                    null,
                    null,
                    false,
                    RedirectAttributesModelMap(),
                )
            }
        }
    }

    @Test
    fun `an admin passes the gate into the override op`() {
        `when`(stage4.overrideApprove(listOf("e1"), "admin@x.com"))
            .thenReturn(Stage4OverrideApproveOutcome(1, 0, 0, 0, 0))
        authenticateAs("admin@x.com", Role.ADMIN)

        controller.overrideApprove(listOf("e1"), null, null, false, RedirectAttributesModelMap())

        // The gate let it through: the body actually invoked the service op.
        verify(stage4).overrideApprove(listOf("e1"), "admin@x.com")
    }

    // ---- the review-list checkbox markup (only FAIL/BORDERLINE rows get a box) ----------

    /** Mirrors the `th:if` guard on the checkbox `<td>` in `templates/sft/list.html` verbatim. */
    private val rowSnippet =
        """
        <table><tbody>
        <tr th:each="e : ${'$'}{examples}">
          <td>
            <label th:if="${'$'}{e.judgeVerdict != null and (e.judgeVerdict.name() == 'FAIL' or e.judgeVerdict.name() == 'BORDERLINE')}">
              <input type="checkbox" name="ids" th:value="${'$'}{e.id}" data-override-checkbox>
            </label>
          </td>
          <td th:text="${'$'}{e.id}">id</td>
        </tr>
        </tbody></table>
        """
            .trimIndent()

    private fun render(examples: List<SftExample>): String {
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    StringTemplateResolver().apply { templateMode = TemplateMode.HTML }
                )
            }
        return engine.process(rowSnippet, Context().apply { setVariable("examples", examples) })
    }

    @Test
    fun `a checkbox renders only on FAIL and BORDERLINE rows`() {
        val html =
            render(
                listOf(
                    SftExample(id = "e-fail", judgeVerdict = JudgeVerdict.FAIL),
                    SftExample(id = "e-border", judgeVerdict = JudgeVerdict.BORDERLINE),
                    SftExample(id = "e-pass", judgeVerdict = JudgeVerdict.PASS),
                    SftExample(id = "e-unjudged", judgeVerdict = null),
                )
            )

        // `value="…"` only appears on the checkbox input (the id column uses th:text), so it is an
        // exact witness that the box rendered for that row.
        assertTrue(html.contains("""value="e-fail""""), "FAIL row lost its override checkbox")
        assertTrue(html.contains("""value="e-border""""), "BORDERLINE row lost its checkbox")
        assertFalse(
            html.contains("""value="e-pass""""),
            "a PASS row must not be override-selectable"
        )
        assertFalse(
            html.contains("""value="e-unjudged""""),
            "an unjudged row must not be selectable"
        )
    }
}

/**
 * A minimal method-security context: the real `@EnableMethodSecurity` proxy plus the production
 * role hierarchy, wrapping an [SftController] whose collaborators are all mocked. Only the
 * [Stage4Service] mock is exposed as its own bean so the admit-path test can stub and verify it.
 */
@TestConfiguration
@EnableMethodSecurity
open class OverrideApproveMethodSecurityConfig {

    @Bean
    open fun roleHierarchy(): RoleHierarchy =
        RoleHierarchyImpl.withDefaultRolePrefix()
            .role("ADMIN")
            .implies("REVIEWER")
            .role("REVIEWER")
            .implies("AUTHOR")
            .build()

    @Bean
    open fun methodSecurityExpressionHandler(
        roleHierarchy: RoleHierarchy
    ): DefaultMethodSecurityExpressionHandler =
        DefaultMethodSecurityExpressionHandler().apply { setRoleHierarchy(roleHierarchy) }

    @Bean open fun stage4Service(): Stage4Service = mock(Stage4Service::class.java)

    @Bean
    open fun sftController(stage4Service: Stage4Service): SftController =
        SftController(
            mock(SftService::class.java),
            mock(TaxonomyService::class.java),
            mock(CatalogService::class.java),
            mock(DraftingService::class.java),
            mock(ScenarioService::class.java),
            mock(SubjectService::class.java),
            mock(Stage4ReviewPanels::class.java),
            stage4Service,
        )
}

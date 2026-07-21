package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.security.SubjectHostFilter
import ai.vishwakarma.labelling.service.SubjectDirectory
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import org.mockito.Mockito.mock
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * VA-173 two-door round, end-to-end through the real host filter + controller (standalone MockMvc):
 * the apex root renders the two-door bifurcation view, and `/construct` the individuals front door.
 * Both are Thymeleaf views (not servlet forwards), so the rewritten `/p` request never re-enters
 * the dispatcher — the loop that once forced the raw static page to be streamed by hand cannot
 * recur (StackOverflowError found live at the earlier cutover, 2026-07-18).
 */
class PublicSiteControllerTest {

    private class NoSubjectsRepo : SubjectRepository(mock(Firestore::class.java)) {
        override fun findByHandle(handle: String): Subject? = null
    }

    // Prod shape: apex ≠ operator.
    private val props =
        AppProperties(
            product =
                AppProperties.Product(
                    baseDomain = "vishwakarma.ai",
                    operatorDomain = "labelling.vishwakarma.ai",
                )
        )

    private val mvc =
        MockMvcBuilders.standaloneSetup(PublicSiteController())
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
                SubjectHostFilter(props, SubjectDirectory(NoSubjectsRepo()))
            )
            .build()

    @Test
    fun `apex root renders the two-door bifurcation view`() {
        mvc.perform(
                get("/").with {
                    it.serverName = "vishwakarma.ai"
                    it
                }
            )
            .andExpect(status().isOk)
            .andExpect(view().name("public/root"))
    }

    @Test
    fun `apex construct renders the product front door view`() {
        mvc.perform(
                get("/construct").with {
                    it.serverName = "vishwakarma.ai"
                    it
                }
            )
            .andExpect(status().isOk)
            .andExpect(view().name("public/landing"))
    }

    @Test
    fun `internal prefix is not reachable off the apex host`() {
        mvc.perform(
                get("/p").with {
                    it.serverName = "labelling.vishwakarma.ai"
                    it
                }
            )
            .andExpect(status().isNotFound)
    }
}

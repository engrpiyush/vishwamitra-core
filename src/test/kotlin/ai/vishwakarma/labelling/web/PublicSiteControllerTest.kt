package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.security.SubjectHostFilter
import ai.vishwakarma.labelling.service.SubjectDirectory
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.mockito.Mockito.mock
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The matrix-landing round, end-to-end through the real host filter + controller + message writing
 * (standalone MockMvc): the apex serves the brand page BYTES on the first dispatch — no servlet
 * forward exists on this path (a forward loops on top of the filter's rewritten request;
 * StackOverflowError found live at cutover, 2026-07-18).
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
    fun `apex root serves the brand page body directly`() {
        mvc.perform(
                get("/").with {
                    it.serverName = "vishwakarma.ai"
                    it
                }
            )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(containsString("The Resume is Dead")))
            .andExpect(content().string(containsString("/construct")))
            .andExpect(content().string(containsString("/policies/terms")))
            .andExpect(content().string(containsString("/user-guide/")))
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

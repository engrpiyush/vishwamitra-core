package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.StageConfigDoc
import ai.vishwakarma.labelling.domain.StageKey
import ai.vishwakarma.labelling.persistence.StageConfigRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * VA-106: the closed-value-set (`ConfigField.options`) enforcement added for `judgeMode`. A dial
 * that selects which subsystem runs must not be typo'able into silently meaning its default — the
 * failure mode the older enum-ish STRING fields (`matchingMode`) still carry.
 */
class StageConfigOptionsTest {

    private fun service(): Pair<StageConfigService, StageConfigRepository> {
        val repo = mock(StageConfigRepository::class.java)
        `when`(repo.find(anyString())).thenAnswer {
            StageConfigDoc(stage = it.arguments[0] as String)
        }
        return StageConfigService(AppProperties(), repo) to repo
    }

    @Test
    fun `judgeMode accepts an allowed value and stores it as an override`() {
        val (svc, _) = service()
        val result = svc.update(StageKey.STAGE3, mapOf("judgeMode" to "GATEKEEPER"), "op")
        assertTrue(result.isRight())
        assertEquals(
            "GATEKEEPER",
            result.getOrNull()!!.overrides["judgeMode"],
        )
    }

    @Test
    fun `judgeMode rejects a value outside its option set`() {
        val (svc, _) = service()
        val result = svc.update(StageKey.STAGE3, mapOf("judgeMode" to "NONSENSE"), "op")
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error!!.message.contains("Judge mode"))
        assertTrue(error.message.contains("LLM | GATEKEEPER | SHADOW"))
    }

    @Test
    fun `the default value is not stored as an override`() {
        val (svc, _) = service()
        // LLM is the bootstrap default — typing it back removes rather than stores the override.
        val result = svc.update(StageKey.STAGE3, mapOf("judgeMode" to "LLM"), "op")
        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.overrides.isEmpty())
    }

    @Test
    fun `the judgeMode field surfaces its options for the dropdown`() {
        val (svc, _) = service()
        val field =
            svc.fieldViews(StageKey.STAGE3).map { it.field }.single { it.name == "judgeMode" }
        assertEquals(listOf("LLM", "GATEKEEPER", "SHADOW"), field.options)
    }
}

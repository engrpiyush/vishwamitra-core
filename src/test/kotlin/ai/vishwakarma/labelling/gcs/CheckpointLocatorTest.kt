package ai.vishwakarma.labelling.gcs

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.gcs.CheckpointLocator.DirInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckpointLocatorTest {

    private val locator = CheckpointLocator(AppProperties())

    private fun weights(t: Long = 1L) = DirInfo(hasWeights = true, latestUpdateMillis = t)

    private fun noWeights(t: Long = 1L) = DirInfo(hasWeights = false, latestUpdateMillis = t)

    @Test
    fun `picks the Gemma nested final dir`() {
        val dirs =
            mapOf(
                "tuned/gemma3-27b-v1.0/postprocess/node-0/checkpoints/final/" to weights(),
                "tuned/gemma3-27b-v1.0/" to noWeights(),
            )
        assertEquals(
            "tuned/gemma3-27b-v1.0/postprocess/node-0/checkpoints/final/",
            locator.select(dirs),
        )
    }

    @Test
    fun `picks a final_output dir by keyword`() {
        val dirs = mapOf("tuned/qwen3-32b-v1.0/final_output/" to weights())
        assertEquals("tuned/qwen3-32b-v1.0/final_output/", locator.select(dirs))
    }

    @Test
    fun `prefers node-0 when final exists on multiple nodes`() {
        val dirs =
            mapOf(
                "run/postprocess/node-0/checkpoints/final/" to weights(1L),
                "run/postprocess/node-1/checkpoints/final/" to weights(9L),
            )
        assertEquals("run/postprocess/node-0/checkpoints/final/", locator.select(dirs))
    }

    @Test
    fun `final wins over an intermediate checkpoint`() {
        val dirs =
            mapOf(
                "run/checkpoints/checkpoint-500/" to weights(9L),
                "run/checkpoints/final/" to weights(1L),
            )
        assertEquals("run/checkpoints/final/", locator.select(dirs))
    }

    @Test
    fun `falls back to most recent weights dir when no final`() {
        val dirs =
            mapOf(
                "run/checkpoints/checkpoint-100/" to weights(1L),
                "run/checkpoints/checkpoint-500/" to weights(9L),
            )
        assertEquals("run/checkpoints/checkpoint-500/", locator.select(dirs))
    }

    @Test
    fun `ignores dirs without weight files`() {
        val dirs = mapOf("run/config/" to noWeights())
        assertNull(locator.select(dirs))
    }

    @Test
    fun `empty input is null`() {
        assertNull(locator.select(emptyMap()))
    }
}

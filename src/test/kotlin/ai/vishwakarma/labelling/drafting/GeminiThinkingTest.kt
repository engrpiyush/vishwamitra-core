package ai.vishwakarma.labelling.drafting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** VA-76: the budget→knob mapping per model generation and pin directive. */
class GeminiThinkingTest {

    @Test
    fun `derive sends the budget knob to 2_5-era models`() {
        assertEquals(
            mapOf<String, Any>("thinkingBudget" to 4096),
            GeminiThinking.config(null, "gemini-2.5-pro", 4096),
        )
        assertNull(GeminiThinking.config("", "gemini-2.5-pro", null))
    }

    @Test
    fun `derive maps the budget to a level on 3_x models`() {
        assertEquals(
            mapOf<String, Any>("thinkingLevel" to "high"),
            GeminiThinking.config(null, "gemini-3.5-flash", 4096),
        )
        assertEquals(
            mapOf<String, Any>("thinkingLevel" to "high"),
            GeminiThinking.config(null, "gemini-3.5-flash", 8192),
        )
        assertEquals(
            mapOf<String, Any>("thinkingLevel" to "low"),
            GeminiThinking.config(null, "gemini-3.1-pro-preview", 0),
        )
        assertNull(GeminiThinking.config(null, "gemini-3.5-flash", null))
    }

    @Test
    fun `derive keeps output-protection caps on low for 3_x models`() {
        // A small 2.5-era budget is a cap guarding output space in the shared maxOutputTokens
        // pool — `high` would ignore it and starve the output (the 2026-07-21 Stage 4 clip).
        for (cap in listOf(512, 1024, 2048)) {
            assertEquals(
                mapOf<String, Any>("thinkingLevel" to "low"),
                GeminiThinking.config(null, "gemini-3.5-flash", cap),
                "budget $cap must derive to low",
            )
        }
    }

    @Test
    fun `budget directive forces the 2_5 knob regardless of model`() {
        assertEquals(
            mapOf<String, Any>("thinkingBudget" to 512),
            GeminiThinking.config("budget", "gemini-3.5-flash", 512),
        )
        assertNull(GeminiThinking.config("budget", "gemini-3.5-flash", null))
    }

    @Test
    fun `level directive pins the level and ignores the caller budget`() {
        assertEquals(
            mapOf<String, Any>("thinkingLevel" to "low"),
            GeminiThinking.config("level:low", "gemini-2.5-pro", 4096),
        )
        // The pin owns the level even when the caller sent no budget at all.
        assertEquals(
            mapOf<String, Any>("thinkingLevel" to "high"),
            GeminiThinking.config("level:high", "gemini-3.5-flash", null),
        )
        // A malformed empty level falls back to omitting thinkingConfig.
        assertNull(GeminiThinking.config("level:", "gemini-3.5-flash", 1024))
    }
}

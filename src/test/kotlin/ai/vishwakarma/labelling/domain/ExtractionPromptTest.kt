package ai.vishwakarma.labelling.domain

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the circulated code defaults in `extraction-prompts.yaml`: the file is the built-in
 * fallback for every content type, so a missing or blank entry silently degrades extraction.
 */
class ExtractionPromptTest {

    @Test
    fun `every content type has a non-blank code default`() {
        val missing = ContentType.entries.filter { ExtractionPrompt.builtinFor(it).isBlank() }

        assertTrue(missing.isEmpty(), "extraction-prompts.yaml lacks defaults for: $missing")
    }

    @Test
    fun `demonstration content defaults keep the inferred-claim confidence calibration`() {
        val demonstration =
            setOf(
                ContentType.SKILL_DEMO,
                ContentType.WORK_SAMPLE_PORTFOLIO,
                ContentType.ACCOMPLISHMENT_STORY,
                ContentType.DEMO_PITCH,
                ContentType.TEACHING_SESSION,
                ContentType.SPEECH_TALK,
            )
        val uncalibrated =
            demonstration.filterNot { ExtractionPrompt.builtinFor(it).contains("0.5–0.7") }

        assertTrue(
            uncalibrated.isEmpty(),
            "Demonstration defaults missing the 0.5–0.7 inferred-confidence cap: $uncalibrated",
        )
    }
}

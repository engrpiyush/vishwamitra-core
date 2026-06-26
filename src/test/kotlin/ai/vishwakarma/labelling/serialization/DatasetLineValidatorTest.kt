package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ExportKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatasetLineValidatorTest {

    private val validator = DatasetLineValidator(AppProperties())

    // cap deliberately tiny to exercise the token guard
    private val tinyCap =
        DatasetLineValidator(AppProperties(tuning = AppProperties.Tuning(maxTokensPerExample = 1)))

    @Test
    fun `valid SFT line has no errors`() {
        val line =
            """{"contents":[{"role":"user","parts":[{"text":"hi"}]},{"role":"model","parts":[{"text":"hello"}]}]}"""
        assertEquals(emptyList(), validator.validate(line, ExportKind.SFT))
    }

    @Test
    fun `valid DPO line has no errors`() {
        val line =
            """{"contents":[{"role":"user","parts":[{"text":"hi"}]}],"chosen":{"role":"model","parts":[{"text":"good"}]},"rejected":{"role":"model","parts":[{"text":"bad"}]}}"""
        assertEquals(emptyList(), validator.validate(line, ExportKind.DPO))
    }

    @Test
    fun `malformed JSON is reported with the line number`() {
        val content =
            """{"contents":[{"role":"user","parts":[{"text":"ok"}]},{"role":"model","parts":[{"text":"y"}]}]}
not json at all"""
        val errors = validator.validate(content, ExportKind.SFT)
        assertTrue(errors.any { it.line == 2 && it.message.contains("Invalid JSON") })
    }

    @Test
    fun `structured tool parts are rejected`() {
        val line =
            """{"contents":[{"role":"user","parts":[{"text":"hi"}]},{"role":"model","parts":[{"functionCall":{"name":"x"}}]}]}"""
        val errors = validator.validate(line, ExportKind.SFT)
        assertTrue(errors.any { it.message.contains("structured tool parts") })
    }

    @Test
    fun `DPO missing chosen is reported`() {
        val line =
            """{"contents":[{"role":"user","parts":[{"text":"hi"}]}],"rejected":{"role":"model","parts":[{"text":"bad"}]}}"""
        val errors = validator.validate(line, ExportKind.DPO)
        assertTrue(errors.any { it.message.contains("missing top-level 'chosen'") })
    }

    @Test
    fun `oversized line trips the token guard`() {
        val line =
            """{"contents":[{"role":"user","parts":[{"text":"hi"}]},{"role":"model","parts":[{"text":"hello"}]}]}"""
        val errors = tinyCap.validate(line, ExportKind.SFT)
        assertTrue(errors.any { it.message.contains("exceeds") })
    }

    @Test
    fun `empty file is a file-level error`() {
        val errors = validator.validate("   \n  ", ExportKind.SFT)
        assertEquals(1, errors.size)
        assertEquals(0, errors.first().line)
    }
}

package ai.vishwakarma.labelling.config

import kotlin.test.Test
import kotlin.test.assertEquals

/** [AppProperties.Stage4]: LLD §16 defaults + mix-weight normalization (VA-49). */
class Stage4PropertiesTest {

    @Test
    fun `defaults match the LLD §16 table`() {
        val stage4 = AppProperties().stage4

        // QD-4 (2026-07-13): situational-led rebalance, QA anchor retained.
        assertEquals(0.25, stage4.mix.qa)
        assertEquals(0.35, stage4.mix.situational)
        assertEquals(0.25, stage4.mix.multiClaim)
        assertEquals(0.10, stage4.mix.negative)
        assertEquals(0.05, stage4.mix.meta)
        assertEquals(6, stage4.maxConversationsPerClaim)
        assertEquals(0.85, stage4.dedupeJaccardThreshold)
        assertEquals(true, stage4.judgeEnabled)
        // VA-164 kb-generation ships dark — default off keeps every prompt/planId byte-for-byte.
        assertEquals(false, stage4.kbGeneration)
        assertEquals(400, stage4.kbMaxClaims)
        assertEquals(1.0, stage4.reviewSampleRate)
        assertEquals(false, stage4.dpoEnabled)
        assertEquals(0.10, stage4.evalHoldoutFraction)
        assertEquals(0.90, stage4.evalBehaviorBar)
    }

    @Test
    fun `normalized rescales fluid dials to sum 1`() {
        val mix =
            AppProperties.Stage4.Mix(
                qa = 2.0,
                situational = 1.0,
                multiClaim = 1.0,
                negative = 0.0,
                meta = 0.0
            )

        val normalized = mix.normalized()

        assertEquals(0.5, normalized.qa)
        assertEquals(0.25, normalized.situational)
        assertEquals(0.25, normalized.multiClaim)
        assertEquals(0.0, normalized.negative)
        assertEquals(0.0, normalized.meta)
    }

    @Test
    fun `default mix is already normalized`() {
        val normalized = AppProperties.Stage4.Mix().normalized()

        assertEquals(AppProperties.Stage4.Mix(), normalized)
    }

    @Test
    fun `an all-zero mix falls back to the defaults`() {
        val mix = AppProperties.Stage4.Mix(0.0, 0.0, 0.0, 0.0, 0.0)

        assertEquals(AppProperties.Stage4.Mix(), mix.normalized())
    }
}

package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.BaseKind
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.TuningMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersioningTest {

    @Test
    fun `first foundation is v1_0`() {
        assertEquals("v1.0", Versioning.nextVersion(emptyList(), BaseKind.FOUNDATION, null))
    }

    @Test
    fun `second foundation bumps the major`() {
        assertEquals("v2.0", Versioning.nextVersion(listOf("v1.0", "v1.1"), BaseKind.FOUNDATION, null))
    }

    @Test
    fun `continuation bumps the minor within the parent major`() {
        assertEquals("v1.1", Versioning.nextVersion(listOf("v1.0"), BaseKind.CONTINUATION, "v1.0"))
        assertEquals("v1.2", Versioning.nextVersion(listOf("v1.0", "v1.1", "v2.0"), BaseKind.CONTINUATION, "v1.1"))
        assertEquals("v2.1", Versioning.nextVersion(listOf("v1.0", "v2.0"), BaseKind.CONTINUATION, "v2.0"))
    }

    @Test
    fun `serve command embeds checkpoint and served name`() {
        val v = ModelVersion(
            id = "1", baseModelId = "b", family = "qwen3-32b", version = "v1.1",
            method = TuningMethod.SFT, baseKind = BaseKind.CONTINUATION,
            gcsCheckpointUri = "gs://serving/tuned/qwen3-32b-v1.1/custom-trained/2026/",
        )
        val cmd = ServeCommand.build(v)
        assertTrue(cmd.contains("gs://serving/tuned/qwen3-32b-v1.1/custom-trained/2026/"))
        assertTrue(cmd.contains("qwen3-32b-v1.1"))
        assertTrue(cmd.contains("vllm serve"))
    }
}

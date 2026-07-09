package ai.vishwakarma.labelling.stage3

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingServiceTest {

    // ---- re-normalization (VA-7: truncated MRL outputs must be unit-norm) --------

    @Test
    fun `renormalize scales to unit L2 norm`() {
        val v = renormalize(listOf(3.0, 4.0))
        assertEquals(0.6, v[0], 1e-9)
        assertEquals(0.8, v[1], 1e-9)
        assertEquals(1.0, norm(v), 1e-9)
    }

    @Test
    fun `renormalize leaves the zero vector untouched`() {
        assertEquals(listOf(0.0, 0.0), renormalize(listOf(0.0, 0.0)))
    }

    // ---- pseudo embeddings (LLD §11.12: deterministic, similar text ⇒ similar vector) ----

    private val pseudo = PseudoEmbeddingService(64)

    @Test
    fun `pseudo embeddings are deterministic and unit-norm at the configured dims`() {
        val a =
            pseudo.embed(
                "Led the payments migration in 2019",
                EmbeddingTaskType.SEMANTIC_SIMILARITY
            )
        val b =
            pseudo.embed(
                "Led the payments migration in 2019",
                EmbeddingTaskType.SEMANTIC_SIMILARITY
            )
        assertEquals(a, b)
        assertEquals(64, a.size)
        assertEquals(1.0, norm(a), 1e-9)
    }

    @Test
    fun `similar text lands closer than unrelated text`() {
        val base =
            pseudo.embed(
                "led the payments migration in 2019",
                EmbeddingTaskType.SEMANTIC_SIMILARITY
            )
        val paraphrase =
            pseudo.embed(
                "led the payments migration during 2019",
                EmbeddingTaskType.SEMANTIC_SIMILARITY
            )
        val unrelated =
            pseudo.embed("enjoys watercolor painting", EmbeddingTaskType.SEMANTIC_SIMILARITY)
        assertTrue(cosine(base, paraphrase) > cosine(base, unrelated))
    }

    @Test
    fun `pseudo version stamp differs from the real one so dry-run flips re-embed`() {
        assertEquals("pseudo:64", pseudo.versionStamp)
    }

    // ---- §11-4 embedded-text composition -------------------------------------------

    @Test
    fun `embedding text composes type prefix and date suffix`() {
        assertEquals(
            "SKILL: knows Kotlin (2024-01-15)",
            ClaimToEmbed("c1", "SKILL", "knows Kotlin", "2024-01-15").embeddingText(),
        )
    }

    @Test
    fun `embedding text omits missing parts`() {
        assertEquals("knows Kotlin", ClaimToEmbed("c1", null, "knows Kotlin", null).embeddingText())
        assertEquals(
            "SKILL: knows Kotlin",
            ClaimToEmbed("c1", "SKILL", "knows Kotlin", null).embeddingText(),
        )
    }

    // ---- helpers ------------------------------------------------------------------

    private fun norm(v: List<Double>): Double = sqrt(v.sumOf { it * it })

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        val dot = a.indices.sumOf { a[it] * b[it] }
        val d = norm(a) * norm(b)
        return if (abs(d) < 1e-12) 0.0 else dot / d
    }
}

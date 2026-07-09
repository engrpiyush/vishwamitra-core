package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.neo4j.driver.Driver

/**
 * VA-11 unit layer: the §11.3 decision pipeline (normalize → exact/alias → kNN bands → mint)
 * against an in-memory canon. Vectors are hand-scripted 2-dim unit vectors so kNN cosines land
 * exactly on the band edges under test; the graph fake computes real dot products.
 */
private class ScriptedEmbeddings(private val vectors: Map<String, List<Double>> = emptyMap()) :
    EmbeddingService {
    override val versionStamp = "scripted:2"
    override val dimensions = 2
    var calls = 0

    override fun embed(text: String, taskType: EmbeddingTaskType): List<Double> {
        calls++
        return vectors[text] ?: listOf(0.0, 1.0)
    }
}

private class FakeEntityGraph(props: AppProperties) :
    Stage3GraphRepository(mock(Driver::class.java), props) {

    data class Stored(
        val ref: EntityRef,
        val embedding: List<Double>,
        val aliasKeys: MutableSet<String> = mutableSetOf(),
    )

    val store = mutableListOf<Stored>()
    val writes = mutableListOf<EntityResolutionWrite>()
    val compressions = mutableListOf<Pair<List<String>, String>>()

    fun seed(
        id: String,
        type: String,
        key: String,
        name: String,
        embedding: List<Double> = listOf(0.0, 1.0),
        mergedInto: String? = null,
        aliasKeys: Set<String> = emptySet(),
    ) {
        store +=
            Stored(EntityRef(id, type, key, name, mergedInto), embedding, aliasKeys.toMutableSet())
    }

    override fun findEntityByKey(entityType: String, canonicalKey: String): EntityRef? =
        store
            .filter { it.ref.entityType == entityType }
            .sortedBy { if (it.ref.canonicalKey == canonicalKey) 0 else 1 }
            .firstOrNull { it.ref.canonicalKey == canonicalKey || canonicalKey in it.aliasKeys }
            ?.ref

    override fun findEntityById(entityId: String): EntityRef? =
        store.firstOrNull { it.ref.entityId == entityId }?.ref

    override fun entityKnn(
        entityType: String,
        embedding: List<Double>,
        k: Int,
    ): List<EntityCandidate> =
        store
            .filter { it.ref.entityType == entityType && it.ref.mergedInto == null }
            .map {
                EntityCandidate(
                    entityId = it.ref.entityId,
                    entityType = it.ref.entityType,
                    canonicalKey = it.ref.canonicalKey,
                    canonicalName = it.ref.canonicalName,
                    score = it.embedding.zip(embedding).sumOf { (a, b) -> a * b },
                )
            }
            .sortedByDescending { it.score }
            .take(k)

    override fun compressRedirects(entityIds: List<String>, targetEntityId: String) {
        compressions += entityIds to targetEntityId
        entityIds.forEach { id ->
            val idx = store.indexOfFirst { it.ref.entityId == id }
            if (idx >= 0)
                store[idx] = store[idx].copy(ref = store[idx].ref.copy(mergedInto = targetEntityId))
        }
    }

    override fun applyEntityResolution(subjectId: String, write: EntityResolutionWrite) {
        writes += write
        write.mints.forEach { mint ->
            store +=
                Stored(
                    EntityRef(
                        mint.entityId,
                        mint.entityType,
                        mint.canonicalKey,
                        mint.canonicalName,
                        null
                    ),
                    mint.embedding,
                )
        }
        write.aliasAppends.forEach { row ->
            store
                .firstOrNull {
                    it.ref.entityType == row.entityType && it.ref.canonicalKey == row.canonicalKey
                }
                ?.aliasKeys
                ?.add(row.aliasKey)
        }
    }
}

class EntityResolverTest {

    private val props = AppProperties()
    private val graph = FakeEntityGraph(props)

    private fun resolver(embeddings: EmbeddingService = ScriptedEmbeddings()) =
        EntityResolver(graph, embeddings, props)

    private fun claim(
        id: String,
        text: String = "some claim text",
        sourceClass: String? = "SELF",
        assetId: String? = "a1",
    ) =
        ClaimToResolve(
            claimId = id,
            type = "SKILL",
            text = text,
            sourceClass = sourceClass,
            assetId = assetId
        )

    private fun mentions(vararg pairs: Pair<String, EntityType>, issuer: String? = null) =
        ExtractedMentions(pairs.map { ExtractedMention(it.first, it.second) }, issuer)

    // ---- normalization ------------------------------------------------------------

    @Test
    fun `normalization folds case punctuation and diacritics but keeps identity symbols`() {
        assertEquals("r mehta", normalizeSurface("R. Mehta"))
        assertEquals("be", normalizeSurface("B.E."))
        assertEquals("obrien", normalizeSurface("O'Brien"))
        assertEquals("full stack", normalizeSurface("full-stack"))
        assertEquals("jose alvarez", normalizeSurface("  José \t Álvarez "))
        assertEquals("kotlin", normalizeSurface("(Kotlin)"))
        // The over-merge the LLD's blunt "strip punctuation" would cause (§15 #8):
        assertEquals("c++", normalizeSurface("C++"))
        assertEquals("c#", normalizeSurface("C#"))
        assertEquals("ci/cd", normalizeSurface("CI/CD"))
    }

    // ---- exact / alias / redirect --------------------------------------------------

    @Test
    fun `exact canonicalKey match links at full confidence without touching embeddings`() {
        graph.seed("e1", "SKILL", "kotlin", "Kotlin")
        val embeddings = ScriptedEmbeddings()
        val outcome =
            resolver(embeddings)
                .resolve(
                    "s1",
                    listOf(claim("c1")),
                    mapOf("c1" to mentions("Kotlin," to EntityType.SKILL)),
                    "st"
                )
        assertEquals(1, outcome.linked)
        assertEquals(0, outcome.minted)
        assertEquals(0, embeddings.calls)
        val link = graph.writes.single().links.single()
        assertEquals("kotlin", link.canonicalKey)
        assertEquals("EXACT", link.method)
        assertEquals(1.0, link.confidence)
        assertEquals(false, link.provisional)
    }

    @Test
    fun `adopted alias keys hit the exact leg and tombstones redirect to the live entity`() {
        graph.seed("e-old", "SKILL", "js", "JS", mergedInto = "e-live")
        graph.seed("e-live", "SKILL", "javascript", "JavaScript")
        graph.seed("e-alias", "ORG", "goog", "Google LLC", aliasKeys = setOf("google"))
        val outcome =
            resolver()
                .resolve(
                    "s1",
                    listOf(claim("c1")),
                    mapOf(
                        "c1" to
                            mentions(
                                "JS" to EntityType.SKILL,
                                "Google" to EntityType.ORG,
                            )
                    ),
                    "st",
                )
        assertEquals(2, outcome.linked)
        val byType = graph.writes.single().links.associateBy { it.entityType }
        // The tombstone's key resolved through mergedInto to the live entity's key.
        assertEquals("javascript", byType["SKILL"]!!.canonicalKey)
        // The alias key resolved to the aliased entity.
        assertEquals("goog", byType["ORG"]!!.canonicalKey)
    }

    @Test
    fun `redirect chains resolve transitively and compress on read (VA-12)`() {
        graph.seed("e1", "SKILL", "js", "JS", mergedInto = "e2")
        graph.seed("e2", "SKILL", "java script", "Java Script", mergedInto = "e3")
        graph.seed("e3", "SKILL", "javascript", "JavaScript")
        val outcome =
            resolver()
                .resolve(
                    "s1",
                    listOf(claim("c1")),
                    mapOf("c1" to mentions("JS" to EntityType.SKILL)),
                    "st",
                )
        // Followed a→b→c to the live target…
        assertEquals(1, outcome.linked)
        assertEquals("javascript", graph.writes.single().links.single().canonicalKey)
        // …and compressed the traversed chain: e1 now points straight at e3 (e2 already did).
        assertEquals(listOf(listOf("e1") to "e3"), graph.compressions)
        assertEquals("e3", graph.store.first { it.ref.entityId == "e1" }.ref.mergedInto)
        // A second read walks the compressed edge and has nothing left to compress.
        graph.writes.clear()
        graph.compressions.clear()
        resolver()
            .resolve(
                "s1",
                listOf(claim("c2")),
                mapOf("c2" to mentions("JS" to EntityType.SKILL)),
                "st",
            )
        assertEquals("javascript", graph.writes.single().links.single().canonicalKey)
        assertTrue(graph.compressions.isEmpty())
    }

    // ---- kNN bands -----------------------------------------------------------------

    @Test
    fun `kNN cosine decides merge, review band, or mint (LLD §11-3 thresholds)`() {
        graph.seed("e1", "SKILL", "kubernetes", "Kubernetes", embedding = listOf(1.0, 0.0))
        val embeddings =
            ScriptedEmbeddings(
                mapOf(
                    "K8s" to listOf(0.86, 0.51),
                    "Kube" to listOf(0.80, 0.60),
                    "Terraform" to listOf(0.20, 0.98),
                )
            )
        val outcome =
            resolver(embeddings)
                .resolve(
                    "s1",
                    listOf(claim("c1")),
                    mapOf(
                        "c1" to
                            mentions(
                                "K8s" to EntityType.SKILL,
                                "Kube" to EntityType.SKILL,
                                "Terraform" to EntityType.SKILL,
                            )
                    ),
                    "st",
                )
        assertEquals(1, outcome.linked)
        assertEquals(1, outcome.reviewListed)
        assertEquals(1, outcome.minted)

        val write = graph.writes.single()
        val bySurface = write.links.associateBy { it.surface }
        // ≥ 0.85: merged into the existing entity and its surface adopted as an alias.
        assertEquals("kubernetes", bySurface["K8s"]!!.canonicalKey)
        assertEquals(false, bySurface["K8s"]!!.provisional)
        assertEquals("EMBED", bySurface["K8s"]!!.method)
        assertEquals(listOf("k8s"), write.aliasAppends.map { it.aliasKey })
        // 0.75–0.85: provisionally linked, review-listed, NOT adopted as an alias.
        assertEquals("kubernetes", bySurface["Kube"]!!.canonicalKey)
        assertEquals(true, bySurface["Kube"]!!.provisional)
        // Below the band: minted fresh, linked at 1.0.
        assertEquals("terraform", bySurface["Terraform"]!!.canonicalKey)
        assertEquals("MINT", bySurface["Terraform"]!!.method)
        assertEquals("terraform", write.mints.single().canonicalKey)
    }

    @Test
    fun `resolution is type-scoped — the same surface mints separately per type`() {
        graph.seed("e1", "SKILL", "java", "Java", embedding = listOf(1.0, 0.0))
        // Identical vector, different type: the SKILL entity must be invisible to a PLACE lookup.
        val embeddings = ScriptedEmbeddings(mapOf("Java" to listOf(1.0, 0.0)))
        val outcome =
            resolver(embeddings)
                .resolve(
                    "s1",
                    listOf(claim("c1")),
                    mapOf("c1" to mentions("Java" to EntityType.PLACE)),
                    "st",
                )
        assertEquals(1, outcome.minted)
        val mint = graph.writes.single().mints.single()
        assertEquals("PLACE", mint.entityType)
        assertEquals("java", mint.canonicalKey)
    }

    @Test
    fun `a new surface repeated across the batch mints once and links every claim`() {
        val outcome =
            resolver()
                .resolve(
                    "s1",
                    listOf(claim("c1"), claim("c2")),
                    mapOf(
                        "c1" to mentions("Neo4j" to EntityType.SKILL),
                        "c2" to mentions("Neo4j" to EntityType.SKILL),
                    ),
                    "st",
                )
        assertEquals(1, outcome.minted)
        assertEquals(1, outcome.linked) // the second claim links to the batch-local mint
        val write = graph.writes.single()
        assertEquals(1, write.mints.size)
        assertEquals(setOf("c1", "c2"), write.links.map { it.claimId }.toSet())
        assertTrue(write.links.all { it.canonicalKey == "neo4j" })
    }

    // ---- issuer derivation (§18.2 Q2) ------------------------------------------------

    @Test
    fun `a documentary claim with a resolved issuer mention stamps its source`() {
        val outcome =
            resolver()
                .resolve(
                    "s1",
                    listOf(
                        claim("c1", sourceClass = "DOCUMENTARY", assetId = "a9"),
                        claim("c2", sourceClass = "SELF"),
                    ),
                    mapOf(
                        "c1" to
                            mentions(
                                "Coursera" to EntityType.INSTITUTION,
                                "Machine Learning" to EntityType.SKILL,
                                issuer = "Coursera",
                            ),
                        // SELF claims never stamp an issuer, even if the model emits one.
                        "c2" to mentions("Google" to EntityType.ORG, issuer = "Google"),
                    ),
                    "st",
                )
        assertEquals(2, outcome.claimsResolved)
        val issuers = graph.writes.single().sourceIssuers
        assertEquals(listOf(SourceIssuerRow("a9", "INSTITUTION|coursera")), issuers)
    }

    @Test
    fun `an issuer surface that never resolved as a mention is dropped, not trusted`() {
        resolver()
            .resolve(
                "s1",
                listOf(claim("c1", sourceClass = "DOCUMENTARY", assetId = "a9")),
                mapOf(
                    // Issuer names something absent from mentions (contract violation) and a
                    // SKILL mention shares no key with it — nothing may be stamped.
                    "c1" to mentions("Kotlin" to EntityType.SKILL, issuer = "Coursera")
                ),
                "st",
            )
        assertTrue(graph.writes.single().sourceIssuers.isEmpty())
    }

    // ---- hygiene ---------------------------------------------------------------------

    @Test
    fun `every batch claim is stamped even when extraction found nothing`() {
        resolver().resolve("s1", listOf(claim("c1"), claim("c2")), emptyMap(), "stamp-1")
        val write = graph.writes.single()
        assertEquals(listOf("c1", "c2"), write.claimIds)
        assertEquals("stamp-1", write.stamp)
        assertTrue(write.links.isEmpty())
    }

    @Test
    fun `mentions that normalize to nothing are dropped and duplicates collapse`() {
        val outcome =
            resolver()
                .resolve(
                    "s1",
                    listOf(claim("c1")),
                    mapOf(
                        "c1" to
                            mentions(
                                "!!!" to EntityType.SKILL, // normalizes to empty → dropped
                                "Kotlin" to EntityType.SKILL,
                                "kotlin." to EntityType.SKILL, // same key → collapsed
                            )
                    ),
                    "st",
                )
        assertEquals(1, outcome.minted)
        assertEquals(0, outcome.linked)
        assertEquals(1, graph.writes.single().links.size)
    }
}

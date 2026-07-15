package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.Stage3EntityJournalEntry
import ai.vishwakarma.labelling.persistence.Stage3EntityJournalRepository
import ai.vishwakarma.labelling.stage3.EmbeddingService
import ai.vishwakarma.labelling.stage3.EmbeddingTaskType
import ai.vishwakarma.labelling.stage3.EntityAdminRow
import ai.vishwakarma.labelling.stage3.EntityCandidate
import ai.vishwakarma.labelling.stage3.EntityMentionRow
import ai.vishwakarma.labelling.stage3.EntityMintRow
import ai.vishwakarma.labelling.stage3.EntityRef
import ai.vishwakarma.labelling.stage3.MentionLinkRow
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import ai.vishwakarma.labelling.stage3.normalizeSurface
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.neo4j.driver.Driver

private fun <A, B> Either<A, B>.valueOrNull(): B? = fold({ null }, { it })

private fun <A, B> Either<A, B>.errorOrNull(): A? = fold({ it }, { null })

/** In-memory canon + MENTIONS edges implementing exactly the VA-12 repo surface. */
private class AdminFakeGraph(props: AppProperties) :
    Stage3GraphRepository(mock(Driver::class.java), liveConfig(props)) {

    data class Edge(
        val claimId: String,
        val subjectId: String,
        val surface: String,
        val provisional: Boolean,
        var entityId: String,
    )

    val nodes = linkedMapOf<String, EntityAdminRow>()
    val embeddingsByEntity = mutableMapOf<String, List<Double>>()
    val edges = mutableListOf<Edge>()

    fun seed(row: EntityAdminRow, embedding: List<Double> = listOf(0.0, 1.0)) {
        nodes[row.entityId] = row
        embeddingsByEntity[row.entityId] = embedding
    }

    fun mention(
        claimId: String,
        subjectId: String,
        surface: String,
        entityId: String,
        provisional: Boolean = false,
    ) {
        edges += Edge(claimId, subjectId, surface, provisional, entityId)
    }

    override fun findEntityAdmin(entityId: String): EntityAdminRow? = nodes[entityId]

    override fun findEntityById(entityId: String): EntityRef? = nodes[entityId]?.toRef()

    override fun entityMentionRows(entityId: String): List<EntityMentionRow> =
        edges
            .filter { it.entityId == entityId }
            .map { EntityMentionRow(it.claimId, it.subjectId, it.surface, it.provisional) }
            .sortedWith(compareBy({ it.claimId }, { it.surface }))

    override fun rewireMentions(fromEntityId: String, intoEntityId: String): Long {
        val moving = edges.filter { it.entityId == fromEntityId }
        moving.forEach { edge ->
            val duplicate =
                edges.any {
                    it.entityId == intoEntityId &&
                        it.claimId == edge.claimId &&
                        it.surface == edge.surface
                }
            if (duplicate) edges.remove(edge) else edge.entityId = intoEntityId
        }
        return moving.size.toLong()
    }

    override fun finalizeEntityMerge(
        fromEntityId: String,
        intoEntityId: String,
        aliases: List<String>,
        aliasKeys: List<String>,
    ) {
        nodes[intoEntityId] =
            nodes.getValue(intoEntityId).copy(aliases = aliases, aliasKeys = aliasKeys)
        nodes[fromEntityId] = nodes.getValue(fromEntityId).copy(mergedInto = intoEntityId)
    }

    override fun findEntityByKeyExcluding(
        entityType: String,
        canonicalKey: String,
        excludeEntityId: String,
    ): EntityRef? =
        nodes.values
            .filter { it.entityType == entityType && it.entityId != excludeEntityId }
            .sortedBy { if (it.canonicalKey == canonicalKey) 0 else 1 }
            .firstOrNull { it.canonicalKey == canonicalKey || canonicalKey in it.aliasKeys }
            ?.toRef()

    override fun entityKnn(
        entityType: String,
        embedding: List<Double>,
        k: Int,
    ): List<EntityCandidate> =
        nodes.values
            .filter { it.entityType == entityType && it.mergedInto == null }
            .map { node ->
                val stored = embeddingsByEntity.getValue(node.entityId)
                EntityCandidate(
                    entityId = node.entityId,
                    entityType = node.entityType,
                    canonicalKey = node.canonicalKey,
                    canonicalName = node.canonicalName,
                    score = stored.zip(embedding).sumOf { (a, b) -> a * b },
                )
            }
            .sortedByDescending { it.score }
            .take(k)

    override fun applyEntitySplit(
        entityId: String,
        keptAliases: List<String>,
        keptAliasKeys: List<String>,
        mints: List<EntityMintRow>,
        moves: List<MentionLinkRow>,
    ) {
        mints.forEach { mint ->
            seed(
                EntityAdminRow(
                    entityId = mint.entityId,
                    entityType = mint.entityType,
                    canonicalKey = mint.canonicalKey,
                    canonicalName = mint.canonicalName,
                    mergedInto = null,
                    aliases = emptyList(),
                    aliasKeys = emptyList(),
                ),
                mint.embedding,
            )
        }
        moves.forEach { move ->
            val target =
                nodes.values.first {
                    it.entityType == move.entityType && it.canonicalKey == move.canonicalKey
                }
            edges
                .filter {
                    it.entityId == entityId &&
                        it.claimId == move.claimId &&
                        it.surface == move.surface
                }
                .forEach { it.entityId = target.entityId }
        }
        nodes[entityId] =
            nodes.getValue(entityId).copy(aliases = keptAliases, aliasKeys = keptAliasKeys)
    }
}

private class FakeJournal : Stage3EntityJournalRepository(mock(Firestore::class.java)) {
    val entries = mutableListOf<Stage3EntityJournalEntry>()

    override fun record(entry: Stage3EntityJournalEntry): String {
        entries += entry
        return "journal-${entries.size}"
    }
}

private class SurfaceEmbeddings(private val vectors: Map<String, List<Double>>) : EmbeddingService {
    override val versionStamp = "scripted:2"
    override val dimensions = 2

    override fun embed(text: String, taskType: EmbeddingTaskType): List<Double> =
        vectors[text] ?: listOf(0.0, 1.0)
}

class EntityAdminServiceTest {

    private val props = AppProperties()
    private val graph = AdminFakeGraph(props)
    private val journal = FakeJournal()

    private fun service(vectors: Map<String, List<Double>> = emptyMap()) =
        EntityAdminService(graph, SurfaceEmbeddings(vectors), journal, liveConfig(props))

    private fun entity(
        id: String,
        type: String = "ORG",
        key: String,
        name: String,
        mergedInto: String? = null,
        aliases: List<String> = emptyList(),
    ) =
        EntityAdminRow(
            entityId = id,
            entityType = type,
            canonicalKey = key,
            canonicalName = name,
            mergedInto = mergedInto,
            aliases = aliases,
            aliasKeys = aliases.map { normalizeSurface(it) },
        )

    // ---- merge -------------------------------------------------------------------

    @Test
    fun `merge rewires mentions, unions aliases, tombstones, and journals`() {
        graph.seed(entity("e-a", key = "acme corp", name = "Acme Corp", aliases = listOf("ACME")))
        graph.seed(entity("e-b", key = "acme corporation", name = "Acme Corporation"))
        graph.mention("c1", "s1", "Acme Corp", "e-a")
        graph.mention("c2", "s2", "ACME", "e-a")

        val outcome = service().merge("e-a", "e-b", "admin@test").valueOrNull()
        assertNotNull(outcome)
        assertEquals(2, outcome.mentionsRewired)
        assertEquals(listOf("s1", "s2"), outcome.affectedSubjectIds)
        // Every mention now points at the target.
        assertTrue(graph.edges.all { it.entityId == "e-b" })
        // Aliases unioned: the source's canonical name + aliases, deduped by key.
        val b = graph.nodes.getValue("e-b")
        assertEquals(listOf("Acme Corp", "ACME"), b.aliases)
        assertEquals(listOf("acme corp", "acme"), b.aliasKeys)
        // Tombstone redirect left behind.
        assertEquals("e-b", graph.nodes.getValue("e-a").mergedInto)
        // Journal row written.
        val entry = journal.entries.single()
        assertEquals("MERGE", entry.action)
        assertEquals("e-a", entry.fromEntityId)
        assertEquals("e-b", entry.intoEntityId)
        assertEquals(2, entry.mentionsRewired)
        assertEquals(listOf("s1", "s2"), entry.affectedSubjectIds)
        assertEquals("admin@test", entry.actor)
    }

    @Test
    fun `merge never adopts an alias that collides with the target's own key`() {
        graph.seed(
            entity(
                "e-a",
                key = "acme",
                name = "ACME",
                aliases = listOf("Acme Corporation"),
            )
        )
        graph.seed(entity("e-b", key = "acme corporation", name = "Acme Corporation"))

        val outcome = service().merge("e-a", "e-b", null).valueOrNull()
        assertNotNull(outcome)
        // "Acme Corporation" normalizes to the target's own canonical key — only "ACME" adopts.
        assertEquals(listOf("ACME"), outcome.aliasesAdded)
        assertEquals(listOf("acme"), graph.nodes.getValue("e-b").aliasKeys)
    }

    @Test
    fun `merge refuses tombstoned ends, cross-type merges, and self-merges`() {
        graph.seed(entity("e-dead", key = "old", name = "Old", mergedInto = "e-live"))
        graph.seed(entity("e-live", key = "live", name = "Live"))
        graph.seed(entity("e-skill", type = "SKILL", key = "kotlin", name = "Kotlin"))

        assertTrue(service().merge("e-dead", "e-live", null).errorOrNull() is DomainError.Conflict)
        assertTrue(service().merge("e-live", "e-dead", null).errorOrNull() is DomainError.Conflict)
        assertTrue(service().merge("e-live", "e-skill", null).errorOrNull() is DomainError.Conflict)
        assertTrue(service().merge("e-live", "e-live", null).errorOrNull() is DomainError.Invalid)
        assertTrue(service().merge("missing", "e-live", null).errorOrNull() is DomainError.NotFound)
        assertTrue(journal.entries.isEmpty(), "refused operations must not journal")
    }

    // ---- split -------------------------------------------------------------------

    @Test
    fun `split redistributes mentions across exact, kNN bands, and mint`() {
        // The over-merged entity: canon "java" that swallowed four other surfaces.
        graph.seed(
            entity(
                "e-x",
                type = "SKILL",
                key = "java",
                name = "Java",
                aliases = listOf("Java programming", "JVM"),
            ),
            embedding = listOf(1.0, 0.0),
        )
        // A live neighbour the kNN legs re-resolve toward.
        graph.seed(
            entity("e-y", type = "SKILL", key = "javascript", name = "JavaScript"),
            embedding = listOf(1.0, 0.0),
        )
        graph.mention("c1", "s1", "Java", "e-x") // canonical key → stays
        graph.mention("c2", "s1", "Java programming", "e-x") // kNN 1.0 ≥ 0.85 → e-y
        graph.mention("c3", "s2", "JVM", "e-x") // kNN 0.80 → provisional on e-y
        graph.mention("c4", "s2", "Java runtime", "e-x") // kNN 0.0 → mint
        graph.mention("c5", "s3", "JavaScript", "e-x") // exact key elsewhere → e-y

        val outcome =
            service(
                    vectors =
                        mapOf(
                            "Java programming" to listOf(1.0, 0.0),
                            "JVM" to listOf(0.8, 0.6),
                            "Java runtime" to listOf(0.0, 1.0),
                        )
                )
                .split("e-x", "admin@test")
                .valueOrNull()
        assertNotNull(outcome)
        assertEquals(1, outcome.mentionsKept)
        assertEquals(4, outcome.mentionsMoved)
        assertEquals(1, outcome.entitiesMinted)
        assertEquals(listOf("s1", "s2", "s3"), outcome.affectedSubjectIds)

        // The canonical-key mention stayed; everything else re-resolved off the entity.
        assertEquals(
            listOf("c1"),
            graph.edges.filter { it.entityId == "e-x" }.map { it.claimId },
        )
        // Confident and provisional kNN merges (and the exact hit) land on the neighbour.
        val yMentions = graph.edges.filter { it.entityId == "e-y" }.map { it.claimId }.sorted()
        assertEquals(listOf("c2", "c3", "c5"), yMentions)
        // The unmatched surface minted its own entity.
        val minted = graph.nodes.values.single { it.canonicalKey == "java runtime" }
        assertEquals(
            listOf("c4"),
            graph.edges.filter { it.entityId == minted.entityId }.map { it.claimId },
        )
        // The split entity kept only surviving aliases.
        assertEquals(emptyList(), graph.nodes.getValue("e-x").aliases)

        val entry = journal.entries.single()
        assertEquals("SPLIT", entry.action)
        assertEquals(4, entry.mentionsRewired)
        assertEquals(listOf("s1", "s2", "s3"), entry.affectedSubjectIds)
        assertTrue(entry.details.any { it.startsWith("Java runtime → SKILL|java runtime") })
    }

    @Test
    fun `split refuses tombstones and journals even a no-op redistribution`() {
        graph.seed(entity("e-dead", key = "old", name = "Old", mergedInto = "e-live"))
        graph.seed(entity("e-live", key = "live", name = "Live"))
        graph.mention("c1", "s1", "Live", "e-live")

        assertTrue(service().split("e-dead", null).errorOrNull() is DomainError.Conflict)

        val outcome = service().split("e-live", null).valueOrNull()
        assertNotNull(outcome)
        assertEquals(1, outcome.mentionsKept)
        assertEquals(0, outcome.mentionsMoved)
        assertEquals("SPLIT", journal.entries.single().action)
        assertEquals(0, journal.entries.single().mentionsRewired)
    }
}

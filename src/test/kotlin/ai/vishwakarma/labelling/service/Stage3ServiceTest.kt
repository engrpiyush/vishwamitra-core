package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.Asset
import ai.vishwakarma.labelling.domain.AssetModality
import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimBasis
import ai.vishwakarma.labelling.domain.ClaimReview
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.ContentType
import ai.vishwakarma.labelling.domain.IntakeManifest
import ai.vishwakarma.labelling.domain.Relationship
import ai.vishwakarma.labelling.domain.ReviewDecision
import ai.vishwakarma.labelling.domain.SourceClass
import ai.vishwakarma.labelling.domain.Stage3Counters
import ai.vishwakarma.labelling.domain.Stage3Run
import ai.vishwakarma.labelling.domain.Stage3RunStatus
import ai.vishwakarma.labelling.domain.Subject
import ai.vishwakarma.labelling.persistence.AssetRepository
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage3.ClaimRow
import ai.vishwakarma.labelling.stage3.ClaimToEmbed
import ai.vishwakarma.labelling.stage3.ClaimToResolve
import ai.vishwakarma.labelling.stage3.EmbeddedClaim
import ai.vishwakarma.labelling.stage3.EmbeddingService
import ai.vishwakarma.labelling.stage3.EmbeddingTaskType
import ai.vishwakarma.labelling.stage3.EntityCandidate
import ai.vishwakarma.labelling.stage3.EntityMentionExtractor
import ai.vishwakarma.labelling.stage3.EntityRef
import ai.vishwakarma.labelling.stage3.EntityResolutionWrite
import ai.vishwakarma.labelling.stage3.EntityResolver
import ai.vishwakarma.labelling.stage3.EntityType
import ai.vishwakarma.labelling.stage3.EvidenceProjection
import ai.vishwakarma.labelling.stage3.ExtractedMention
import ai.vishwakarma.labelling.stage3.ExtractedMentions
import ai.vishwakarma.labelling.stage3.GraphPing
import ai.vishwakarma.labelling.stage3.MentionLinkRow
import ai.vishwakarma.labelling.stage3.PseudoEmbeddingService
import ai.vishwakarma.labelling.stage3.SchemaStatus
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.neo4j.driver.Driver

private fun <A, B> Either<A, B>.valueOrNull(): B? = fold({ null }, { it })

private fun <A, B> Either<A, B>.errorOrNull(): A? = fold({ it }, { null })

// In-memory fakes over the (all-open) repositories, the Stage2ServiceTest pattern: every
// DB-facing method is overridden, so the mocked ctor args are never touched.

private class FakeStage3RunRepo : Stage3RunRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage3Run>()
    private var seq = 0

    override fun newId(): String = "run-${++seq}"

    override fun findById(id: String): Stage3Run? = store[id]

    override fun findBySubject(subjectId: String): List<Stage3Run> =
        store.values.filter { it.subjectId == subjectId }.sortedByDescending { it.createdAt }

    override fun save(run: Stage3Run) {
        store[run.id] = run
    }
}

private class FakeS3ManifestRepo : IntakeManifestRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, IntakeManifest>()

    override fun findBySubject(subjectId: String): IntakeManifest? = store[subjectId]

    override fun save(manifest: IntakeManifest) {
        store[manifest.subjectId] = manifest
    }
}

private class FakeS3SubjectRepo : SubjectRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Subject>()

    override fun findById(id: String): Subject? = store[id]
}

private class FakeS3AssetRepo : AssetRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Asset>()

    override fun findById(id: String): Asset? = store[id]

    override fun findBySubject(subjectId: String): List<Asset> =
        store.values.filter { it.subjectId == subjectId }
}

private class FakeS3JobRepo : Stage2JobRepository(mock(Firestore::class.java)) {
    override fun findBySubject(subjectId: String) =
        emptyList<ai.vishwakarma.labelling.domain.Stage2Job>()
}

private class FakeS3ClaimRepo : ClaimRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Claim>()
    var failNext = false

    override fun findById(id: String): Claim? = store[id]

    override fun findBySubject(subjectId: String): List<Claim> {
        if (failNext) throw IllegalStateException("Firestore transport blip")
        return store.values.filter { it.subjectId == subjectId }
    }
}

private class FakeS3ReviewRepo : ClaimReviewRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, ClaimReview>()

    override fun findByClaim(claimId: String): ClaimReview? = store[claimId]

    override fun findBySubject(subjectId: String): List<ClaimReview> =
        store.values.filter { it.subjectId == subjectId }

    override fun save(review: ClaimReview) {
        store[review.claimId] = review
    }
}

/** In-memory graph: captures projections and mimics the two stamp-cursor phases (§11.3/§11.4). */
private class FakeGraphRepo(props: AppProperties) :
    Stage3GraphRepository(mock(Driver::class.java), props) {
    var pingResult = GraphPing(reachable = true, latencyMs = 1, database = "neo4j")
    var schemaEnsured = 0
    var wipes = 0
    val projections = mutableListOf<EvidenceProjection>()
    /** claimId → embedding stamp (null = not yet embedded). */
    val claimStamps = linkedMapOf<String, String?>()
    /** claimId → entity-resolution stamp (null = mentions unresolved). */
    val claimResolutionStamps = linkedMapOf<String, String?>()
    val claimRows = linkedMapOf<String, ClaimRow>()
    /** "type|key" → entity, so later batches exact-match what earlier batches minted. */
    val entities = linkedMapOf<String, EntityRef>()
    val mentionLinks = mutableListOf<MentionLinkRow>()
    /** assetId → issuerTypeAndKey (the Source stamp the Q2 sweep keys on). */
    val sourceIssuers = linkedMapOf<String, String>()
    private var issuersUpgraded = false

    override fun ping(): GraphPing = pingResult

    override fun ensureSchema(): SchemaStatus {
        schemaEnsured++
        return SchemaStatus("ENTERPRISE", 3072, false)
    }

    override fun layerGuardViolations(): Map<String, Long> = emptyMap()

    override fun wipeEvidenceLayer(subjectId: String): Long {
        wipes++
        val removed = claimStamps.size
        claimStamps.clear()
        claimResolutionStamps.clear()
        claimRows.clear()
        sourceIssuers.clear() // Source nodes are evidence-layer; the global canon survives
        return removed.toLong()
    }

    override fun mergeEvidence(projection: EvidenceProjection) {
        projections += projection
        projection.claims.forEach { row ->
            claimStamps.putIfAbsent(row.claimId, null) // re-merge never clears an embedding
            claimResolutionStamps.putIfAbsent(row.claimId, null) // …nor a resolution stamp
            claimRows[row.claimId] = row
        }
    }

    override fun claimsNeedingEmbedding(
        subjectId: String,
        versionStamp: String,
        limit: Int,
    ): List<ClaimToEmbed> =
        claimStamps
            .filterValues { it == null || it != versionStamp }
            .keys
            .sorted()
            .take(limit)
            .mapNotNull { id ->
                claimRows[id]?.let { ClaimToEmbed(it.claimId, it.type, it.text, it.claimedDate) }
            }

    override fun countClaimsNeedingEmbedding(subjectId: String, versionStamp: String): Long =
        claimStamps.values.count { it == null || it != versionStamp }.toLong()

    override fun countClaims(subjectId: String): Long = claimStamps.size.toLong()

    override fun setClaimEmbeddings(
        subjectId: String,
        rows: List<EmbeddedClaim>,
        versionStamp: String,
    ) {
        rows.forEach { claimStamps[it.claimId] = versionStamp }
    }

    override fun claimsNeedingEntityResolution(
        subjectId: String,
        versionStamp: String,
        limit: Int,
    ): List<ClaimToResolve> =
        claimResolutionStamps
            .filterValues { it == null || it != versionStamp }
            .keys
            .sorted()
            .take(limit)
            .mapNotNull { id ->
                claimRows[id]?.let {
                    ClaimToResolve(it.claimId, it.type, it.text, it.sourceClass, it.assetId)
                }
            }

    override fun countClaimsNeedingEntityResolution(subjectId: String, versionStamp: String): Long =
        claimResolutionStamps.values.count { it == null || it != versionStamp }.toLong()

    override fun findEntityByKey(entityType: String, canonicalKey: String): EntityRef? =
        entities["$entityType|$canonicalKey"]

    override fun findEntityById(entityId: String): EntityRef? =
        entities.values.firstOrNull { it.entityId == entityId }

    override fun entityKnn(
        entityType: String,
        embedding: List<Double>,
        k: Int,
    ): List<EntityCandidate> = emptyList() // empty canon neighbourhood → unmatched surfaces mint

    override fun applyEntityResolution(subjectId: String, write: EntityResolutionWrite) {
        write.mints.forEach {
            entities["${it.entityType}|${it.canonicalKey}"] =
                EntityRef(it.entityId, it.entityType, it.canonicalKey, it.canonicalName, null)
        }
        mentionLinks += write.links
        write.sourceIssuers.forEach { sourceIssuers[it.assetId] = it.issuerTypeAndKey }
        write.claimIds.forEach { claimResolutionStamps[it] = write.stamp }
    }

    override fun upgradeIssuerAttestors(subjectId: String): Long {
        if (issuersUpgraded || sourceIssuers.isEmpty()) return 0
        issuersUpgraded = true
        return sourceIssuers.size.toLong()
    }
}

/** Scriptable extractor: canned per-claim mentions, observable batches, a failure switch. */
private class ScriptedExtractor(var mentionsByClaim: Map<String, ExtractedMentions> = emptyMap()) :
    EntityMentionExtractor {
    override val versionStamp = "test:1:abc123"
    var failNext = false
    val batches = mutableListOf<List<String>>()

    override fun extract(claims: List<ClaimToResolve>): Map<String, ExtractedMentions> {
        if (failNext) throw IllegalStateException("Gemini entity extraction 500 INTERNAL")
        batches += claims.map { it.claimId }
        return claims.associate {
            it.claimId to (mentionsByClaim[it.claimId] ?: ExtractedMentions(emptyList()))
        }
    }
}

private class FailingEmbeddings : EmbeddingService {
    override val versionStamp = "gemini-embedding-001:3072"
    override val dimensions = 3072

    override fun embed(text: String, taskType: EmbeddingTaskType): List<Double> =
        throw IllegalStateException("Vertex quota exceeded (429)")
}

class Stage3ServiceTest {

    private val subjectId = "s1"
    private val props =
        AppProperties(
            stage3 =
                AppProperties.Stage3(
                    embedBatchPerPoll = 2,
                    entityBatchPerPoll = 2,
                    phaseTimeout = Duration.ofMinutes(15),
                )
        )

    private val runs = FakeStage3RunRepo()
    private val manifests = FakeS3ManifestRepo()
    private val subjects = FakeS3SubjectRepo()
    private val assets = FakeS3AssetRepo()
    private val jobs = FakeS3JobRepo()
    private val claims = FakeS3ClaimRepo()
    private val reviews = FakeS3ReviewRepo()
    private val graph = FakeGraphRepo(props)
    private val reviewService = ClaimReviewService(manifests, jobs, claims, reviews, props)
    private val extractor = ScriptedExtractor()

    private fun service(embeddings: EmbeddingService = PseudoEmbeddingService(8)) =
        Stage3Service(
            runs,
            manifests,
            subjects,
            assets,
            jobs,
            reviews,
            reviewService,
            graph,
            embeddings,
            extractor,
            EntityResolver(graph, embeddings, props),
            props,
        )

    private fun seedSubject(reviewSubmitted: Boolean = true, claimCount: Int = 2) {
        subjects.store[subjectId] = Subject(id = subjectId, displayName = "Asha")
        manifests.store[subjectId] =
            IntakeManifest(
                id = subjectId,
                subjectId = subjectId,
                reviewLockedAt = Instant.now(),
                reviewSubmittedAt = if (reviewSubmitted) Instant.now() else null,
            )
        assets.store["a1"] =
            Asset(
                id = "a1",
                subjectId = subjectId,
                title = "self interview",
                modality = AssetModality.AUDIO,
                sourceClass = SourceClass.SELF,
                contentType = ContentType.SELF_INTERVIEW,
                relationship = Relationship.SELF,
            )
        (1..claimCount).forEach { i ->
            claims.store["c$i"] =
                Claim(
                    id = "c$i",
                    subjectId = subjectId,
                    assetId = "a1",
                    claimType = ClaimType.SKILL,
                    text = "claim $i",
                    sourceClass = SourceClass.SELF,
                    relationship = Relationship.SELF,
                    authenticityTier = AuthenticityTier.LOW,
                    favorability = 0.8,
                )
        }
    }

    // ---- submit guards ----------------------------------------------------------

    @Test
    fun `submit refuses while the claim review is not submitted`() {
        seedSubject(reviewSubmitted = false)
        val error = service().submit(subjectId, "op").errorOrNull()
        assertTrue(error is DomainError.Conflict)
        assertTrue(error!!.message.contains("review is not submitted"))
    }

    @Test
    fun `submit refuses while another run is active`() {
        seedSubject()
        val first = service().submit(subjectId, "op").valueOrNull()
        assertNotNull(first)
        val error = service().submit(subjectId, "op").errorOrNull()
        assertTrue(error is DomainError.Conflict)
        assertTrue(error!!.message.contains("already active"))
    }

    @Test
    fun `submit refuses when Neo4j is unreachable and reports the error verbatim`() {
        seedSubject()
        graph.pingResult = GraphPing(reachable = false, error = "connection refused: :7687")
        val error = service().submit(subjectId, "op").errorOrNull()
        assertTrue(error is DomainError.Conflict)
        assertTrue(error!!.message.contains("connection refused"))
    }

    @Test
    fun `submit freezes the params snapshot`() {
        seedSubject()
        val run = service().submit(subjectId, "op").valueOrNull()!!
        assertNotNull(run.paramsSnapshot)
        assertTrue(run.paramsSnapshot!!.contains("ensembleK"))
        assertEquals(1, graph.schemaEnsured)
    }

    // ---- the state machine walk ----------------------------------------------------

    @Test
    fun `polling walks PENDING through to AWAITING_REVIEW with real sync, entities and embed`() {
        seedSubject(claimCount = 3)
        extractor.mentionsByClaim =
            mapOf("c1" to ExtractedMentions(listOf(ExtractedMention("Kotlin", EntityType.SKILL))))
        val svc = service()
        var run = svc.submit(subjectId, "op").valueOrNull()!!
        assertEquals(Stage3RunStatus.PENDING, run.status)

        run = svc.poll(run.id).valueOrNull()!! // sync
        assertEquals(Stage3RunStatus.RESOLVING_ENTITIES, run.status)
        assertEquals(3L, run.counters[Stage3Counters.CLAIMS_SYNCED])
        assertEquals(1L, run.counters[Stage3Counters.SOURCES_SYNCED])
        assertEquals(1L, run.counters[Stage3Counters.ATTESTORS_SYNCED])

        run = svc.poll(run.id).valueOrNull()!! // resolves c1, c2 (batch = 2)
        assertEquals(Stage3RunStatus.RESOLVING_ENTITIES, run.status)
        assertEquals(2L, run.counters[Stage3Counters.CLAIMS_ENTITY_RESOLVED])
        assertEquals(1L, run.counters[Stage3Counters.ENTITIES_MINTED])

        run = svc.poll(run.id).valueOrNull()!! // resolves c3
        assertEquals(Stage3RunStatus.RESOLVING_ENTITIES, run.status)
        assertEquals(3L, run.counters[Stage3Counters.CLAIMS_ENTITY_RESOLVED])

        run = svc.poll(run.id).valueOrNull()!! // none left → issuer sweep → EMBEDDING
        assertEquals(Stage3RunStatus.EMBEDDING, run.status)
        assertEquals(0L, run.counters[Stage3Counters.ISSUER_ATTESTORS_UPGRADED])

        run = svc.poll(run.id).valueOrNull()!! // embeds c1, c2 (batch = 2)
        assertEquals(Stage3RunStatus.EMBEDDING, run.status)
        assertEquals(2L, run.counters[Stage3Counters.CLAIMS_EMBEDDED])

        run = svc.poll(run.id).valueOrNull()!! // embeds c3
        assertEquals(Stage3RunStatus.EMBEDDING, run.status)
        assertEquals(3L, run.counters[Stage3Counters.CLAIMS_EMBEDDED])

        run = svc.poll(run.id).valueOrNull()!! // nothing left → MATCHING
        assertEquals(Stage3RunStatus.MATCHING, run.status)

        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.JUDGING, run.status)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.ASSEMBLING, run.status)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.SCORING, run.status)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.AWAITING_REVIEW, run.status)

        // The Q6 park: the poll loop never advances past AWAITING_REVIEW (publish is VA-18).
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.AWAITING_REVIEW, run.status)
        assertNull(run.error)
    }

    @Test
    fun `sync projects only the approved claim set and keeps approved-only citations`() {
        seedSubject(claimCount = 2)
        // c3 is INFERRED and CONTESTED → excluded; c2 is SIDECARED citing c1 (kept) and c3
        // (dropped).
        claims.store["c3"] = claims.store["c1"]!!.copy(id = "c3", claimBasis = ClaimBasis.INFERRED)
        reviews.store["c3"] =
            ClaimReview(claimId = "c3", subjectId = subjectId, decision = ReviewDecision.CONTESTED)
        reviews.store["c2"] =
            ClaimReview(
                claimId = "c2",
                subjectId = subjectId,
                decision = ReviewDecision.SIDECARED,
                justification = "context for c2",
                corroboratingClaimIds = listOf("c1", "c3"),
                reviewedBy = "op",
                reviewedAt = Instant.now(),
            )
        val svc = service()
        var run = svc.submit(subjectId, "op").valueOrNull()!!
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.RESOLVING_ENTITIES, run.status)

        val projection = graph.projections.single()
        assertEquals(setOf("c1", "c2"), projection.claims.map { it.claimId }.toSet())
        assertEquals(listOf("c1"), projection.explanations.single().cites)
        assertEquals(1, projection.citationsDropped)
        assertEquals(2L, run.counters[Stage3Counters.CLAIMS_SYNCED])
        assertEquals(1L, run.counters[Stage3Counters.EXPLANATIONS_SYNCED])
        assertEquals(1L, run.counters[Stage3Counters.CITATIONS_DROPPED])
    }

    // ---- failure, retry, reclaim -----------------------------------------------------

    @Test
    fun `an embedding failure fails the run verbatim and retry resumes the phase`() {
        seedSubject(claimCount = 1)
        val failing = service(embeddings = FailingEmbeddings())
        var run = failing.submit(subjectId, "op").valueOrNull()!!
        run = failing.poll(run.id).valueOrNull()!! // SYNC
        run = failing.poll(run.id).valueOrNull()!! // resolves c1 (no mentions → no embed calls)
        run = failing.poll(run.id).valueOrNull()!! // none left → EMBEDDING
        run = failing.poll(run.id).valueOrNull()!! // embed fails
        assertEquals(Stage3RunStatus.FAILED, run.status)
        assertEquals(Stage3RunStatus.EMBEDDING, run.failedPhase)
        assertTrue(run.error!!.contains("Vertex quota exceeded (429)"))

        // Retry resumes exactly where it failed; a working embedder completes the phase.
        val working = service()
        run = working.retry(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.EMBEDDING, run.status)
        assertNull(run.error)
        run = working.poll(run.id).valueOrNull()!! // embeds the claim
        run = working.poll(run.id).valueOrNull()!! // none left → MATCHING
        assertEquals(Stage3RunStatus.MATCHING, run.status)
    }

    @Test
    fun `a phase with no progress past the timeout reclaims to FAILED`() {
        seedSubject()
        runs.store["r-stuck"] =
            Stage3Run(
                id = "r-stuck",
                subjectId = subjectId,
                status = Stage3RunStatus.EMBEDDING,
                createdAt = Instant.now().minus(Duration.ofHours(1)),
                phaseSince = Instant.now().minus(Duration.ofMinutes(20)),
            )
        val run = service().poll("r-stuck").valueOrNull()!!
        assertEquals(Stage3RunStatus.FAILED, run.status)
        assertEquals(Stage3RunStatus.EMBEDDING, run.failedPhase)
        assertTrue(run.error!!.contains("no progress"))
    }

    @Test
    fun `a transport failure while loading sync inputs leaves the run pollable`() {
        seedSubject()
        val svc = service()
        var run = svc.submit(subjectId, "op").valueOrNull()!!
        claims.failNext = true
        val error = svc.poll(run.id).errorOrNull()
        assertTrue(error is DomainError.Invalid)
        assertTrue(error!!.message.contains("transport blip"))
        // Nothing terminal was persisted — the next poll simply succeeds.
        assertEquals(Stage3RunStatus.SYNCING, runs.store[run.id]!!.status)
        claims.failNext = false
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.RESOLVING_ENTITIES, run.status)
    }

    // ---- RESOLVE_ENTITIES (VA-11) --------------------------------------------------

    @Test
    fun `entity resolution chunks per poll and later batches link what earlier ones minted`() {
        seedSubject(claimCount = 3)
        extractor.mentionsByClaim =
            mapOf(
                "c1" to ExtractedMentions(listOf(ExtractedMention("Neo4j", EntityType.SKILL))),
                // c3 lands in the second batch — its mention must hit the c1-minted entity.
                "c3" to ExtractedMentions(listOf(ExtractedMention("Neo4j,", EntityType.SKILL))),
            )
        val svc = service()
        var run = svc.submit(subjectId, "op").valueOrNull()!!
        run = svc.poll(run.id).valueOrNull()!! // SYNC
        run = svc.poll(run.id).valueOrNull()!! // resolves c1, c2
        assertEquals(1L, run.counters[Stage3Counters.ENTITIES_MINTED])
        run = svc.poll(run.id).valueOrNull()!! // resolves c3
        assertEquals(1L, run.counters[Stage3Counters.ENTITIES_MINTED])
        assertEquals(1L, run.counters[Stage3Counters.MENTIONS_LINKED])
        run = svc.poll(run.id).valueOrNull()!! // none left → EMBEDDING
        assertEquals(Stage3RunStatus.EMBEDDING, run.status)
        assertEquals(listOf(listOf("c1", "c2"), listOf("c3")), extractor.batches)
        assertTrue(graph.mentionLinks.all { it.canonicalKey == "neo4j" })
        assertEquals(setOf("c1", "c3"), graph.mentionLinks.map { it.claimId }.toSet())
    }

    @Test
    fun `an extraction failure fails the run verbatim and retry resumes the stamp cursor`() {
        seedSubject(claimCount = 3)
        val svc = service()
        var run = svc.submit(subjectId, "op").valueOrNull()!!
        run = svc.poll(run.id).valueOrNull()!! // SYNC
        run = svc.poll(run.id).valueOrNull()!! // resolves c1, c2
        extractor.failNext = true
        run = svc.poll(run.id).valueOrNull()!! // c3's batch blows up
        assertEquals(Stage3RunStatus.FAILED, run.status)
        assertEquals(Stage3RunStatus.RESOLVING_ENTITIES, run.failedPhase)
        assertTrue(run.error!!.contains("Gemini entity extraction 500 INTERNAL"))

        extractor.failNext = false
        run = svc.retry(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.RESOLVING_ENTITIES, run.status)
        run = svc.poll(run.id).valueOrNull()!! // only c3 is still unstamped
        assertEquals(listOf("c3"), extractor.batches.last())
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.EMBEDDING, run.status)
    }

    @Test
    fun `a resolved documentary issuer upgrades the fallback attestor at phase completion`() {
        seedSubject(claimCount = 1)
        assets.store["a1"] =
            assets.store["a1"]!!.copy(
                sourceClass = SourceClass.DOCUMENTARY,
                relationship = Relationship.INSTITUTION,
            )
        claims.store["c1"] =
            claims.store["c1"]!!.copy(
                sourceClass = SourceClass.DOCUMENTARY,
                relationship = Relationship.INSTITUTION,
            )
        extractor.mentionsByClaim =
            mapOf(
                "c1" to
                    ExtractedMentions(
                        listOf(ExtractedMention("Coursera", EntityType.INSTITUTION)),
                        issuerSurface = "Coursera",
                    )
            )
        val svc = service()
        var run = svc.submit(subjectId, "op").valueOrNull()!!
        run = svc.poll(run.id).valueOrNull()!! // SYNC
        run = svc.poll(run.id).valueOrNull()!! // resolves c1 → Source stamped
        assertEquals("INSTITUTION|coursera", graph.sourceIssuers["a1"])
        run = svc.poll(run.id).valueOrNull()!! // completion sweep upgrades the attestation
        assertEquals(Stage3RunStatus.EMBEDDING, run.status)
        assertEquals(1L, run.counters[Stage3Counters.ISSUER_ATTESTORS_UPGRADED])
    }

    // ---- rerun -------------------------------------------------------------------

    @Test
    fun `rerun requires PUBLISHED and creates a fresh run whose sync wipes the evidence layer`() {
        seedSubject()
        val svc = service()
        val pending = svc.submit(subjectId, "op").valueOrNull()!!
        assertTrue(
            svc.rerun(pending.id, fresh = true, actor = "op").errorOrNull() is DomainError.Conflict
        )

        runs.store[pending.id] = pending.copy(status = Stage3RunStatus.PUBLISHED)
        val next = svc.rerun(pending.id, fresh = true, actor = "op").valueOrNull()!!
        assertTrue(next.id != pending.id) // published record (and its params snapshot) is untouched
        assertTrue(next.fresh)
        assertEquals(Stage3RunStatus.PENDING, next.status)

        svc.poll(next.id).valueOrNull()!!
        assertEquals(1, graph.wipes)
    }
}

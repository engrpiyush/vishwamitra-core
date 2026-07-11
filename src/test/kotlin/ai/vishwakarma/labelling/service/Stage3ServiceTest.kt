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
import ai.vishwakarma.labelling.persistence.ClaimAuthenticityRow
import ai.vishwakarma.labelling.persistence.ClaimRepository
import ai.vishwakarma.labelling.persistence.ClaimReviewRepository
import ai.vishwakarma.labelling.persistence.IntakeManifestRepository
import ai.vishwakarma.labelling.persistence.Stage2JobRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeVerdict
import ai.vishwakarma.labelling.persistence.Stage3RunRepository
import ai.vishwakarma.labelling.persistence.SubjectRepository
import ai.vishwakarma.labelling.stage3.AssembleOutcome
import ai.vishwakarma.labelling.stage3.AttestorSnapshot
import ai.vishwakarma.labelling.stage3.ClaimCard
import ai.vishwakarma.labelling.stage3.ClaimJudgeService
import ai.vishwakarma.labelling.stage3.ClaimPair
import ai.vishwakarma.labelling.stage3.ClaimRow
import ai.vishwakarma.labelling.stage3.ClaimSnapshot
import ai.vishwakarma.labelling.stage3.ClaimToAssemble
import ai.vishwakarma.labelling.stage3.ClaimToEmbed
import ai.vishwakarma.labelling.stage3.ClaimToMatch
import ai.vishwakarma.labelling.stage3.ClaimToResolve
import ai.vishwakarma.labelling.stage3.ContradictionEdgeRef
import ai.vishwakarma.labelling.stage3.ContradictionSide
import ai.vishwakarma.labelling.stage3.ContradictionView
import ai.vishwakarma.labelling.stage3.EdgeSnapshot
import ai.vishwakarma.labelling.stage3.EmbeddedClaim
import ai.vishwakarma.labelling.stage3.EmbeddingService
import ai.vishwakarma.labelling.stage3.EmbeddingTaskType
import ai.vishwakarma.labelling.stage3.EntityCandidate
import ai.vishwakarma.labelling.stage3.EntityEmbedding
import ai.vishwakarma.labelling.stage3.EntityMentionExtractor
import ai.vishwakarma.labelling.stage3.EntityRef
import ai.vishwakarma.labelling.stage3.EntityResolutionWrite
import ai.vishwakarma.labelling.stage3.EntityResolver
import ai.vishwakarma.labelling.stage3.EntityToReembed
import ai.vishwakarma.labelling.stage3.EntityType
import ai.vishwakarma.labelling.stage3.EvidenceProjection
import ai.vishwakarma.labelling.stage3.ExplanationRow
import ai.vishwakarma.labelling.stage3.ExtractedMention
import ai.vishwakarma.labelling.stage3.ExtractedMentions
import ai.vishwakarma.labelling.stage3.FactSnapshot
import ai.vishwakarma.labelling.stage3.GraphPing
import ai.vishwakarma.labelling.stage3.GraphSnapshot
import ai.vishwakarma.labelling.stage3.JudgeQueueEntry
import ai.vishwakarma.labelling.stage3.JudgeRelation
import ai.vishwakarma.labelling.stage3.JudgeSample
import ai.vishwakarma.labelling.stage3.JudgeSampler
import ai.vishwakarma.labelling.stage3.JudgedPair
import ai.vishwakarma.labelling.stage3.JudgedPairRecord
import ai.vishwakarma.labelling.stage3.MatchOutcome
import ai.vishwakarma.labelling.stage3.MentionLinkRow
import ai.vishwakarma.labelling.stage3.PairToJudge
import ai.vishwakarma.labelling.stage3.PseudoEmbeddingService
import ai.vishwakarma.labelling.stage3.SchemaStatus
import ai.vishwakarma.labelling.stage3.ScoreOutcome
import ai.vishwakarma.labelling.stage3.ScoredClaimForPublish
import ai.vishwakarma.labelling.stage3.ScoredPair
import ai.vishwakarma.labelling.stage3.Stage3GraphRepository
import arrow.core.Either
import com.google.cloud.firestore.Firestore
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    val published = mutableListOf<ClaimAuthenticityRow>()
    var failPublish = false

    override fun findById(id: String): Claim? = store[id]

    override fun findBySubject(subjectId: String): List<Claim> {
        if (failNext) throw IllegalStateException("Firestore transport blip")
        return store.values.filter { it.subjectId == subjectId }
    }

    override fun publishAuthenticity(rows: List<ClaimAuthenticityRow>) {
        if (failPublish) throw IllegalStateException("Firestore ledger batch write failed")
        published += rows
        rows.forEach { row ->
            store[row.claimId] =
                store[row.claimId]!!.copy(
                    authenticityScore = row.score,
                    authenticitySignals = row.signals,
                    authenticityTier = AuthenticityTier.fromOrNull(row.tier),
                    scoreRunId = row.scoreRunId,
                    scoredAt = row.scoredAt,
                )
        }
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

    // ---- MATCH (VA-14): scripted kNN/sims, derived co-mention + human arms ----

    var knnPairs: List<ScoredPair> = emptyList()
    var pairSims: Map<ClaimPair, Double> = emptyMap()
    val staleEntities = mutableListOf<EntityToReembed>()
    val entityEmbeddings = mutableListOf<EntityEmbedding>()
    val matchOutcomes = mutableListOf<MatchOutcome>()
    var failMatchWrite = false

    // ---- JUDGE (VA-15): the persisted queue with wholesale-replace + status-flip ----

    class QueueSlot(val entry: JudgeQueueEntry, var status: String = "QUEUED") {
        var judged: JudgedPair? = null
    }

    val judgeQueue = linkedMapOf<ClaimPair, QueueSlot>()
    val judgeRepeats = mutableListOf<ClaimPair>()
    var failJudgeWrite = false

    /** Sidecar texts visible to the cards: SYNC-projected plus [upsertExplanations] refreshes. */
    val extraExplanations = linkedMapOf<String, String>()

    private fun explanationOf(claimId: String): String? =
        extraExplanations[claimId]
            ?: projections.flatMap { it.explanations }.firstOrNull { it.claimId == claimId }?.text

    private fun cardOf(claimId: String): ClaimCard {
        val row = claimRows[claimId]
        return ClaimCard(
            claimId = claimId,
            text = row?.text ?: "",
            type = row?.type,
            claimedDate = row?.claimedDate,
            sourceClass = row?.sourceClass,
            relationship = row?.relationship,
            speakerRole = row?.speakerRole,
            explanationText = explanationOf(claimId),
        )
    }

    override fun judgeQueueBatch(subjectId: String, limit: Int): List<PairToJudge> =
        judgeQueue.values
            .filter { it.status == "QUEUED" }
            .sortedBy { it.entry.rank }
            .take(limit)
            .map { slot ->
                PairToJudge(
                    pair = slot.entry.pair,
                    rank = slot.entry.rank.toLong(),
                    withContext = slot.entry.withContext,
                    humanAsserted = slot.entry.humanAsserted,
                    a = cardOf(slot.entry.pair.a),
                    b = cardOf(slot.entry.pair.b),
                    sharedEntities = emptyList(),
                )
            }

    override fun countJudgeQueue(subjectId: String, status: String): Long =
        judgeQueue.values.count { it.status == status }.toLong()

    override fun applyJudgeOutcome(subjectId: String, judged: List<JudgedPair>) {
        if (failJudgeWrite) throw IllegalStateException("Neo4j write failed: judge tx aborted")
        judged.forEach { p ->
            judgeQueue[p.pair]?.let {
                it.status = "JUDGED"
                it.judged = p
            }
            if (p.bare.relation == JudgeRelation.REPEATS) judgeRepeats += p.pair
        }
    }

    // ---- ASSEMBLE (VA-16): reads derived from the judged queue + captured outcome ----

    val assembleOutcomes = mutableListOf<AssembleOutcome>()
    var failAssembleWrite = false

    /** Mutable contradiction-edge state (VA-18 actions flip/delete/annotate these). */
    class FakeContradiction(
        val fromFactId: String,
        val toFactId: String,
        val confidence: Double,
        var reviewStatus: String?,
        var explained: Boolean,
        var withContext: Boolean,
        var ctxRelation: String?,
        var ctxConfidence: Double?,
        val contributingPairs: List<String>,
    )

    val contradictionEdges = linkedMapOf<String, FakeContradiction>()
    private var corroboratesEdges = listOf<EdgeSnapshot>()
    private var edgeSeq = 0

    override fun claimsForAssembly(subjectId: String): List<ClaimToAssemble> =
        claimRows.values
            .sortedBy { it.claimId }
            .map { row ->
                ClaimToAssemble(
                    claimId = row.claimId,
                    type = row.type,
                    text = row.text,
                    claimedDate = row.claimedDate,
                    sourceClass = row.sourceClass,
                    mentionTypes =
                        mentionLinks
                            .filter { it.claimId == row.claimId }
                            .map { it.entityType }
                            .sorted(),
                )
            }

    override fun repeatsPairs(subjectId: String): List<ClaimPair> =
        (matchOutcomes.flatMap { o -> o.autoRepeats.map { it.pair } } + judgeRepeats)
            .distinct()
            .sortedWith(compareBy({ it.a }, { it.b }))

    override fun judgedPairRecords(subjectId: String): List<JudgedPairRecord> =
        judgeQueue.values
            .filter { it.status == "JUDGED" }
            .mapNotNull { slot ->
                slot.judged?.let { p ->
                    JudgedPairRecord(
                        pair = p.pair,
                        relation = p.bare.relation,
                        confidence = p.bare.confidence,
                        votesJson = null,
                        rationale = p.bare.rationale,
                        temporalNote = p.bare.temporalNote,
                        ctxJudged = p.ctx != null,
                        ctxRelation = p.ctx?.relation,
                        ctxConfidence = p.ctx?.confidence,
                        ctxExplanationRelevant = p.ctx?.explanationRelevant,
                        judgeModel = p.judgeModel,
                        promptHash = p.promptStamp,
                        sharedEntities = emptyList(),
                    )
                }
            }
            .sortedWith(compareBy({ it.pair.a }, { it.pair.b }))

    override fun applyAssembleOutcome(subjectId: String, outcome: AssembleOutcome) {
        if (failAssembleWrite)
            throw IllegalStateException("Neo4j write failed: assemble tx aborted")
        assembleOutcomes += outcome
        // The wholesale :Fact rebuild: fact edges are recreated from scratch.
        contradictionEdges.clear()
        corroboratesEdges =
            outcome.edges
                .filter { it.relation == "CORROBORATES" }
                .map {
                    EdgeSnapshot(
                        fromFactId = it.fromFactId,
                        toFactId = it.toFactId,
                        relation = it.relation,
                        confidence = it.confidence,
                        withContext = it.withContext,
                        ctxRelation = it.ctxRelation,
                        ctxConfidence = it.ctxConfidence,
                        explained = it.explained,
                    )
                }
        outcome.edges
            .filter { it.relation == "CONTRADICTS" }
            .forEach {
                contradictionEdges["edge-${edgeSeq++}"] =
                    FakeContradiction(
                        fromFactId = it.fromFactId,
                        toFactId = it.toFactId,
                        confidence = it.confidence,
                        reviewStatus = it.reviewStatus,
                        explained = it.explained,
                        withContext = it.withContext,
                        ctxRelation = it.ctxRelation,
                        ctxConfidence = it.ctxConfidence,
                        contributingPairs = it.contributingPairs,
                    )
            }
    }

    // ---- AWAITING_REVIEW queue + publish (VA-18) ----

    override fun contradictionQueue(subjectId: String, floor: Double): List<ContradictionView> =
        contradictionEdges
            .filterValues {
                it.reviewStatus == "PROPOSED" && !it.explained && it.confidence >= floor
            }
            .map { (id, e) ->
                ContradictionView(
                    edgeId = id,
                    confidence = e.confidence,
                    votesJson = null,
                    rationale = "scripted",
                    temporalNote = null,
                    temporalOverlap = true,
                    viaEntities = emptyList(),
                    contributingPairs = e.contributingPairs,
                    from = ContradictionSide(e.fromFactId, "", null, null, emptyList()),
                    to = ContradictionSide(e.toFactId, "", null, null, emptyList()),
                )
            }

    override fun findContradictionEdge(edgeId: String): ContradictionEdgeRef? =
        contradictionEdges[edgeId]?.let {
            ContradictionEdgeRef(
                edgeId = edgeId,
                subjectId = "s1",
                fromFactId = it.fromFactId,
                toFactId = it.toFactId,
                reviewStatus = it.reviewStatus,
                contributingPairs = it.contributingPairs,
            )
        }

    override fun confirmContradiction(subjectId: String, edgeId: String): Boolean {
        val edge = contradictionEdges[edgeId] ?: return false
        if (edge.reviewStatus != "PROPOSED") return false
        edge.reviewStatus = "CONFIRMED"
        return true
    }

    override fun deleteContradiction(subjectId: String, edgeId: String): Boolean =
        contradictionEdges.remove(edgeId) != null

    override fun updateContradictionContext(
        subjectId: String,
        edgeId: String,
        ctxRelation: String?,
        ctxConfidence: Double?,
        explained: Boolean,
    ) {
        contradictionEdges[edgeId]?.let {
            it.withContext = true
            it.ctxRelation = ctxRelation
            it.ctxConfidence = ctxConfidence
            it.explained = explained
        }
    }

    override fun upsertExplanations(subjectId: String, rows: List<ExplanationRow>) {
        rows.forEach { extraExplanations[it.claimId] = it.text }
    }

    override fun hydratePairs(
        subjectId: String,
        pairs: Collection<ClaimPair>,
    ): List<PairToJudge> =
        pairs.sortedWith(compareBy({ it.a }, { it.b })).map { p ->
            val a = cardOf(p.a)
            val b = cardOf(p.b)
            PairToJudge(
                pair = p,
                rank = 0,
                withContext = a.explanationText != null || b.explanationText != null,
                humanAsserted = false,
                a = a,
                b = b,
                sharedEntities = emptyList(),
            )
        }

    override fun scoredClaimsForPublish(subjectId: String): List<ScoredClaimForPublish> =
        scoreOutcomes.lastOrNull()?.claims.orEmpty().map { c ->
            ScoredClaimForPublish(
                claimId = c.claimId,
                score = c.score,
                signalsJson = """{"prior":${c.prior},"scoreBare":${c.scoreBare}}""",
            )
        }

    // ---- SCORE (VA-17): snapshot derived from the last assembly ----

    val scoreOutcomes = mutableListOf<ScoreOutcome>()

    override fun scoreSnapshot(subjectId: String): GraphSnapshot {
        val assembly = assembleOutcomes.lastOrNull()
        val factByClaim =
            assembly
                ?.facts
                .orEmpty()
                .flatMap { f -> f.memberClaimIds.map { it to f.factId } }
                .toMap()
        return GraphSnapshot(
            claims =
                claimRows.values
                    .sortedBy { it.claimId }
                    .mapNotNull { row ->
                        factByClaim[row.claimId]?.let { factId ->
                            ClaimSnapshot(
                                claimId = row.claimId,
                                factId = factId,
                                type = row.type,
                                tierSeed = row.tierSeed,
                                sourceClass = row.sourceClass,
                                basis = row.basis,
                                favorability = row.favorability,
                                claimedDate = row.claimedDate,
                                attestorKey = row.attestorKey,
                                assetId = row.assetId,
                            )
                        }
                    },
            facts =
                assembly?.facts.orEmpty().map {
                    FactSnapshot(it.factId, it.factKind, it.exemplarClaimId, it.anchored)
                },
            attestors =
                projections
                    .flatMap { it.attestors }
                    .distinctBy { it.attestorKey }
                    .map { AttestorSnapshot(it.attestorKey, it.trustPrior, it.trustPrior) },
            edges =
                corroboratesEdges +
                    contradictionEdges.values.map {
                        EdgeSnapshot(
                            fromFactId = it.fromFactId,
                            toFactId = it.toFactId,
                            relation = "CONTRADICTS",
                            confidence = it.confidence,
                            withContext = it.withContext,
                            ctxRelation = it.ctxRelation,
                            ctxConfidence = it.ctxConfidence,
                            explained = it.explained,
                        )
                    },
        )
    }

    override fun applyScoreOutcome(subjectId: String, outcome: ScoreOutcome) {
        scoreOutcomes += outcome
    }

    override fun countContradictionQueue(subjectId: String, floor: Double): Long =
        contradictionEdges.values
            .count { it.reviewStatus == "PROPOSED" && !it.explained && it.confidence >= floor }
            .toLong()

    override fun claimsForMatching(subjectId: String): List<ClaimToMatch> {
        val explained = projections.flatMap { it.explanations }.map { it.claimId }.toSet()
        return claimRows.values
            .sortedBy { it.claimId }
            .map {
                ClaimToMatch(
                    claimId = it.claimId,
                    type = it.type,
                    text = it.text,
                    claimedDate = it.claimedDate,
                    assetId = it.assetId,
                    explained = it.claimId in explained,
                    exemplarClaimId = null, // :Fact clusters arrive with VA-16
                )
            }
    }

    override fun claimKnnPairs(subjectId: String, k: Int, simFloor: Double): List<ScoredPair> =
        knnPairs.filter { it.sim >= simFloor }

    override fun coMentionPairs(subjectId: String, idfFloor: Double): List<ClaimPair> =
        mentionLinks
            .groupBy { "${it.entityType}|${it.canonicalKey}" }
            .values
            .flatMap { links ->
                val ids = links.map { it.claimId }.distinct().sorted()
                buildList {
                    for (i in ids.indices) for (j in i + 1 until ids.size) add(
                        ClaimPair(ids[i], ids[j])
                    )
                }
            }
            .distinct()

    override fun humanAssertedPairs(subjectId: String): List<ClaimPair> =
        projections
            .flatMap { it.explanations }
            .flatMap { x -> x.cites.map { ClaimPair.of(x.claimId, it) } }
            .filter { it.a != it.b }
            .distinct()

    override fun pairSimilarities(
        subjectId: String,
        pairs: Collection<ClaimPair>,
    ): Map<ClaimPair, Double> = pairs.mapNotNull { p -> pairSims[p]?.let { p to it } }.toMap()

    override fun entitiesNeedingReembedding(
        subjectId: String,
        versionStamp: String,
        limit: Int,
    ): List<EntityToReembed> = staleEntities.take(limit)

    override fun setEntityEmbeddings(rows: List<EntityEmbedding>, versionStamp: String) {
        entityEmbeddings += rows
        staleEntities.removeAll { stale -> rows.any { it.entityId == stale.entityId } }
    }

    override fun applyMatchOutcome(subjectId: String, outcome: MatchOutcome) {
        if (failMatchWrite) throw IllegalStateException("Neo4j write failed: connection reset")
        matchOutcomes += outcome
        // The wholesale replace: MATCH re-runs yield exactly the new queue, never a union.
        judgeQueue.clear()
        outcome.queue.forEach { judgeQueue[it.pair] = QueueSlot(it) }
    }
}

/** Scriptable ensemble member for lifecycle tests: fixed relations, observable call count. */
private class ScriptedJudgeSampler(
    var verdicts: Map<ClaimPair, JudgeRelation> = emptyMap(),
) : JudgeSampler {
    var calls = 0

    override val versionStamp = "test:1:judgehash"

    override val modelId = "test-judge"

    // Synchronized: the ensemble fans samples out across threads (2026-07-11).
    @Synchronized
    override fun sample(
        pairs: List<PairToJudge>,
        withContext: Boolean,
        sampleIndex: Int,
    ): Map<ClaimPair, JudgeSample> {
        calls++
        return pairs.associate {
            it.pair to
                JudgeSample(
                    relation = verdicts[it.pair] ?: JudgeRelation.NEUTRAL,
                    confidence = 0.9,
                    rationale = "scripted",
                    temporalNote = null,
                    explanationRelevant = if (withContext) true else null,
                )
        }
    }
}

/** In-memory `stage3_edges` cache. */
private class FakeJudgeEdgeRepo : Stage3EdgeRepository(mock(Firestore::class.java)) {
    val store = linkedMapOf<String, Stage3EdgeVerdict>()

    override fun findAll(ids: Collection<String>): Map<String, Stage3EdgeVerdict> =
        ids.mapNotNull { store[it] }.associateBy { it.id }

    override fun saveAll(rows: List<Stage3EdgeVerdict>) {
        rows.forEach { store[it.id] = it }
    }

    override fun markOverridden(claimIdLow: String, claimIdHigh: String): Int {
        val hits =
            store.values.filter { it.claimIdLow == claimIdLow && it.claimIdHigh == claimIdHigh }
        hits.forEach { store[it.id] = it.copy(overridden = true) }
        return hits.size
    }

    override fun deleteBySubject(subjectId: String): Int {
        val ids = store.values.filter { it.subjectId == subjectId }.map { it.id }
        ids.forEach { store.remove(it) }
        return ids.size
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
                    judgePairsPerPoll = 1,
                    ensembleK = 3,
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
    private val judgeSampler = ScriptedJudgeSampler()
    private val judgeEdges = FakeJudgeEdgeRepo()

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
            ClaimJudgeService(judgeSampler, judgeEdges, props),
            judgeEdges,
            claims,
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

        run = svc.poll(run.id).valueOrNull()!! // real MATCH: no candidate pairs on this corpus
        assertEquals(Stage3RunStatus.JUDGING, run.status)
        assertEquals(0L, run.counters[Stage3Counters.PAIRS_QUEUED])
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

    // ---- MATCH (VA-14) --------------------------------------------------------------

    /** Poll a fresh run until it parks at [target] (guards against silent walk regressions). */
    private fun walkTo(svc: Stage3Service, target: Stage3RunStatus): Stage3Run {
        var run = svc.submit(subjectId, "op").valueOrNull()!!
        repeat(20) {
            if (run.status == target) return run
            run = svc.poll(run.id).valueOrNull()!!
        }
        error("run never reached $target (stuck at ${run.status})")
    }

    @Test
    fun `match queues cascade survivors and writes auto-repeats without the judge`() {
        seedSubject(claimCount = 3)
        val svc = service()
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        graph.knnPairs =
            listOf(
                ScoredPair(ClaimPair.of("c1", "c2"), 0.95), // τ_high same-type → AUTO REPEATS
                ScoredPair(ClaimPair.of("c1", "c3"), 0.70), // cascade survivor → queued
            )
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.JUDGING, run.status)
        val outcome = graph.matchOutcomes.single()
        assertEquals(listOf(ClaimPair.of("c1", "c2")), outcome.autoRepeats.map { it.pair })
        assertEquals(3, outcome.autoRepeats.single().rung)
        val entry = outcome.queue.single()
        assertEquals(ClaimPair.of("c1", "c3"), entry.pair)
        assertEquals(0, entry.rank)
        assertEquals(0.70, entry.blockScore)
        assertEquals(2L, run.counters[Stage3Counters.PAIRS_KNN])
        assertEquals(2L, run.counters[Stage3Counters.PAIRS_CANDIDATE])
        assertEquals(1L, run.counters[Stage3Counters.PAIRS_AUTO_RESOLVED])
        assertEquals(1L, run.counters[Stage3Counters.PAIRS_QUEUED])
        assertEquals(0L, run.counters[Stage3Counters.PAIRS_DISCARDED])
    }

    @Test
    fun `match queues human-asserted sidecar pairs even at rock-bottom similarity`() {
        seedSubject(claimCount = 2)
        reviews.store["c2"] =
            ClaimReview(
                claimId = "c2",
                subjectId = subjectId,
                decision = ReviewDecision.SIDECARED,
                justification = "the cited claim shows the same project",
                corroboratingClaimIds = listOf("c1"),
                reviewedBy = "op",
                reviewedAt = Instant.now(),
            )
        val svc = service()
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        graph.pairSims = mapOf(ClaimPair.of("c1", "c2") to 0.05)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.JUDGING, run.status)
        val entry = graph.matchOutcomes.single().queue.single()
        assertEquals(ClaimPair.of("c1", "c2"), entry.pair)
        assertTrue(entry.humanAsserted)
        assertTrue(entry.withContext) // c2 carries the sidecar → §11.9 dual evaluation
        assertEquals(1L, run.counters[Stage3Counters.PAIRS_HUMAN_ASSERTED])
        assertEquals(1L, run.counters[Stage3Counters.PAIRS_QUEUED])
    }

    @Test
    fun `match bounces stale claim vectors back to embedding and returns`() {
        seedSubject(claimCount = 2)
        val svc = service()
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        graph.claimStamps["c1"] = "pseudo:stale" // model/dims changed since EMBED (§15 #6)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.EMBEDDING, run.status)
        run = svc.poll(run.id).valueOrNull()!! // re-embeds c1
        run = svc.poll(run.id).valueOrNull()!! // clean → back to MATCHING
        assertEquals(Stage3RunStatus.MATCHING, run.status)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.JUDGING, run.status)
    }

    @Test
    fun `match re-embeds stale entity vectors before blocking`() {
        seedSubject(claimCount = 2)
        val svc = service()
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        graph.staleEntities += EntityToReembed("e-kotlin", "Kotlin")
        run = svc.poll(run.id).valueOrNull()!! // one bounded re-embed chunk, same phase
        assertEquals(Stage3RunStatus.MATCHING, run.status)
        assertEquals(1L, run.counters[Stage3Counters.ENTITIES_REEMBEDDED])
        assertEquals("e-kotlin", graph.entityEmbeddings.single().entityId)
        run = svc.poll(run.id).valueOrNull()!! // vectors current → the blocking tick
        assertEquals(Stage3RunStatus.JUDGING, run.status)
    }

    @Test
    fun `a match write failure fails the run verbatim and retry resumes the phase`() {
        seedSubject(claimCount = 2)
        val svc = service()
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        graph.failMatchWrite = true
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.FAILED, run.status)
        assertEquals(Stage3RunStatus.MATCHING, run.failedPhase)
        assertTrue(run.error!!.contains("connection reset"))
        graph.failMatchWrite = false
        run = svc.retry(run.id).valueOrNull()!!
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.JUDGING, run.status)
    }

    // ---- JUDGE (VA-15) ---------------------------------------------------------------

    /** Poll an existing run until it parks at [target]. */
    private fun pollTo(svc: Stage3Service, runId: String, target: Stage3RunStatus): Stage3Run {
        var run = runs.store[runId]!!
        repeat(30) {
            if (run.status == target) return run
            run = svc.poll(run.id).valueOrNull()!!
        }
        error("run never reached $target (stuck at ${run.status})")
    }

    /** Two cascade-surviving pairs on a 3-claim corpus: (c1,c2) rank 0, (c1,c3) rank 1. */
    private fun queueTwoPairs(svc: Stage3Service): Stage3Run {
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        graph.knnPairs =
            listOf(
                ScoredPair(ClaimPair.of("c1", "c2"), 0.80),
                ScoredPair(ClaimPair.of("c1", "c3"), 0.70),
            )
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.JUDGING, run.status)
        assertEquals(2L, run.counters[Stage3Counters.PAIRS_QUEUED])
        return run
    }

    @Test
    fun `judge consumes the queue in rank-order chunks and writes repeats edges`() {
        seedSubject(claimCount = 3)
        judgeSampler.verdicts = mapOf(ClaimPair.of("c1", "c2") to JudgeRelation.REPEATS)
        val svc = service()
        var run = queueTwoPairs(svc)

        run = svc.poll(run.id).valueOrNull()!! // judges rank 0 only (judge-pairs-per-poll = 1)
        assertEquals(Stage3RunStatus.JUDGING, run.status)
        assertEquals(1L, run.counters[Stage3Counters.PAIRS_JUDGED])
        assertEquals("JUDGED", graph.judgeQueue[ClaimPair.of("c1", "c2")]!!.status)
        assertEquals("QUEUED", graph.judgeQueue[ClaimPair.of("c1", "c3")]!!.status)

        run = svc.poll(run.id).valueOrNull()!! // judges rank 1
        assertEquals(Stage3RunStatus.JUDGING, run.status)
        assertEquals(2L, run.counters[Stage3Counters.PAIRS_JUDGED])

        run = svc.poll(run.id).valueOrNull()!! // queue empty → ASSEMBLING
        assertEquals(Stage3RunStatus.ASSEMBLING, run.status)
        assertEquals(2L, run.counters[Stage3Counters.PAIRS_JUDGED])
        assertEquals(0L, run.counters[Stage3Counters.JUDGE_TIES])

        // The REPEATS majority wrote a claim-level edge; the NEUTRAL verdict wrote none.
        assertEquals(listOf(ClaimPair.of("c1", "c2")), graph.judgeRepeats)
        val neutral = graph.judgeQueue[ClaimPair.of("c1", "c3")]!!.judged!!
        assertEquals(JudgeRelation.NEUTRAL, neutral.bare.relation)
        assertNull(neutral.ctx)
    }

    @Test
    fun `a judge write failure fails the run verbatim and retry completes via the cache`() {
        seedSubject(claimCount = 3)
        val svc = service()
        var run = queueTwoPairs(svc)
        run = svc.poll(run.id).valueOrNull()!! // rank 0 judged fine
        val callsBeforeFailure = judgeSampler.calls

        graph.failJudgeWrite = true
        run = svc.poll(run.id).valueOrNull()!! // rank 1 sampled, write blows up
        assertEquals(Stage3RunStatus.FAILED, run.status)
        assertEquals(Stage3RunStatus.JUDGING, run.failedPhase)
        assertTrue(run.error!!.contains("judge tx aborted"))
        assertTrue(judgeSampler.calls > callsBeforeFailure)

        graph.failJudgeWrite = false
        run = svc.retry(run.id).valueOrNull()!!
        val callsBeforeRetry = judgeSampler.calls
        run = svc.poll(run.id).valueOrNull()!! // the failed pair re-runs on cached verdicts
        assertEquals(judgeSampler.calls, callsBeforeRetry)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.ASSEMBLING, run.status)
        assertEquals(2L, run.counters[Stage3Counters.PAIRS_JUDGED])
    }

    @Test
    fun `a rerun over unchanged pairs judges entirely from the cache`() {
        seedSubject(claimCount = 3)
        val svc = service()
        var run = queueTwoPairs(svc)
        run = pollTo(svc, run.id, Stage3RunStatus.AWAITING_REVIEW)
        assertTrue(judgeSampler.calls > 0)

        runs.store[run.id] = run.copy(status = Stage3RunStatus.PUBLISHED)
        val next = svc.rerun(run.id, fresh = false, actor = "op").valueOrNull()!!
        val callsBefore = judgeSampler.calls
        val rerun = pollTo(svc, next.id, Stage3RunStatus.AWAITING_REVIEW)
        assertEquals(callsBefore, judgeSampler.calls) // zero sampling — every verdict cached
        assertEquals(2L, rerun.counters[Stage3Counters.JUDGE_CACHE_HITS])
        assertEquals(0L, rerun.counters[Stage3Counters.JUDGE_SAMPLER_CALLS] ?: 0L)
    }

    @Test
    fun `a fresh rerun drops the cache and judges everything again`() {
        seedSubject(claimCount = 3)
        val svc = service()
        var run = queueTwoPairs(svc)
        run = pollTo(svc, run.id, Stage3RunStatus.AWAITING_REVIEW)
        assertTrue(judgeEdges.store.isNotEmpty())

        runs.store[run.id] = run.copy(status = Stage3RunStatus.PUBLISHED)
        val next = svc.rerun(run.id, fresh = true, actor = "op").valueOrNull()!!
        val callsBefore = judgeSampler.calls
        val rerun = pollTo(svc, next.id, Stage3RunStatus.AWAITING_REVIEW)
        assertTrue(judgeSampler.calls > callsBefore) // cache was dropped at SYNC → re-judged
        assertEquals(0L, rerun.counters[Stage3Counters.JUDGE_CACHE_HITS] ?: 0L)
    }

    @Test
    fun `a rerun from AWAITING_REVIEW retires the parked run as SUPERSEDED`() {
        seedSubject(claimCount = 3)
        val svc = service()
        var run = queueTwoPairs(svc)
        run = pollTo(svc, run.id, Stage3RunStatus.AWAITING_REVIEW)

        val next = svc.rerun(run.id, fresh = false, actor = "op").valueOrNull()!!
        assertEquals(Stage3RunStatus.PENDING, next.status)
        val parked = runs.store[run.id]!!
        assertEquals(Stage3RunStatus.SUPERSEDED, parked.status)
        assertNotNull(parked.finishedAt)
        // The retired run left the active set — the replacement walks back to the park.
        pollTo(svc, next.id, Stage3RunStatus.AWAITING_REVIEW)
    }

    @Test
    fun `rerun refuses a mid-phase run`() {
        seedSubject(claimCount = 3)
        val svc = service()
        val run = queueTwoPairs(svc) // parked mid-pipeline at JUDGING
        val error = svc.rerun(run.id, fresh = false, actor = "op").errorOrNull()
        assertTrue(error is DomainError.Conflict)
        assertTrue(error!!.message.contains("Only PUBLISHED or AWAITING_REVIEW"))
    }

    @Test
    fun `withContext queue entries reach the graph with both verdict variants`() {
        seedSubject(claimCount = 2)
        reviews.store["c2"] =
            ClaimReview(
                claimId = "c2",
                subjectId = subjectId,
                decision = ReviewDecision.SIDECARED,
                justification = "I led the backend workstream",
                corroboratingClaimIds = listOf("c1"),
                reviewedBy = "op",
                reviewedAt = Instant.now(),
            )
        judgeSampler.verdicts = mapOf(ClaimPair.of("c1", "c2") to JudgeRelation.CONTRADICTS)
        val svc = service()
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        graph.pairSims = mapOf(ClaimPair.of("c1", "c2") to 0.5)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.JUDGING, run.status)

        run = svc.poll(run.id).valueOrNull()!!
        val judged = graph.judgeQueue[ClaimPair.of("c1", "c2")]!!.judged!!
        assertEquals(JudgeRelation.CONTRADICTS, judged.bare.relation)
        assertNotNull(judged.ctx)
        assertTrue(judged.ctx!!.explanationRelevant)
        assertEquals("test:1:judgehash", judged.promptStamp)
        // Both variants cached under distinct keys.
        assertEquals(setOf(false, true), judgeEdges.store.values.map { it.withContext }.toSet())
    }

    // ---- ASSEMBLE (VA-16) --------------------------------------------------------------

    @Test
    fun `assemble clusters judged repeats into facts and advances to SCORING`() {
        seedSubject(claimCount = 3)
        judgeSampler.verdicts = mapOf(ClaimPair.of("c1", "c2") to JudgeRelation.REPEATS)
        val svc = service()
        var run = queueTwoPairs(svc)
        run = pollTo(svc, run.id, Stage3RunStatus.ASSEMBLING)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.SCORING, run.status)

        // The judged REPEATS merged c1+c2; c3 stays a singleton. Undated SKILL claims → TIMELESS.
        assertEquals(2L, run.counters[Stage3Counters.FACTS])
        assertEquals(2L, run.counters[Stage3Counters.FACTS_TIMELESS])
        assertEquals(0L, run.counters[Stage3Counters.FACT_CONTRADICTS])
        val facts = graph.assembleOutcomes.single().facts
        val cluster = facts.first { it.factId == "fact:c1" }
        assertEquals(listOf("c1", "c2"), cluster.memberClaimIds)
        assertEquals(listOf("c3"), facts.first { it.factId == "fact:c3" }.memberClaimIds)
    }

    // ---- SCORE (VA-17) -----------------------------------------------------------------

    @Test
    fun `score writes provisional vectors and parks the run at the review gate`() {
        seedSubject(claimCount = 3)
        val svc = service()
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        run = pollTo(svc, run.id, Stage3RunStatus.SCORING)
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.AWAITING_REVIEW, run.status)
        assertEquals(3L, run.counters[Stage3Counters.CLAIMS_SCORED])
        assertEquals(0L, run.counters[Stage3Counters.CONTRADICTION_QUEUE])
        assertEquals(0L, run.counters[Stage3Counters.SCORE_I2_CLAMPED])
        assertEquals(true, run.converged)
        assertNotNull(run.iterations)

        val outcome = graph.scoreOutcomes.single()
        // Three SELF singletons: belief = the LOW prior, tier movement LOW → LOW.
        assertEquals(3, outcome.claims.size)
        outcome.claims.forEach {
            assertEquals(0.35, it.score, 0.005)
            assertEquals("LOW", it.tier)
            assertTrue(it.score >= it.scoreBare)
        }
        // The subject attestor's trust update landed (shrinkage toward the 0.5 prior).
        assertTrue(outcome.trustUpdates.isNotEmpty())
        // The park holds: further polls are no-ops (the Q6 gate).
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.AWAITING_REVIEW, run.status)
    }

    @Test
    fun `an assemble write failure fails the run verbatim and retry resumes the phase`() {
        seedSubject(claimCount = 2)
        val svc = service()
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        run = pollTo(svc, run.id, Stage3RunStatus.ASSEMBLING)
        graph.failAssembleWrite = true
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.FAILED, run.status)
        assertEquals(Stage3RunStatus.ASSEMBLING, run.failedPhase)
        assertTrue(run.error!!.contains("assemble tx aborted"))
        graph.failAssembleWrite = false
        run = svc.retry(run.id).valueOrNull()!!
        run = svc.poll(run.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.SCORING, run.status)
    }

    // ---- AWAITING_REVIEW queue actions + publish (VA-18) --------------------------------

    /**
     * A corpus whose run parks with ONE proposed contradiction: two dated 2019 EPISODEs, judged
     * CONTRADICTS at 0.9 — same-year events overlap at episode granularity, so the §11.7 gate keeps
     * the edge. The contradictor is an ENDORSEMENT (MEDIUM, believed) so the penalty on c1 actually
     * bites — rel(B) of a LOW self-claim would be zero.
     */
    private fun contestedCorpus(svc: Stage3Service): Stage3Run {
        seedSubject(claimCount = 2)
        claims.store["c1"] =
            claims.store["c1"]!!.copy(
                claimType = ClaimType.EPISODE,
                claimedDate = LocalDate.of(2019, 6, 1),
            )
        claims.store["c2"] =
            claims.store["c2"]!!.copy(
                claimType = ClaimType.EPISODE,
                claimedDate = LocalDate.of(2019, 8, 1),
                sourceClass = SourceClass.ENDORSEMENT,
                relationship = Relationship.PEER,
                authenticityTier = AuthenticityTier.MEDIUM,
            )
        judgeSampler.verdicts = mapOf(ClaimPair.of("c1", "c2") to JudgeRelation.CONTRADICTS)
        var run = walkTo(svc, Stage3RunStatus.MATCHING)
        graph.pairSims = mapOf(ClaimPair.of("c1", "c2") to 0.7)
        run = pollTo(svc, run.id, Stage3RunStatus.AWAITING_REVIEW)
        assertEquals(1L, run.counters[Stage3Counters.CONTRADICTION_QUEUE])
        return run
    }

    @Test
    fun `publish refuses over a non-empty queue and skipReview publishes with the audit flag`() {
        val svc = service()
        val run = contestedCorpus(svc)
        assertEquals(1, svc.contradictions(subjectId).size)

        val refused = svc.publish(run.id, skipReview = false, actor = "op").errorOrNull()
        assertTrue(refused is DomainError.Conflict)
        assertTrue(refused!!.message.contains("skipReview"))

        val published = svc.publish(run.id, skipReview = true, actor = "op").valueOrNull()!!
        assertEquals(Stage3RunStatus.PUBLISHED, published.status)
        assertTrue(published.reviewSkipped)
        assertEquals("op", published.publishedBy)
        assertNotNull(published.publishedAt)
        assertEquals(2L, published.counters[Stage3Counters.CLAIMS_PUBLISHED])

        // The §11.11 ledger round-trip: vector fields land on the Firestore claims.
        val ledger = claims.store["c1"]!!
        assertEquals(published.id, ledger.scoreRunId)
        assertNotNull(ledger.authenticityScore)
        assertNotNull(ledger.scoredAt)
        assertEquals(AuthenticityTier.LOW, ledger.authenticityTier)
        assertTrue(ledger.authenticitySignals!!.containsKey("scoreBare"))
    }

    @Test
    fun `confirm keeps the penalty, empties the queue, and publish then proceeds`() {
        val svc = service()
        val run = contestedCorpus(svc)
        val edgeId = graph.contradictionEdges.keys.single()

        val updated = svc.confirmContradiction(edgeId).valueOrNull()!!
        assertEquals(0L, updated.counters[Stage3Counters.CONTRADICTION_QUEUE])
        assertEquals("CONFIRMED", graph.contradictionEdges[edgeId]!!.reviewStatus)
        // Confirm ratifies — no re-score happens, the penalty stands.
        assertEquals(1, graph.scoreOutcomes.size)

        // A second confirm is a conflict (not PROPOSED anymore).
        assertTrue(svc.confirmContradiction(edgeId).errorOrNull() is DomainError.Conflict)

        val published = svc.publish(run.id, skipReview = false, actor = "op").valueOrNull()!!
        assertEquals(Stage3RunStatus.PUBLISHED, published.status)
        assertFalse(published.reviewSkipped)
    }

    @Test
    fun `dismiss deletes the edge, overrides the cached verdicts and re-scores in-request`() {
        val svc = service()
        contestedCorpus(svc)
        val edgeId = graph.contradictionEdges.keys.single()
        val beliefBefore =
            graph.scoreOutcomes.last().facts.first { it.factId == "fact:c1" }.beliefBare

        val updated = svc.dismissContradiction(edgeId).valueOrNull()!!
        assertEquals(0L, updated.counters[Stage3Counters.CONTRADICTION_QUEUE])
        assertTrue(graph.contradictionEdges.isEmpty())
        // Every cached verdict variant behind the pair is permanently overridden.
        assertTrue(judgeEdges.store.values.all { it.overridden })
        // The incremental re-score ran and the penalty is gone: belief returns to the prior.
        assertEquals(2, graph.scoreOutcomes.size)
        val rescored = graph.scoreOutcomes.last().facts.first { it.factId == "fact:c1" }
        assertTrue(rescored.beliefBare > beliefBefore)
        assertEquals(rescored.belief, rescored.beliefBare, 1e-9)

        // Dismissing again: the edge is gone.
        assertTrue(svc.dismissContradiction(edgeId).errorOrNull() is DomainError.NotFound)
    }

    @Test
    fun `rejudge needs a sidecar, then re-judges with context and marks the edge explained`() {
        val svc = service()
        contestedCorpus(svc)
        val edgeId = graph.contradictionEdges.keys.single()

        // No sidecar authored yet — the hook refuses.
        val refused = svc.rejudgeContradiction(edgeId).errorOrNull()
        assertTrue(refused is DomainError.Conflict)
        assertTrue(refused!!.message.contains("sidecar"))

        // The operator authors the §12.6 justification on c1, then calls the hook.
        reviews.store["c1"] =
            ClaimReview(
                claimId = "c1",
                subjectId = subjectId,
                decision = ReviewDecision.SIDECARED,
                justification = "I led the backend workstream; Vikram was program lead",
                reviewedBy = "op",
                reviewedAt = Instant.now(),
            )
        val callsBefore = judgeSampler.calls
        val updated = svc.rejudgeContradiction(edgeId).valueOrNull()!!
        assertTrue(judgeSampler.calls > callsBefore) // the ctx ensemble actually ran
        val edge = graph.contradictionEdges[edgeId]!!
        assertTrue(edge.withContext)
        assertTrue(edge.explained) // scripted relevance affirms → leaves the queue
        assertEquals("CONTRADICTS", edge.ctxRelation)
        assertEquals(0L, updated.counters[Stage3Counters.CONTRADICTION_QUEUE])
        // Explained pass now μ-mitigates: published score recovers above bare.
        val rescored = graph.scoreOutcomes.last().facts.first { it.factId == "fact:c1" }
        assertTrue(rescored.belief > rescored.beliefBare)
    }

    @Test
    fun `a crash mid-publishing resumes via retry and poll without duplicate writes`() {
        val svc = service()
        val run = contestedCorpus(svc)
        claims.failPublish = true
        var failed = svc.publish(run.id, skipReview = true, actor = "op").valueOrNull()!!
        assertEquals(Stage3RunStatus.FAILED, failed.status)
        assertEquals(Stage3RunStatus.PUBLISHING, failed.failedPhase)
        assertTrue(failed.error!!.contains("ledger batch write failed"))
        assertTrue(claims.published.isEmpty())

        claims.failPublish = false
        var resumed = svc.retry(failed.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.PUBLISHING, resumed.status)
        resumed = svc.poll(resumed.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.PUBLISHED, resumed.status)
        assertEquals(2, claims.published.size) // both claims written exactly once
        assertEquals(setOf("c1", "c2"), claims.published.map { it.claimId }.toSet())
    }

    @Test
    fun `admin reopen returns to the gate and a second publish overwrites the ledger stamp`() {
        val svc = service()
        val run = contestedCorpus(svc)
        val first = svc.publish(run.id, skipReview = true, actor = "op").valueOrNull()!!
        val firstPublishedAt = first.publishedAt!!

        // Reopen requires PUBLISHED; a pending run cannot reopen.
        assertTrue(svc.reopen("run-does-not-exist").errorOrNull() is DomainError.NotFound)
        val reopened = svc.reopen(first.id).valueOrNull()!!
        assertEquals(Stage3RunStatus.AWAITING_REVIEW, reopened.status)
        assertNull(reopened.finishedAt)
        // The ledger keeps the last-published values while reopened (§15 #12).
        assertEquals(first.id, claims.store["c1"]!!.scoreRunId)

        val second = svc.publish(reopened.id, skipReview = true, actor = "op2").valueOrNull()!!
        assertEquals(Stage3RunStatus.PUBLISHED, second.status)
        assertEquals("op2", second.publishedBy)
        assertTrue(second.publishedAt!! >= firstPublishedAt)
        assertEquals(4, claims.published.size) // two publishes × two claims
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

package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.liveConfig
import ai.vishwakarma.labelling.persistence.Stage3EdgeRepository
import ai.vishwakarma.labelling.persistence.Stage3EdgeVerdict
import com.google.cloud.firestore.Firestore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.neo4j.driver.Driver

/**
 * VA-19 acceptance: the §11.12 sample corpus walks the REAL offline pipeline — pseudo-embeddings →
 * the real [EntityResolver] → the real [ClaimMatcher] cascade → [ClaimJudgeService] over the
 * scripted [DryRunJudgeSampler] (through the real [JudgeAggregator]) → [FactAssembler] → [Scorer] —
 * and reproduces the LLD §11.8 worked-example numbers, exercising every §11 branch on the way.
 * Blocking arms are computed here exactly as the Cypher does (kNN = pairwise cosine ≥ sim-floor;
 * co-mention = shared discriminative entity under the `mentioners ≤ (1 − idf-floor) × total` rule);
 * everything downstream of blocking is the production code itself.
 */
class DryRunStage3CorpusTest {

    private val props = AppProperties()
    private val s3 = props.stage3
    private val embeddings = PseudoEmbeddingService(s3.embeddingDimensions)
    private val corpus = DryRunStage3Corpus.claims.associateBy { it.claimId }

    // ---- shared pipeline walk (deterministic; recomputed per call for the I5 assertion) ----

    private class InMemoryEdgeRepo : Stage3EdgeRepository(mock(Firestore::class.java)) {
        val store = linkedMapOf<String, Stage3EdgeVerdict>()

        override fun findAll(ids: Collection<String>): Map<String, Stage3EdgeVerdict> =
            ids.mapNotNull { store[it] }.associateBy { it.id }

        override fun saveAll(rows: List<Stage3EdgeVerdict>) {
            rows.forEach { store[it.id] = it }
        }
    }

    private class CorpusEntityGraph(props: AppProperties) :
        Stage3GraphRepository(mock(Driver::class.java), liveConfig(props)) {

        data class Stored(
            val ref: EntityRef,
            val embedding: List<Double>,
            val aliasKeys: MutableSet<String> = mutableSetOf(),
            val aliases: MutableSet<String> = mutableSetOf(),
        )

        val store = mutableListOf<Stored>()
        val links = mutableListOf<MentionLinkRow>()
        val issuers = linkedMapOf<String, String>()

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
                        score = cosine(it.embedding, embedding),
                    )
                }
                .sortedByDescending { it.score }
                .take(k)

        override fun applyEntityResolution(subjectId: String, write: EntityResolutionWrite) {
            write.mints.forEach { mint ->
                store +=
                    Stored(
                        EntityRef(
                            mint.entityId,
                            mint.entityType,
                            mint.canonicalKey,
                            mint.canonicalName,
                            null,
                        ),
                        mint.embedding,
                    )
            }
            write.aliasAppends.forEach { row ->
                store
                    .firstOrNull {
                        it.ref.entityType == row.entityType &&
                            it.ref.canonicalKey == row.canonicalKey
                    }
                    ?.let {
                        it.aliasKeys += row.aliasKey
                        it.aliases += row.surface
                    }
            }
            links += write.links
            write.sourceIssuers.forEach { issuers[it.assetId] = it.issuerTypeAndKey }
        }
    }

    private data class Walk(
        val graph: CorpusEntityGraph,
        val resolution: ResolutionOutcome,
        val candidates: Map<ClaimPair, Set<BlockSource>>,
        val match: MatchOutcome,
        val judgeOutcome: JudgeTickOutcome,
        val assembly: AssembleOutcome,
        val score: ScoreOutcome,
        val scoreSnapshot: GraphSnapshot,
    )

    private fun claimText(c: CorpusClaim): String =
        ClaimToEmbed(c.claimId, c.type, c.text, c.claimedDate).embeddingText()

    private fun assetIdOf(c: CorpusClaim): String =
        DryRunStage3Corpus.assets
            .first { it.key == c.assetKey }
            .assetId(DryRunStage3Corpus.SUBJECT_ID)

    /** SELF → the subject; DOCUMENTARY → per-asset issuer; else per-asset endorser (§11.2). */
    private fun attestorKeyOf(c: CorpusClaim): String {
        val asset = DryRunStage3Corpus.assets.first { it.key == c.assetKey }
        val sourceClass = sourceClassOf(c)
        return when (sourceClass) {
            "SELF" -> "subject:${DryRunStage3Corpus.SUBJECT_ID}"
            "DOCUMENTARY" -> "issuer:asset:${assetIdOf(c)}"
            else -> "endorser:asset:${assetIdOf(c)}:${asset.relationship}"
        }
    }

    private fun sourceClassOf(c: CorpusClaim): String =
        ai.vishwakarma.labelling.domain.ContentType.valueOf(
                DryRunStage3Corpus.assets.first { it.key == c.assetKey }.contentType
            )
            .sourceClass
            .name

    private fun trustPriorOf(attestorKey: String): Double =
        when {
            attestorKey.startsWith("subject:") -> 0.50
            attestorKey.startsWith("issuer:") -> 0.85
            attestorKey.endsWith("MANAGER") -> 0.65
            else -> 0.60
        }

    private fun walk(): Walk {
        val claims = DryRunStage3Corpus.claims

        // RESOLVE_ENTITIES: the real resolver over the real canned extractor, one batch.
        val graph = CorpusEntityGraph(props)
        val resolver = EntityResolver(graph, embeddings, liveConfig(props))
        val toResolve =
            claims.map {
                ClaimToResolve(it.claimId, it.type, it.text, sourceClassOf(it), assetIdOf(it))
            }
        val extracted = DryRunEntityMentionExtractor().extract(toResolve)
        val resolution =
            resolver.resolve(DryRunStage3Corpus.SUBJECT_ID, toResolve, extracted, "dryrun:1")

        // EMBED + blocking arms, replicating the Cypher over pseudo-vectors.
        val vectors =
            claims.associate {
                it.claimId to embeddings.embed(claimText(it), EmbeddingTaskType.SEMANTIC_SIMILARITY)
            }
        val allPairs = buildList {
            val ids = claims.map { it.claimId }.sorted()
            for (i in ids.indices) for (j in i + 1 until ids.size) add(ClaimPair(ids[i], ids[j]))
        }
        val sims = allPairs.associateWith { cosine(vectors.getValue(it.a), vectors.getValue(it.b)) }
        val knn =
            allPairs
                .filter { sims.getValue(it) >= s3.simFloor }
                .map { ScoredPair(it, sims.getValue(it)) }
        val total = claims.size
        val discriminative =
            graph.links
                .groupBy { "${it.entityType}|${it.canonicalKey}" }
                .filterValues { rows ->
                    rows.map { it.claimId }.distinct().size <= (1.0 - s3.entityIdfFloor) * total
                }
        val coMention =
            discriminative.values
                .flatMap { rows ->
                    val ids = rows.map { it.claimId }.distinct().sorted()
                    buildList {
                        for (i in ids.indices) for (j in i + 1 until ids.size) add(
                            ClaimPair(ids[i], ids[j])
                        )
                    }
                }
                .distinct()

        // MATCH: the real cascade.
        val toMatch =
            claims.map {
                ClaimToMatch(
                    claimId = it.claimId,
                    type = it.type,
                    text = it.text,
                    claimedDate = it.claimedDate,
                    assetId = assetIdOf(it),
                    explained = it.sidecar != null,
                    exemplarClaimId = null,
                )
            }
        val candidates = ClaimMatcher.candidates(toMatch, knn, coMention, emptyList(), s3)
        val match = ClaimMatcher.cascade(toMatch, candidates, sims, s3)

        // JUDGE: the real cache-first ensemble over the scripted dry-run sampler.
        val judge = ClaimJudgeService(DryRunJudgeSampler(), InMemoryEdgeRepo(), liveConfig(props))
        val pairsToJudge =
            match.queue.map { entry ->
                PairToJudge(
                    pair = entry.pair,
                    rank = entry.rank.toLong(),
                    withContext = entry.withContext,
                    humanAsserted = entry.humanAsserted,
                    a = cardOf(entry.pair.a),
                    b = cardOf(entry.pair.b),
                    sharedEntities = emptyList(),
                )
            }
        val judgeOutcome = judge.judgePairs(DryRunStage3Corpus.SUBJECT_ID, pairsToJudge)

        // ASSEMBLE: the real union-find + gate + lift.
        val repeats =
            match.autoRepeats.map { it.pair } +
                judgeOutcome.judged
                    .filter { it.bare.relation == JudgeRelation.REPEATS }
                    .map { it.pair }
        val records =
            judgeOutcome.judged.map { p ->
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
        val toAssemble =
            claims.map { c ->
                ClaimToAssemble(
                    claimId = c.claimId,
                    type = c.type,
                    text = c.text,
                    claimedDate = c.claimedDate,
                    sourceClass = sourceClassOf(c),
                    mentionTypes =
                        graph.links
                            .filter { it.claimId == c.claimId }
                            .map { it.entityType }
                            .sorted(),
                )
            }
        val assembly = FactAssembler.assemble(toAssemble, repeats, records, s3)

        // SCORE: the real dual-pass fixed point at the fixture's tightened ε (§11.8 as-built).
        val factByClaim =
            assembly.facts.flatMap { f -> f.memberClaimIds.map { it to f.factId } }.toMap()
        val attestorKeys = claims.associate { it.claimId to attestorKeyOf(it) }
        val snapshot =
            GraphSnapshot(
                claims =
                    claims.map { c ->
                        ClaimSnapshot(
                            claimId = c.claimId,
                            factId = factByClaim.getValue(c.claimId),
                            type = c.type,
                            tierSeed = c.tierSeed,
                            sourceClass = sourceClassOf(c),
                            basis = "STATED",
                            favorability = c.favorability,
                            claimedDate = c.claimedDate,
                            attestorKey = attestorKeys.getValue(c.claimId),
                            assetId = assetIdOf(c),
                        )
                    },
                facts =
                    assembly.facts.map {
                        FactSnapshot(it.factId, it.factKind, it.exemplarClaimId, it.anchored)
                    },
                attestors =
                    attestorKeys.values.distinct().map {
                        AttestorSnapshot(it, trustPriorOf(it), trustPriorOf(it))
                    },
                edges =
                    assembly.edges.map {
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
                    },
            )
        val score =
            Scorer.score(
                snapshot,
                ScorerParams(
                    stage3 = propsWithEpsilon(0.001).stage3,
                    favorabilityThreshold = props.stage2.favorabilityThreshold,
                    asOf = AS_OF,
                ),
            )
        return Walk(graph, resolution, candidates, match, judgeOutcome, assembly, score, snapshot)
    }

    private fun propsWithEpsilon(epsilon: Double): AppProperties =
        AppProperties(stage3 = s3.copy(epsilon = epsilon))

    private fun cardOf(claimId: String): ClaimCard {
        val c = corpus.getValue(claimId)
        return ClaimCard(
            claimId = claimId,
            text = c.text,
            type = c.type,
            claimedDate = c.claimedDate,
            sourceClass = sourceClassOf(c),
            relationship = null,
            speakerRole = null,
            explanationText = c.sidecar,
        )
    }

    private fun factOf(walk: Walk, claimId: String): AssembledFact =
        walk.assembly.facts.first { claimId in it.memberClaimIds }

    private fun beliefOf(walk: Walk, claimId: String): Pair<Double, Double> {
        val fact = factOf(walk, claimId)
        val score = walk.score.facts.first { it.factId == fact.factId }
        return score.belief to score.beliefBare
    }

    // ---- pinned pseudo-cosines (the corpus's load-bearing tuning) --------------------------

    @Test
    fun `corpus surfaces land in their tuned similarity bands`() {
        fun surfaceCos(a: String, b: String) =
            cosine(
                embeddings.embed(a, EmbeddingTaskType.CLASSIFICATION),
                embeddings.embed(b, EmbeddingTaskType.CLASSIFICATION),
            )
        // Confident kNN merge (≥ entity-merge-threshold 0.85).
        assertTrue(surfaceCos("payments migration", "the payments migration") >= 0.85)
        // The §11.3 near-miss band [0.75, 0.85).
        val band = surfaceCos("AWS Solutions Architect", "AWS Solutions Architect Associate")
        assertTrue(band >= 0.75 && band < 0.85, "expected review band, got $band")

        fun claimCos(a: String, b: String) =
            cosine(
                embeddings.embed(
                    claimText(corpus.getValue(a)),
                    EmbeddingTaskType.SEMANTIC_SIMILARITY,
                ),
                embeddings.embed(
                    claimText(corpus.getValue(b)),
                    EmbeddingTaskType.SEMANTIC_SIMILARITY,
                ),
            )
        // Rung-3 τ_high auto-REPEATS.
        assertTrue(claimCos("asha-c08", "asha-c09") >= s3.simAutoRepeat)
        // Judged pairs stay below τ_high (they must reach the ensemble).
        assertTrue(claimCos("asha-c01", "asha-c02") < s3.simAutoRepeat)
        assertTrue(claimCos("asha-c05", "asha-c06") < s3.simAutoRepeat)
        assertTrue(claimCos("asha-c12", "asha-c13") in s3.simFloor..s3.simAutoRepeat)
        // The Java type-scoping pair never blocks.
        assertTrue(claimCos("asha-c16", "asha-c17") < s3.simFloor)
    }

    @Test
    fun `every corpus claim resolves its canned mentions by marker`() {
        DryRunStage3Corpus.claims.forEach { c ->
            val extracted = DryRunStage3Corpus.mentionsFor(c.text)
            assertNotNull(extracted, "no mention row matched ${c.claimId}")
            assertEquals(c.mentions, extracted.mentions, "wrong row matched ${c.claimId}")
        }
        assertNull(DryRunStage3Corpus.mentionsFor("Some unrelated non-corpus claim."))
    }

    // ---- resolution branches (§11.3) -------------------------------------------------------

    @Test
    fun `resolution merges variants, review-lists the near miss, and scopes types`() {
        val walk = walk()
        val entities = walk.graph.store

        // Alias variants: "the payments migration" merged into c05's mint and adopted as alias.
        val project = entities.filter { it.ref.entityType == "PROJECT" }
        assertEquals(1, project.size)
        assertTrue("the payments migration" in project.single().aliases)

        // Near-miss band: the Associate credential provisionally linked, not aliased, listed.
        val credentials = entities.filter { it.ref.entityType == "CREDENTIAL" }
        assertEquals(1, credentials.size, "the Associate surface must not mint")
        val provisional =
            walk.graph.links.single { it.claimId == "asha-c11" && it.entityType == "CREDENTIAL" }
        assertTrue(provisional.provisional)
        assertEquals(1, walk.resolution.reviewListed)
        assertTrue("aws solutions architect associate" !in credentials.single().aliasKeys)

        // Type scoping: two "java" canon nodes, one per type.
        val javas = entities.filter { it.ref.canonicalKey == "java" }
        assertEquals(setOf("SKILL", "PLACE"), javas.map { it.ref.entityType }.toSet())

        // Normalization alias: "R. Mehta" and "R Mehta" share one PERSON node.
        val mehta =
            entities.filter { it.ref.entityType == "PERSON" && it.ref.canonicalKey == "r mehta" }
        assertEquals(1, mehta.size)
        assertEquals(
            2,
            walk.graph.links.count { it.entityType == "PERSON" && it.canonicalKey == "r mehta" },
        )

        // Issuer hooks stamped for both documentary assets (§18.2 Q2).
        assertEquals(2, walk.graph.issuers.size)
        assertTrue(
            walk.graph.issuers.values.any { it.startsWith("INSTITUTION|") } &&
                walk.graph.issuers.values.any { it.startsWith("ORG|") }
        )
    }

    // ---- blocking + cascade branches (§11.5) ------------------------------------------------

    @Test
    fun `cascade auto-resolves the dupe and paraphrase and never pairs the java claims`() {
        val walk = walk()
        val autoByPair = walk.match.autoRepeats.associateBy { it.pair }

        // Rung 2: same-source normalization-identical dupe.
        assertEquals(2, autoByPair.getValue(ClaimPair.of("asha-c04", "asha-c07")).rung)
        // Rung 3: τ_high paraphrase across assets.
        assertEquals(3, autoByPair.getValue(ClaimPair.of("asha-c08", "asha-c09")).rung)
        // The IDF floor keeps the subject's own PERSON node out of blocking: no candidate pair
        // may exist whose only shared entity is "Asha" — the java pair is the canary.
        assertFalse(ClaimPair.of("asha-c16", "asha-c17") in walk.candidates)
        // The sidecared claim queues its pair with the §11.9 dual flag.
        val queued = walk.match.queue.first { it.pair == ClaimPair.of("asha-c05", "asha-c06") }
        assertTrue(queued.withContext)
    }

    // ---- judged verdicts through the real aggregator (§11.6) ---------------------------------

    @Test
    fun `scripted samples aggregate to the worked-example verdicts`() {
        val walk = walk()
        val byPair = walk.judgeOutcome.judged.associateBy { it.pair }

        fun verdict(a: String, b: String) = byPair.getValue(ClaimPair.of(a, b))

        val education = verdict("asha-c01", "asha-c02").bare
        assertEquals(JudgeRelation.REPEATS, education.relation)
        assertEquals(mapOf("REPEATS" to 5), education.votes)
        assertEquals(0.9, education.confidence, 1e-6)

        val kotlin = verdict("asha-c03", "asha-c04").bare
        assertEquals(JudgeRelation.REPEATS, kotlin.relation)
        assertEquals(4, kotlin.votes["REPEATS"])
        assertEquals(0.72, kotlin.confidence, 1e-6)

        val migration = verdict("asha-c05", "asha-c06")
        assertEquals(JudgeRelation.CONTRADICTS, migration.bare.relation)
        assertEquals(0.72, migration.bare.confidence, 1e-6)
        val ctx = migration.ctx
        assertNotNull(ctx, "the sidecared pair must judge a ctx variant")
        assertEquals(JudgeRelation.NEUTRAL, ctx.relation, "0.36 < floor collapses to NEUTRAL")
        assertTrue(ctx.floored)
        assertEquals(0.36, ctx.confidence, 1e-6)
        assertTrue(ctx.explanationRelevant)

        val aws = verdict("asha-c10", "asha-c11").bare
        assertEquals(JudgeRelation.CORROBORATES, aws.relation)
        assertEquals(0.64, aws.confidence, 1e-6)

        val employers = verdict("asha-c12", "asha-c13").bare
        assertEquals(JudgeRelation.CONTRADICTS, employers.relation)
        assertNotNull(employers.temporalNote)

        val german = verdict("asha-c14", "asha-c15").bare
        assertEquals(JudgeRelation.NEUTRAL, german.relation, "tie resolves by NEUTRAL precedence")
        assertTrue(german.tie)
        assertTrue(walk.judgeOutcome.ties >= 1)
    }

    // ---- assembly branches (§11.7) -----------------------------------------------------------

    @Test
    fun `assembly reproduces the worked-example facts and gates the disjoint employers`() {
        val walk = walk()

        assertEquals(15, walk.assembly.facts.size)
        assertEquals(
            listOf("asha-c03", "asha-c04", "asha-c07"),
            factOf(walk, "asha-c03").memberClaimIds.sorted(),
        )
        assertEquals("asha-c03", factOf(walk, "asha-c03").exemplarClaimId)
        assertEquals("EVENT", factOf(walk, "asha-c01").factKind)
        assertTrue(factOf(walk, "asha-c01").anchored)

        // The overlapping-2019 contradiction survives the gate, explained by the ctx verdict
        // (edges lift low→high fact id; the scorer reads them symmetrically).
        val contradiction = walk.assembly.edges.single { it.relation == "CONTRADICTS" }
        assertEquals(factOf(walk, "asha-c05").factId, contradiction.fromFactId)
        assertEquals(factOf(walk, "asha-c06").factId, contradiction.toFactId)
        assertTrue(contradiction.explained)
        assertEquals(0.72, contradiction.confidence, 1e-6)
        assertEquals(true, contradiction.temporalOverlap)

        // The disjoint employers were gated into the EMPLOYER SUCCEEDS chain instead.
        assertEquals(1L, walk.assembly.counters.contradictionsGated)
        val succeeds = walk.assembly.succeeds.single()
        assertEquals(factOf(walk, "asha-c12").factId, succeeds.fromFactId)
        assertEquals(factOf(walk, "asha-c13").factId, succeeds.toFactId)
        assertEquals("EMPLOYER", succeeds.slot)

        val corroborates = walk.assembly.edges.single { it.relation == "CORROBORATES" }
        assertEquals(factOf(walk, "asha-c10").factId, corroborates.fromFactId)
        assertEquals(factOf(walk, "asha-c11").factId, corroborates.toFactId)
    }

    // ---- the §11.8 worked-example numbers -----------------------------------------------------

    @Test
    fun `scores reproduce the worked example and the queue is empty at the gate`() {
        val walk = walk()

        val (f1, f1Bare) = beliefOf(walk, "asha-c01")
        assertEquals(0.90, f1, 0.005)
        assertEquals(f1, f1Bare, 1e-9)

        val (f2, _) = beliefOf(walk, "asha-c03")
        assertEquals(0.74, f2, 0.005)

        val (f3, f3Bare) = beliefOf(walk, "asha-c05")
        assertEquals(0.31, f3Bare, 0.005)
        assertEquals(0.35, f3, 0.005)
        assertTrue(f3 > f3Bare, "the explained pass must recover the sidecar delta")

        val (f4, _) = beliefOf(walk, "asha-c06")
        assertEquals(0.60, f4, 0.005)

        assertTrue(walk.score.converged == true || walk.score.iterations <= s3.maxIterations)
        assertEquals(0L, walk.score.i2Clamped)

        // The Q6 gate is immediately publishable: the only CONTRADICTS edge is explained.
        val queueSize =
            walk.assembly.edges.count {
                it.relation == "CONTRADICTS" &&
                    it.reviewStatus == "PROPOSED" &&
                    !it.explained &&
                    it.confidence >= s3.judgeConfidenceFloor
            }
        assertEquals(0, queueSize)
    }

    // ---- determinism (§11.12 / I5) ------------------------------------------------------------

    @Test
    fun `two walks from the same inputs are bit-identical`() {
        val first = walk()
        val second = walk()
        assertEquals(first.match.queue, second.match.queue)
        assertEquals(first.assembly.facts, second.assembly.facts)
        assertEquals(first.assembly.edges, second.assembly.edges)
        assertEquals(
            first.score.claims.map { it.claimId to it.score },
            second.score.claims.map { it.claimId to it.score },
        )
        assertEquals(
            first.score.facts.map { it.factId to it.belief },
            second.score.facts.map { it.factId to it.belief },
        )
    }

    // ---- per-leg dry-run flags (§11.12 mix-and-match) -----------------------------------------

    @Test
    fun `per-leg overrides follow the master flag unless set`() {
        val master = AppProperties.Stage3(dryRun = true)
        assertTrue(master.embeddingsDryRun)
        assertTrue(master.extractionDryRun)
        assertTrue(master.judgeDryRun)

        val liveSmoke = AppProperties.Stage3(dryRun = true, dryRunJudge = false)
        assertTrue(liveSmoke.embeddingsDryRun)
        assertTrue(liveSmoke.extractionDryRun)
        assertFalse(liveSmoke.judgeDryRun)

        val realDefault = AppProperties.Stage3()
        assertFalse(realDefault.embeddingsDryRun)
        assertFalse(realDefault.extractionDryRun)
        assertFalse(realDefault.judgeDryRun)
    }

    private companion object {
        /** Fixed clock (I5: wall time is a parameter, never sampled). */
        val AS_OF: LocalDate = LocalDate.of(2026, 7, 9)
    }
}

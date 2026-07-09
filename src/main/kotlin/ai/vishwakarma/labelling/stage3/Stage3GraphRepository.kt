package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository

/**
 * Result of the connectivity probe — the run-submit guard and the diagnostic endpoint's payload.
 */
data class GraphPing(
    val reachable: Boolean,
    val latencyMs: Long? = null,
    /** From `dbms.components()` when reachable: e.g. "Neo4j Kernel 5.26.0 enterprise". */
    val server: String? = null,
    val database: String? = null,
    val error: String? = null,
)

/** What [Stage3GraphRepository.ensureSchema] applied (logged once; surfaced by the diagnostic). */
data class SchemaStatus(
    /** ENTERPRISE = composite NODE KEY on :Entity; COMMUNITY = concatenated-key UNIQUE fallback. */
    val entityKeyForm: String,
    val vectorIndexDimensions: Int,
    /** True when a dims change forced dropping + recreating the vector indexes. */
    val vectorIndexesRebuilt: Boolean,
)

/** A claim awaiting embedding — the EMBED phase's work unit (LLD §11.4). */
data class ClaimToEmbed(
    val claimId: String,
    val type: String?,
    val text: String,
    val claimedDate: String?,
)

/**
 * All Neo4j access for Stage 3 — plain driver + hand-written Cypher (LLD §8.1: no OGM; the access
 * pattern is MERGE/MATCH + vector queries). Three structural rules this class enforces:
 * 1. **Privacy scoping is structural**: every evidence-layer read/write takes `subjectId` and its
 *    Cypher is `subjectId`-bound — cross-subject isolation is not caller discipline (LLD §9.1).
 *    Ontology/trust-layer writes (`:Entity`, `:Attestor`) are global by design and carry no
 *    subjectId and no claim text.
 * 2. **Managed transactions everywhere** (`executeRead`/`executeWrite`): the driver transparently
 *    retries SessionExpired / ServiceUnavailable / transient failures — the AuraDB idle-drop
 *    defense (see `Neo4jConfig`). Only schema DDL uses auto-commit transactions (schema commands
 *    must run outside data transactions).
 * 3. **Idempotency**: everything is MERGE by natural key; re-running any phase is a no-op.
 */
@Repository
class Stage3GraphRepository(private val driver: Driver, private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(Stage3GraphRepository::class.java)

    private fun sessionConfig(): SessionConfig =
        SessionConfig.forDatabase(props.stage3.neo4jDatabase)

    // ---- connectivity --------------------------------------------------------

    /** `RETURN 1` probe — never throws; the guard/diagnostic reads the error off the result. */
    fun ping(): GraphPing =
        try {
            val start = System.nanoTime()
            driver.session(sessionConfig()).use { it.run("RETURN 1").consume() }
            val latency = (System.nanoTime() - start) / 1_000_000
            val server =
                runCatching {
                        driver.session(sessionConfig()).use { s ->
                            s.executeRead { tx ->
                                tx.run(
                                        "CALL dbms.components() YIELD name, versions, edition " +
                                            "RETURN name + ' ' + versions[0] + ' ' + edition AS server"
                                    )
                                    .single()["server"]
                                    .asString()
                            }
                        }
                    }
                    .getOrNull()
            GraphPing(
                reachable = true,
                latencyMs = latency,
                server = server,
                database = props.stage3.neo4jDatabase,
            )
        } catch (e: Exception) {
            GraphPing(
                reachable = false,
                database = props.stage3.neo4jDatabase,
                error = "${e.message}"
            )
        }

    // ---- schema (LLD §21 A.1) -------------------------------------------------

    @Volatile private var schemaEnsured: SchemaStatus? = null

    /**
     * Idempotent DDL: uniqueness constraints, subject indexes, and the two vector indexes. Called
     * best-effort at startup and re-checked at run submit (memoized after first success). A dims
     * config change drops + recreates the vector indexes (LLD §15 #6 — vector spaces never mix; the
     * EMBED staleness stamp then re-embeds every claim).
     *
     * `:Entity` gets the Enterprise composite NODE KEY `(entityType, canonicalKey)`; on editions/
     * tiers that reject it (Community local Docker; LLD §18.2 Q7 wants Aura Free verified at first
     * contact) it degrades to a UNIQUE constraint on the concatenated `typeAndKey` property —
     * entity writers must therefore always set `typeAndKey = entityType + '|' + canonicalKey`.
     */
    fun ensureSchema(): SchemaStatus {
        schemaEnsured?.let {
            return it
        }
        driver.session(sessionConfig()).use { session ->
            // Schema commands run in their own auto-commit transactions — they may not share a
            // transaction with data statements, and DDL is not retryable work anyway.
            listOf(
                    "CREATE CONSTRAINT claim_id IF NOT EXISTS FOR (c:Claim) REQUIRE c.claimId IS UNIQUE",
                    "CREATE CONSTRAINT fact_id IF NOT EXISTS FOR (f:Fact) REQUIRE f.factId IS UNIQUE",
                    "CREATE CONSTRAINT source_id IF NOT EXISTS FOR (s:Source) REQUIRE s.assetId IS UNIQUE",
                    "CREATE CONSTRAINT expl_id IF NOT EXISTS FOR (e:Explanation) REQUIRE e.explanationId IS UNIQUE",
                    "CREATE CONSTRAINT attestor_key IF NOT EXISTS FOR (a:Attestor) REQUIRE a.attestorKey IS UNIQUE",
                    "CREATE INDEX claim_subject IF NOT EXISTS FOR (c:Claim) ON (c.subjectId)",
                    "CREATE INDEX fact_subject IF NOT EXISTS FOR (f:Fact) ON (f.subjectId)",
                )
                .forEach { session.run(it).consume() }

            val entityKeyForm =
                try {
                    session
                        .run(
                            "CREATE CONSTRAINT entity_key IF NOT EXISTS FOR (e:Entity) " +
                                "REQUIRE (e.entityType, e.canonicalKey) IS NODE KEY"
                        )
                        .consume()
                    "ENTERPRISE"
                } catch (e: Exception) {
                    log.info(
                        "NODE KEY rejected ({}); using Community fallback constraint on typeAndKey",
                        e.message,
                    )
                    session
                        .run(
                            "CREATE CONSTRAINT entity_key IF NOT EXISTS FOR (e:Entity) " +
                                "REQUIRE e.typeAndKey IS UNIQUE"
                        )
                        .consume()
                    "COMMUNITY"
                }

            val dims = props.stage3.embeddingDimensions
            var rebuilt = false
            listOf("claim_embedding" to "Claim", "entity_embedding" to "Entity").forEach {
                (name, label) ->
                rebuilt = ensureVectorIndex(session, name, label, dims) || rebuilt
            }

            val status =
                SchemaStatus(
                    entityKeyForm = entityKeyForm,
                    vectorIndexDimensions = dims,
                    vectorIndexesRebuilt = rebuilt,
                )
            log.info(
                "Stage 3 graph schema ensured: entity key {}, vector indexes {} dims{}",
                status.entityKeyForm,
                dims,
                if (rebuilt) " (rebuilt after dims change)" else "",
            )
            schemaEnsured = status
            return status
        }
    }

    /** Creates the vector index, dropping a dims-mismatched predecessor first. True = rebuilt. */
    private fun ensureVectorIndex(
        session: org.neo4j.driver.Session,
        name: String,
        label: String,
        dims: Int,
    ): Boolean {
        val existingDims =
            session
                .run("SHOW INDEXES YIELD name, options WHERE name = '$name' RETURN options")
                .list()
                .firstOrNull()
                ?.get("options")
                ?.asMap()
                ?.let { (it["indexConfig"] as? Map<*, *>)?.get("vector.dimensions") as? Long }
        val rebuilt =
            if (existingDims != null && existingDims.toInt() != dims) {
                log.warn(
                    "Vector index {} has {} dims, config wants {} — dropping and recreating " +
                        "(stored vectors go stale; EMBED re-embeds on next run)",
                    name,
                    existingDims,
                    dims,
                )
                session.run("DROP INDEX $name").consume()
                true
            } else false
        // Dims interpolated, not parameterized: index DDL OPTIONS maps take literals only.
        session
            .run(
                "CREATE VECTOR INDEX $name IF NOT EXISTS FOR (n:$label) ON (n.embedding) " +
                    "OPTIONS {indexConfig: {`vector.dimensions`: $dims, " +
                    "`vector.similarity_function`: 'cosine'}}"
            )
            .consume()
        return rebuilt
    }

    // ---- layer-boundary guards (LLD §21 A.4, verbatim) -------------------------

    /**
     * The privacy-boundary assertions — every count must be 0. Run by tests, the diagnostic
     * endpoint, and any CI smoke: evidence nodes always carry subjectId; evidence edges never cross
     * subjects; global-layer nodes never carry claim text.
     */
    fun layerGuardViolations(): Map<String, Long> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                mapOf(
                    "evidenceNodeWithoutSubjectId" to
                        tx.run(
                                "MATCH (n) WHERE any(l IN labels(n) WHERE l IN " +
                                    "['Claim','Fact','Source','Explanation']) AND n.subjectId IS NULL " +
                                    "RETURN count(n) AS c"
                            )
                            .single()["c"]
                            .asLong(),
                    "crossSubjectEvidenceEdge" to
                        tx.run(
                                "MATCH (f:Fact)-[r:CORROBORATES|CONTRADICTS|REPEATS|SUCCEEDS]-(g:Fact) " +
                                    "WHERE f.subjectId <> g.subjectId RETURN count(r) AS c"
                            )
                            .single()["c"]
                            .asLong(),
                    "claimTextOnGlobalLayer" to
                        tx.run("MATCH (e:Entity) WHERE e.text IS NOT NULL RETURN count(e) AS c")
                            .single()["c"]
                            .asLong(),
                )
            }
        }

    // ---- SYNC phase (LLD §11.2) ------------------------------------------------

    /**
     * `fresh` re-run support: detach-delete the subject's **evidence layer only** — never
     * `:Entity`/`:Attestor`, which are global and shared across subjects (attestor trust/counts
     * recompute lazily at scoring). Returns nodes deleted.
     */
    fun wipeEvidenceLayer(subjectId: String): Long =
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        "MATCH (n) WHERE n.subjectId = ${'$'}subjectId AND " +
                            "any(l IN labels(n) WHERE l IN ['Claim','Fact','Source','Explanation']) " +
                            "DETACH DELETE n",
                        mapOf("subjectId" to subjectId),
                    )
                    .consume()
                    .counters()
                    .nodesDeleted()
                    .toLong()
            }
        }

    /**
     * The §11.2 projection: idempotent MERGE of the reviewed claim set — claims + sources (evidence
     * layer, subjectId-stamped), attestors (global trust layer), explanations + EXPLAINS/CITES, and
     * the FROM / ATTESTED_BY / VOICED_BY wiring. One call per SYNC tick; batching happens inside
     * via UNWIND.
     */
    fun mergeEvidence(projection: EvidenceProjection) {
        val subjectId = projection.subjectId
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                    """
                    UNWIND ${'$'}rows AS row
                    MERGE (c:Claim {claimId: row.claimId})
                    SET c.subjectId = ${'$'}subjectId, c.assetId = row.assetId, c.type = row.type,
                        c.text = row.text, c.basis = row.basis, c.sourceClass = row.sourceClass,
                        c.relationship = row.relationship, c.speakerRole = row.speakerRole,
                        c.tierSeed = row.tierSeed, c.favorability = row.favorability,
                        c.claimedDate = row.claimedDate, c.sensitive = row.sensitive
                    """
                        .trimIndent(),
                    mapOf("subjectId" to subjectId, "rows" to projection.claims.map { it.toMap() }),
                )
                tx.run(
                    """
                    UNWIND ${'$'}rows AS row
                    MERGE (s:Source {assetId: row.assetId})
                    SET s.subjectId = ${'$'}subjectId, s.contentType = row.contentType,
                        s.sourceClass = row.sourceClass, s.relationship = row.relationship,
                        s.checksum = row.checksum
                    """
                        .trimIndent(),
                    mapOf(
                        "subjectId" to subjectId,
                        "rows" to projection.sources.map { it.toMap() }
                    ),
                )
                // Global trust layer: no subjectId on the node (LLD §9.1). Identity fields are
                // ON CREATE; trust/claimCount are scoring-owned and never reset by a re-sync.
                tx.run(
                    """
                    UNWIND ${'$'}rows AS row
                    MERGE (a:Attestor {attestorKey: row.attestorKey})
                    ON CREATE SET a.kind = row.kind, a.relationship = row.relationship,
                                  a.name = row.name, a.trustPrior = row.trustPrior,
                                  a.trust = row.trustPrior, a.claimCount = 0, a.subjectSpan = 0
                    ON MATCH SET a.name = coalesce(a.name, row.name)
                    """
                        .trimIndent(),
                    mapOf("rows" to projection.attestors.map { it.toMap() }),
                )
                tx.run(
                    """
                    UNWIND ${'$'}rows AS row
                    MATCH (c:Claim {claimId: row.claimId})
                    MATCH (s:Source {assetId: row.assetId})
                    MATCH (a:Attestor {attestorKey: row.attestorKey})
                    MERGE (c)-[:FROM]->(s)
                    MERGE (c)-[:ATTESTED_BY]->(a)
                    MERGE (s)-[:VOICED_BY]->(a)
                    """
                        .trimIndent(),
                    mapOf("rows" to projection.claims.map { it.toMap() }),
                )
                tx.run(
                    """
                    UNWIND ${'$'}rows AS row
                    MERGE (e:Explanation {explanationId: row.explanationId})
                    SET e.subjectId = ${'$'}subjectId, e.text = row.text, e.author = row.author,
                        e.createdAt = row.createdAt
                    WITH e, row
                    MATCH (c:Claim {claimId: row.claimId})
                    MERGE (e)-[:EXPLAINS]->(c)
                    WITH e, row
                    UNWIND row.cites AS cid
                    MATCH (t:Claim {claimId: cid})
                    MERGE (e)-[:CITES]->(t)
                    """
                        .trimIndent(),
                    mapOf(
                        "subjectId" to subjectId,
                        "rows" to projection.explanations.map { it.toMap() },
                    ),
                )
                Unit
            }
        }
    }

    // ---- EMBED phase (LLD §11.4) -------------------------------------------------

    /**
     * Claims whose vector is missing or from a different space ([EmbeddingService.versionStamp]
     * mismatch — model, dims, or dry-run flip). The graph itself is the phase cursor: finished
     * claims stop matching, so a killed poll resumes for free.
     */
    fun claimsNeedingEmbedding(
        subjectId: String,
        versionStamp: String,
        limit: Int
    ): List<ClaimToEmbed> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (c:Claim {subjectId: ${'$'}subjectId})
                        WHERE c.embedding IS NULL OR c.embeddingModelVersion <> ${'$'}stamp
                        RETURN c.claimId AS claimId, c.type AS type, c.text AS text,
                               c.claimedDate AS claimedDate
                        ORDER BY c.claimId LIMIT ${'$'}limit
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "stamp" to versionStamp, "limit" to limit),
                    )
                    .list { r ->
                        ClaimToEmbed(
                            claimId = r["claimId"].asString(),
                            type = r["type"].takeUnless { it.isNull }?.asString(),
                            text = r["text"].asString(""),
                            claimedDate = r["claimedDate"].takeUnless { it.isNull }?.asString(),
                        )
                    }
            }
        }

    fun countClaimsNeedingEmbedding(subjectId: String, versionStamp: String): Long =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        "MATCH (c:Claim {subjectId: ${'$'}subjectId}) " +
                            "WHERE c.embedding IS NULL OR c.embeddingModelVersion <> ${'$'}stamp " +
                            "RETURN count(c) AS c",
                        mapOf("subjectId" to subjectId, "stamp" to versionStamp),
                    )
                    .single()["c"]
                    .asLong()
            }
        }

    fun countClaims(subjectId: String): Long =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        "MATCH (c:Claim {subjectId: ${'$'}subjectId}) RETURN count(c) AS c",
                        mapOf("subjectId" to subjectId),
                    )
                    .single()["c"]
                    .asLong()
            }
        }

    /** Persist one embedded chunk (single UNWIND write — a failed chunk persists nothing). */
    fun setClaimEmbeddings(subjectId: String, rows: List<EmbeddedClaim>, versionStamp: String) {
        if (rows.isEmpty()) return
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        UNWIND ${'$'}rows AS row
                        MATCH (c:Claim {claimId: row.claimId})
                        WHERE c.subjectId = ${'$'}subjectId
                        SET c.embedding = row.embedding, c.embeddingModelVersion = ${'$'}stamp
                        """
                            .trimIndent(),
                        mapOf(
                            "subjectId" to subjectId,
                            "stamp" to versionStamp,
                            "rows" to
                                rows.map {
                                    mapOf("claimId" to it.claimId, "embedding" to it.embedding)
                                },
                        ),
                    )
                    .consume()
                Unit
            }
        }
    }
}

/** One embedded claim ready to persist. */
data class EmbeddedClaim(val claimId: String, val embedding: List<Double>)

/** Best-effort schema at boot — Neo4j down must never fail startup (VA-7 acceptance). */
@Component
class Stage3SchemaInitializer(private val graph: Stage3GraphRepository) {

    private val log = LoggerFactory.getLogger(Stage3SchemaInitializer::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        try {
            graph.ensureSchema()
        } catch (e: Exception) {
            log.warn(
                "Stage 3 graph schema not ensured at startup ({}) — retried at first run submit",
                e.message,
            )
        }
    }
}

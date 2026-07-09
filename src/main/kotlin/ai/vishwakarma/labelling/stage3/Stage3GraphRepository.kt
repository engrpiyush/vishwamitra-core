package ai.vishwakarma.labelling.stage3

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.serialization.Json
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

/** An `:Entity` as the exact-match lookup sees it ([mergedInto] ≠ null ⇒ tombstone redirect). */
data class EntityRef(
    val entityId: String,
    val entityType: String,
    val canonicalKey: String,
    val canonicalName: String,
    val mergedInto: String?,
)

/** One entity-kNN candidate (LLD §21 A.2) — tombstones already excluded. */
data class EntityCandidate(
    val entityId: String,
    val entityType: String,
    val canonicalKey: String,
    val canonicalName: String,
    val score: Double,
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

    // ---- RESOLVE_ENTITIES phase (LLD §11.3) ---------------------------------------

    /**
     * Claims whose mentions are unresolved or resolved under a different extractor stamp (prompt
     * version:hash / dry-run flip) — the graph-as-cursor idiom, mirroring [claimsNeedingEmbedding].
     */
    fun claimsNeedingEntityResolution(
        subjectId: String,
        versionStamp: String,
        limit: Int,
    ): List<ClaimToResolve> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (c:Claim {subjectId: ${'$'}subjectId})
                        WHERE c.entityResolutionStamp IS NULL
                           OR c.entityResolutionStamp <> ${'$'}stamp
                        RETURN c.claimId AS claimId, c.type AS type, c.text AS text,
                               c.sourceClass AS sourceClass, c.assetId AS assetId
                        ORDER BY c.claimId LIMIT ${'$'}limit
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "stamp" to versionStamp, "limit" to limit),
                    )
                    .list { r ->
                        ClaimToResolve(
                            claimId = r["claimId"].asString(),
                            type = r["type"].takeUnless { it.isNull }?.asString(),
                            text = r["text"].asString(""),
                            sourceClass = r["sourceClass"].takeUnless { it.isNull }?.asString(),
                            assetId = r["assetId"].takeUnless { it.isNull }?.asString(),
                        )
                    }
            }
        }

    fun countClaimsNeedingEntityResolution(subjectId: String, versionStamp: String): Long =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        "MATCH (c:Claim {subjectId: ${'$'}subjectId}) " +
                            "WHERE c.entityResolutionStamp IS NULL " +
                            "OR c.entityResolutionStamp <> ${'$'}stamp " +
                            "RETURN count(c) AS c",
                        mapOf("subjectId" to subjectId, "stamp" to versionStamp),
                    )
                    .single()["c"]
                    .asLong()
            }
        }

    /**
     * Exact-match leg of §11.3: `canonicalKey` first, adopted alias keys second, type-scoped,
     * global (deliberately not subject-scoped — the Q1 cross-subject canon). Tombstones are
     * returned as-is; the caller follows [EntityRef.mergedInto].
     */
    fun findEntityByKey(entityType: String, canonicalKey: String): EntityRef? =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (e:Entity {entityType: ${'$'}type})
                        WHERE e.canonicalKey = ${'$'}key
                           OR ${'$'}key IN coalesce(e.aliasKeys, [])
                        RETURN e.entityId AS entityId, e.entityType AS entityType,
                               e.canonicalKey AS canonicalKey, e.canonicalName AS canonicalName,
                               e.mergedInto AS mergedInto
                        ORDER BY CASE WHEN e.canonicalKey = ${'$'}key THEN 0 ELSE 1 END
                        LIMIT 1
                        """
                            .trimIndent(),
                        mapOf("type" to entityType, "key" to canonicalKey),
                    )
                    .list { it.toEntityRef() }
                    .firstOrNull()
            }
        }

    fun findEntityById(entityId: String): EntityRef? =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        "MATCH (e:Entity {entityId: ${'$'}id}) " +
                            "RETURN e.entityId AS entityId, e.entityType AS entityType, " +
                            "e.canonicalKey AS canonicalKey, e.canonicalName AS canonicalName, " +
                            "e.mergedInto AS mergedInto LIMIT 1",
                        mapOf("id" to entityId),
                    )
                    .list { it.toEntityRef() }
                    .firstOrNull()
            }
        }

    private fun org.neo4j.driver.Record.toEntityRef(): EntityRef =
        EntityRef(
            entityId = this["entityId"].asString(),
            entityType = this["entityType"].asString(),
            canonicalKey = this["canonicalKey"].asString(),
            canonicalName = this["canonicalName"].asString(""),
            mergedInto = this["mergedInto"].takeUnless { it.isNull }?.asString(),
        )

    /**
     * Entity-surface kNN (LLD §21 A.2 verbatim): fetch [k] nearest over the shared index, then
     * type-scope and drop tombstones — so fewer than [k] same-type candidates can come back.
     */
    fun entityKnn(entityType: String, embedding: List<Double>, k: Int): List<EntityCandidate> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        CALL db.index.vector.queryNodes('entity_embedding', ${'$'}k, ${'$'}embedding)
                        YIELD node, score
                        WHERE node.entityType = ${'$'}type AND node.mergedInto IS NULL
                        RETURN node.entityId AS entityId, node.entityType AS entityType,
                               node.canonicalKey AS canonicalKey,
                               node.canonicalName AS canonicalName, score
                        ORDER BY score DESC
                        """
                            .trimIndent(),
                        mapOf("k" to k, "embedding" to embedding, "type" to entityType),
                    )
                    .list { r ->
                        EntityCandidate(
                            entityId = r["entityId"].asString(),
                            entityType = r["entityType"].asString(),
                            canonicalKey = r["canonicalKey"].asString(),
                            canonicalName = r["canonicalName"].asString(""),
                            score = r["score"].asDouble(),
                        )
                    }
            }
        }

    /**
     * Persist one resolution tick as a single transaction — mints, MENTIONS links, adopted aliases,
     * `Source.issuerTypeAndKey` stamps, then the claim stamps. A failed tick persists nothing, so
     * the stamp cursor retries it whole. Stale MENTIONS of the batch's claims are cleared first:
     * re-resolution (prompt bump, dry-run flip) replaces a claim's mentions rather than accreting
     * them.
     *
     * Mint MERGE is keyed `(entityType, canonicalKey)` — the planned `entityId` survives only on ON
     * CREATE, and links address entities by the key pair, so losing a mint race is harmless.
     * `typeAndKey` is always written (the §21 A.1 Community-fallback constraint keys on it).
     */
    fun applyEntityResolution(subjectId: String, write: EntityResolutionWrite) {
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                if (write.mints.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MERGE (e:Entity {entityType: row.entityType,
                                             canonicalKey: row.canonicalKey})
                            ON CREATE SET e.entityId = row.entityId,
                                          e.canonicalName = row.canonicalName,
                                          e.typeAndKey = row.entityType + '|' + row.canonicalKey,
                                          e.aliases = [], e.aliasKeys = [],
                                          e.embedding = row.embedding,
                                          e.embeddingModelVersion = row.embeddingStamp,
                                          e.createdFrom = row.createdFrom,
                                          e.createdAt = datetime()
                            """
                                .trimIndent(),
                            mapOf("rows" to write.mints.map { it.toMap() }),
                        )
                        .consume()
                tx.run(
                        """
                        UNWIND ${'$'}claimIds AS cid
                        MATCH (c:Claim {claimId: cid}) WHERE c.subjectId = ${'$'}subjectId
                        OPTIONAL MATCH (c)-[m:MENTIONS]->()
                        DELETE m
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "claimIds" to write.claimIds),
                    )
                    .consume()
                if (write.links.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (c:Claim {claimId: row.claimId})
                            WHERE c.subjectId = ${'$'}subjectId
                            MATCH (e:Entity {entityType: row.entityType,
                                             canonicalKey: row.canonicalKey})
                            MERGE (c)-[m:MENTIONS {surface: row.surface}]->(e)
                            SET m.confidence = row.confidence, m.provisional = row.provisional,
                                m.method = row.method
                            """
                                .trimIndent(),
                            mapOf(
                                "subjectId" to subjectId,
                                "rows" to write.links.map { it.toMap() }
                            ),
                        )
                        .consume()
                if (write.aliasAppends.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (e:Entity {entityType: row.entityType,
                                             canonicalKey: row.canonicalKey})
                            SET e.aliases = CASE WHEN row.surface IN coalesce(e.aliases, [])
                                                 THEN e.aliases
                                                 ELSE coalesce(e.aliases, []) + row.surface END,
                                e.aliasKeys = CASE WHEN row.aliasKey IN coalesce(e.aliasKeys, [])
                                                   THEN e.aliasKeys
                                                   ELSE coalesce(e.aliasKeys, []) + row.aliasKey
                                              END
                            """
                                .trimIndent(),
                            mapOf("rows" to write.aliasAppends.map { it.toMap() }),
                        )
                        .consume()
                if (write.sourceIssuers.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (s:Source {assetId: row.assetId})
                            WHERE s.subjectId = ${'$'}subjectId
                            SET s.issuerTypeAndKey = row.issuerTypeAndKey
                            """
                                .trimIndent(),
                            mapOf(
                                "subjectId" to subjectId,
                                "rows" to write.sourceIssuers.map { it.toMap() },
                            ),
                        )
                        .consume()
                tx.run(
                        """
                        UNWIND ${'$'}claimIds AS cid
                        MATCH (c:Claim {claimId: cid}) WHERE c.subjectId = ${'$'}subjectId
                        SET c.entityResolutionStamp = ${'$'}stamp
                        """
                            .trimIndent(),
                        mapOf(
                            "subjectId" to subjectId,
                            "claimIds" to write.claimIds,
                            "stamp" to write.stamp,
                        ),
                    )
                    .consume()
                Unit
            }
        }
    }

    /**
     * The §18.2 Q2 decision, **UPGRADE** (2026-07-09): once resolution stamps a documentary
     * source's issuer entity (`Source.issuerTypeAndKey`), its claims migrate from the per-asset
     * fallback attestor (`issuer:asset:<assetId>`) to the entity-keyed one
     * (`issuer:entity:<entityId>`) — so the same issuer accrues one global trust node across assets
     * and subjects, the §11.2 intent. Runs as an idempotent sweep at phase completion: derived
     * state, so a re-synced claim (whose SYNC re-MERGEd the fallback edge) heals on the next run's
     * sweep. Orphaned fallback attestors are deleted; trust priors carry over unchanged, so scores
     * are unaffected beyond cross-asset aggregation. Journal = the run's `issuerAttestorsUpgraded`
     * counter + the service log line.
     */
    fun upgradeIssuerAttestors(subjectId: String): Long =
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        MATCH (s:Source {subjectId: ${'$'}subjectId})
                        WHERE s.issuerTypeAndKey IS NOT NULL
                        MATCH (e:Entity {typeAndKey: s.issuerTypeAndKey})
                        MERGE (a:Attestor {attestorKey: 'issuer:entity:' + e.entityId})
                        ON CREATE SET a.kind = 'ISSUER', a.relationship = s.relationship,
                                      a.name = e.canonicalName, a.trustPrior = ${'$'}prior,
                                      a.trust = ${'$'}prior, a.claimCount = 0, a.subjectSpan = 0
                        ON MATCH SET a.name = coalesce(a.name, e.canonicalName)
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "prior" to TRUST_PRIOR_ISSUER),
                    )
                    .consume()
                val upgraded =
                    tx.run(
                            """
                            MATCH (s:Source {subjectId: ${'$'}subjectId})
                            WHERE s.issuerTypeAndKey IS NOT NULL
                            MATCH (e:Entity {typeAndKey: s.issuerTypeAndKey})
                            MATCH (a:Attestor {attestorKey: 'issuer:entity:' + e.entityId})
                            MATCH (c:Claim)-[r:ATTESTED_BY]->
                                  (fb:Attestor {attestorKey: 'issuer:asset:' + s.assetId})
                            WHERE (c)-[:FROM]->(s)
                            MERGE (c)-[:ATTESTED_BY]->(a)
                            DELETE r
                            RETURN count(*) AS upgraded
                            """
                                .trimIndent(),
                            mapOf("subjectId" to subjectId),
                        )
                        .single()["upgraded"]
                        .asLong()
                tx.run(
                        """
                        MATCH (s:Source {subjectId: ${'$'}subjectId})-[v:VOICED_BY]->(fb:Attestor)
                        WHERE s.issuerTypeAndKey IS NOT NULL
                          AND fb.attestorKey = 'issuer:asset:' + s.assetId
                        MATCH (e:Entity {typeAndKey: s.issuerTypeAndKey})
                        MATCH (a:Attestor {attestorKey: 'issuer:entity:' + e.entityId})
                        MERGE (s)-[:VOICED_BY]->(a)
                        DELETE v
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .consume()
                // Fallback attestors left with no voice anywhere are clutter, not history —
                // the upgrade itself is the journaled event.
                tx.run(
                        "MATCH (fb:Attestor) WHERE fb.attestorKey STARTS WITH 'issuer:asset:' " +
                            "AND NOT (fb)<-[:ATTESTED_BY]-() AND NOT (fb)<-[:VOICED_BY]-() " +
                            "DELETE fb"
                    )
                    .consume()
                upgraded
            }
        }

    // ---- Entity admin: merge / split / redirects (LLD §11.3 "Human repair", VA-12) ------

    /** The full entity row the admin operations read (EntityRef + the alias arrays). */
    fun findEntityAdmin(entityId: String): EntityAdminRow? =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (e:Entity {entityId: ${'$'}id})
                        RETURN e.entityId AS entityId, e.entityType AS entityType,
                               e.canonicalKey AS canonicalKey, e.canonicalName AS canonicalName,
                               e.mergedInto AS mergedInto,
                               coalesce(e.aliases, []) AS aliases,
                               coalesce(e.aliasKeys, []) AS aliasKeys
                        LIMIT 1
                        """
                            .trimIndent(),
                        mapOf("id" to entityId),
                    )
                    .list { r ->
                        EntityAdminRow(
                            entityId = r["entityId"].asString(),
                            entityType = r["entityType"].asString(),
                            canonicalKey = r["canonicalKey"].asString(),
                            canonicalName = r["canonicalName"].asString(""),
                            mergedInto = r["mergedInto"].takeUnless { it.isNull }?.asString(),
                            aliases = r["aliases"].asList { v -> v.asString() },
                            aliasKeys = r["aliasKeys"].asList { v -> v.asString() },
                        )
                    }
                    .firstOrNull()
            }
        }

    /** Every MENTIONS edge into the entity — the split's re-resolution work list. */
    fun entityMentionRows(entityId: String): List<EntityMentionRow> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (c:Claim)-[m:MENTIONS]->(e:Entity {entityId: ${'$'}id})
                        RETURN c.claimId AS claimId, c.subjectId AS subjectId,
                               m.surface AS surface,
                               coalesce(m.provisional, false) AS provisional
                        ORDER BY claimId, surface
                        """
                            .trimIndent(),
                        mapOf("id" to entityId),
                    )
                    .list { r ->
                        EntityMentionRow(
                            claimId = r["claimId"].asString(),
                            subjectId = r["subjectId"].asString(""),
                            surface = r["surface"].asString(""),
                            provisional = r["provisional"].asBoolean(false),
                        )
                    }
            }
        }

    /**
     * The §21 A.2 merge rewire: move every MENTIONS edge off the source onto the target, batched
     * `CALL … IN TRANSACTIONS` (an implicit transaction — auto-commit session run, not a managed
     * write). MERGE by `{surface}` dedupes a claim already mentioning the target with the same
     * surface; edge properties copy on create. Returns relationships deleted (= edges moved or
     * dropped as duplicates). Resumable: a crash mid-batch leaves the remaining edges on the
     * source, and re-issuing the merge completes the move.
     */
    fun rewireMentions(fromEntityId: String, intoEntityId: String): Long =
        driver.session(sessionConfig()).use { s ->
            s.run(
                    """
                    MATCH (a:Entity {entityId: ${'$'}from}), (b:Entity {entityId: ${'$'}into})
                    CALL {
                        WITH a, b
                        MATCH (c:Claim)-[m:MENTIONS]->(a)
                        MERGE (c)-[m2:MENTIONS {surface: m.surface}]->(b)
                        ON CREATE SET m2.confidence = m.confidence,
                                      m2.provisional = m.provisional,
                                      m2.method = m.method
                        DELETE m
                    } IN TRANSACTIONS OF 200 ROWS
                    """
                        .trimIndent(),
                    mapOf("from" to fromEntityId, "into" to intoEntityId),
                )
                .consume()
                .counters()
                .relationshipsDeleted()
                .toLong()
        }

    /** Merge finalization: the unioned alias arrays on the target, the tombstone on the source. */
    fun finalizeEntityMerge(
        fromEntityId: String,
        intoEntityId: String,
        aliases: List<String>,
        aliasKeys: List<String>,
    ) {
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        MATCH (a:Entity {entityId: ${'$'}from}), (b:Entity {entityId: ${'$'}into})
                        SET b.aliases = ${'$'}aliases, b.aliasKeys = ${'$'}aliasKeys,
                            a.mergedInto = ${'$'}into
                        """
                            .trimIndent(),
                        mapOf(
                            "from" to fromEntityId,
                            "into" to intoEntityId,
                            "aliases" to aliases,
                            "aliasKeys" to aliasKeys,
                        ),
                    )
                    .consume()
                Unit
            }
        }
    }

    /** Exact key/alias lookup with one entity excluded — the split's resolution leg (§11.3). */
    fun findEntityByKeyExcluding(
        entityType: String,
        canonicalKey: String,
        excludeEntityId: String,
    ): EntityRef? =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (e:Entity {entityType: ${'$'}type})
                        WHERE e.entityId <> ${'$'}exclude
                          AND (e.canonicalKey = ${'$'}key
                               OR ${'$'}key IN coalesce(e.aliasKeys, []))
                        RETURN e.entityId AS entityId, e.entityType AS entityType,
                               e.canonicalKey AS canonicalKey, e.canonicalName AS canonicalName,
                               e.mergedInto AS mergedInto
                        ORDER BY CASE WHEN e.canonicalKey = ${'$'}key THEN 0 ELSE 1 END
                        LIMIT 1
                        """
                            .trimIndent(),
                        mapOf(
                            "type" to entityType,
                            "key" to canonicalKey,
                            "exclude" to excludeEntityId,
                        ),
                    )
                    .list { it.toEntityRef() }
                    .firstOrNull()
            }
        }

    /**
     * Persist one split as a single transaction: mint the new homes (MERGE by type+key — a mention
     * whose key equals an existing entity's lands there, never duplicates), move the re-resolved
     * mentions, and reset the split entity's alias arrays to the surfaces that stayed.
     */
    fun applyEntitySplit(
        entityId: String,
        keptAliases: List<String>,
        keptAliasKeys: List<String>,
        mints: List<EntityMintRow>,
        moves: List<MentionLinkRow>,
    ) {
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                if (mints.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MERGE (e:Entity {entityType: row.entityType,
                                             canonicalKey: row.canonicalKey})
                            ON CREATE SET e.entityId = row.entityId,
                                          e.canonicalName = row.canonicalName,
                                          e.typeAndKey = row.entityType + '|' + row.canonicalKey,
                                          e.aliases = [], e.aliasKeys = [],
                                          e.embedding = row.embedding,
                                          e.embeddingModelVersion = row.embeddingStamp,
                                          e.createdFrom = row.createdFrom,
                                          e.createdAt = datetime()
                            """
                                .trimIndent(),
                            mapOf("rows" to mints.map { it.toMap() }),
                        )
                        .consume()
                if (moves.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (c:Claim {claimId: row.claimId})
                                  -[m:MENTIONS {surface: row.surface}]->
                                  (a:Entity {entityId: ${'$'}id})
                            MATCH (t:Entity {entityType: row.entityType,
                                             canonicalKey: row.canonicalKey})
                            MERGE (c)-[m2:MENTIONS {surface: row.surface}]->(t)
                            ON CREATE SET m2.confidence = row.confidence,
                                          m2.provisional = row.provisional,
                                          m2.method = row.method
                            DELETE m
                            """
                                .trimIndent(),
                            mapOf("id" to entityId, "rows" to moves.map { it.toMap() }),
                        )
                        .consume()
                tx.run(
                        """
                        MATCH (e:Entity {entityId: ${'$'}id})
                        SET e.aliases = ${'$'}aliases, e.aliasKeys = ${'$'}aliasKeys
                        """
                            .trimIndent(),
                        mapOf(
                            "id" to entityId,
                            "aliases" to keptAliases,
                            "aliasKeys" to keptAliasKeys,
                        ),
                    )
                    .consume()
                Unit
            }
        }
    }

    /**
     * Path-compress a followed redirect chain (VA-12: "compress on read") — every traversed
     * tombstone points straight at the final live target afterwards.
     */
    fun compressRedirects(entityIds: List<String>, targetEntityId: String) {
        if (entityIds.isEmpty()) return
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        UNWIND ${'$'}ids AS eid
                        MATCH (e:Entity {entityId: eid})
                        SET e.mergedInto = ${'$'}target
                        """
                            .trimIndent(),
                        mapOf("ids" to entityIds, "target" to targetEntityId),
                    )
                    .consume()
                Unit
            }
        }
    }

    // ---- MATCH phase (LLD §11.5) ----------------------------------------------------

    /**
     * The subject's claims as [ClaimToMatch] rows — the cascade's metadata (type/date/source for
     * the rungs, the §11.9 `explained` flag, and `Fact.exemplarClaimId` for rung 5 once VA-16
     * clusters exist; on a first run the OPTIONAL MATCH yields nulls and rung 5 no-ops).
     */
    fun claimsForMatching(subjectId: String): List<ClaimToMatch> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (c:Claim {subjectId: ${'$'}subjectId})
                        OPTIONAL MATCH (c)-[:ASSERTS]->(f:Fact)
                        RETURN c.claimId AS claimId, c.type AS type, c.text AS text,
                               c.claimedDate AS claimedDate, c.assetId AS assetId,
                               EXISTS { MATCH (:Explanation)-[:EXPLAINS]->(c) } AS explained,
                               f.exemplarClaimId AS exemplarClaimId
                        ORDER BY c.claimId
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r ->
                        ClaimToMatch(
                            claimId = r["claimId"].asString(),
                            type = r["type"].takeUnless { it.isNull }?.asString(),
                            text = r["text"].asString(""),
                            claimedDate = r["claimedDate"].takeUnless { it.isNull }?.asString(),
                            assetId = r["assetId"].takeUnless { it.isNull }?.asString(),
                            explained = r["explained"].asBoolean(false),
                            exemplarClaimId =
                                r["exemplarClaimId"].takeUnless { it.isNull }?.asString(),
                        )
                    }
            }
        }

    /**
     * The §21 A.2 kNN blocking query generalized over the whole subject: each claim fetches its [k]
     * approximate neighbours from the shared index, post-filtered to the subject and floored at
     * [simFloor]. Duplicated hits (a finds b, b finds a) collapse to the unordered pair keeping the
     * max score. HNSW is approximate — EXHAUSTIVE mode (§13) is the recall oracle for tuning
     * [k]/[simFloor], not this query.
     */
    fun claimKnnPairs(subjectId: String, k: Int, simFloor: Double): List<ScoredPair> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                val best = linkedMapOf<ClaimPair, Double>()
                tx.run(
                        """
                        MATCH (c:Claim {subjectId: ${'$'}subjectId})
                        WHERE c.embedding IS NOT NULL
                        CALL db.index.vector.queryNodes('claim_embedding', ${'$'}k, c.embedding)
                        YIELD node, score
                        WHERE node.subjectId = c.subjectId AND node.claimId <> c.claimId
                          AND score >= ${'$'}simFloor
                        RETURN c.claimId AS a, node.claimId AS b, score AS sim
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "k" to k, "simFloor" to simFloor),
                    )
                    .forEach { r ->
                        val pair = ClaimPair.of(r["a"].asString(), r["b"].asString())
                        val sim = r["sim"].asDouble()
                        best[pair] = maxOf(best[pair] ?: sim, sim)
                    }
                best.entries.sortedWith(compareBy({ it.key.a }, { it.key.b })).map {
                    ScoredPair(it.key, it.value)
                }
            }
        }

    /**
     * The §21 A.2 co-mention blocking query with the `entity-idf-floor` inlined: entities mentioned
     * by more than (1 − floor) of the subject's claims are stopword-like (the subject himself,
     * "software") and produce no pairs.
     */
    fun coMentionPairs(subjectId: String, idfFloor: Double): List<ClaimPair> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (c:Claim {subjectId: ${'$'}subjectId})
                        WITH count(c) AS total
                        MATCH (e:Entity)<-[:MENTIONS]-(k:Claim {subjectId: ${'$'}subjectId})
                        WITH e, count(DISTINCT k) AS mentioners, total
                        WHERE mentioners <= (1.0 - ${'$'}floor) * total
                        MATCH (a:Claim {subjectId: ${'$'}subjectId})-[:MENTIONS]->(e)
                              <-[:MENTIONS]-(b:Claim {subjectId: ${'$'}subjectId})
                        WHERE a.claimId < b.claimId
                        RETURN DISTINCT a.claimId AS a, b.claimId AS b
                        ORDER BY a, b
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "floor" to idfFloor),
                    )
                    .list { r -> ClaimPair(r["a"].asString(), r["b"].asString()) }
            }
        }

    /**
     * Human-asserted pairs (§11.5: always judged, bypass every prune): the claim an explanation
     * EXPLAINS × each claim it CITES — review `corroboratingClaimIds` became those CITES edges at
     * SYNC.
     */
    fun humanAssertedPairs(subjectId: String): List<ClaimPair> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (x:Explanation {subjectId: ${'$'}subjectId})-[:EXPLAINS]->(ca:Claim),
                              (x)-[:CITES]->(cb:Claim)
                        WHERE ca.claimId <> cb.claimId
                        WITH CASE WHEN ca.claimId < cb.claimId THEN ca.claimId
                                  ELSE cb.claimId END AS a,
                             CASE WHEN ca.claimId < cb.claimId THEN cb.claimId
                                  ELSE ca.claimId END AS b
                        RETURN DISTINCT a, b
                        ORDER BY a, b
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r -> ClaimPair(r["a"].asString(), r["b"].asString()) }
            }
        }

    /**
     * Exact pairwise cosine for candidate pairs the kNN arm did not score (co-mention/structural/
     * human arms) — `vector.similarity.cosine` over the stored claim vectors. Pairs whose vectors
     * are missing simply drop from the result (the cascade treats them as sim 0.0).
     */
    fun pairSimilarities(subjectId: String, pairs: Collection<ClaimPair>): Map<ClaimPair, Double> {
        if (pairs.isEmpty()) return emptyMap()
        return driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        UNWIND ${'$'}pairs AS p
                        MATCH (a:Claim {claimId: p.a}), (b:Claim {claimId: p.b})
                        WHERE a.subjectId = ${'$'}subjectId AND b.subjectId = ${'$'}subjectId
                          AND a.embedding IS NOT NULL AND b.embedding IS NOT NULL
                        RETURN p.a AS a, p.b AS b,
                               vector.similarity.cosine(a.embedding, b.embedding) AS sim
                        """
                            .trimIndent(),
                        mapOf(
                            "subjectId" to subjectId,
                            "pairs" to pairs.map { mapOf("a" to it.a, "b" to it.b) },
                        ),
                    )
                    .list { r ->
                        ClaimPair(r["a"].asString(), r["b"].asString()) to r["sim"].asDouble()
                    }
                    .toMap()
            }
        }
    }

    /**
     * MATCH's §15 #6 pre-flight, ontology side: entities this subject mentions whose vector is
     * missing or from another space (model/dims/dry-run change after they were minted). Claim
     * staleness is handled by bouncing to EMBED; entities re-embed here because no phase owns their
     * vectors after minting (the canon converges as runs touch it).
     */
    fun entitiesNeedingReembedding(
        subjectId: String,
        versionStamp: String,
        limit: Int,
    ): List<EntityToReembed> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (e:Entity)<-[:MENTIONS]-(c:Claim {subjectId: ${'$'}subjectId})
                        WHERE e.mergedInto IS NULL
                          AND (e.embedding IS NULL OR e.embeddingModelVersion <> ${'$'}stamp)
                        RETURN DISTINCT e.entityId AS entityId, e.canonicalName AS canonicalName
                        ORDER BY entityId LIMIT ${'$'}limit
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "stamp" to versionStamp, "limit" to limit),
                    )
                    .list { r ->
                        EntityToReembed(
                            entityId = r["entityId"].asString(),
                            canonicalName = r["canonicalName"].asString(""),
                        )
                    }
            }
        }

    /** Persist one entity re-embed chunk (global ontology layer — no subjectId by design). */
    fun setEntityEmbeddings(rows: List<EntityEmbedding>, versionStamp: String) {
        if (rows.isEmpty()) return
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        UNWIND ${'$'}rows AS row
                        MATCH (e:Entity {entityId: row.entityId})
                        SET e.embedding = row.embedding, e.embeddingModelVersion = ${'$'}stamp
                        """
                            .trimIndent(),
                        mapOf(
                            "stamp" to versionStamp,
                            "rows" to
                                rows.map {
                                    mapOf("entityId" to it.entityId, "embedding" to it.embedding)
                                },
                        ),
                    )
                    .consume()
                Unit
            }
        }
    }

    /**
     * Persist the cascade's outcome as a single transaction: rung-2/3 auto-REPEATS edges (MERGE —
     * idempotent across re-runs) and the judge queue as `JUDGE_QUEUED` relationships. The queue is
     * **replaced wholesale** (delete-then-create), which is what makes MATCH deterministic and
     * re-entrant: re-running the phase after a config change yields exactly the new queue, never a
     * union of old and new. VA-15 consumes entries by ascending `rank` (its judgeCursor) and flips
     * `status` off QUEUED; a failed tick here persists nothing.
     */
    fun applyMatchOutcome(subjectId: String, outcome: MatchOutcome) {
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                if (outcome.autoRepeats.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (a:Claim {claimId: row.a}) WHERE a.subjectId = ${'$'}subjectId
                            MATCH (b:Claim {claimId: row.b}) WHERE b.subjectId = ${'$'}subjectId
                            MERGE (a)-[r:REPEATS]->(b)
                            SET r.method = 'AUTO', r.rung = row.rung, r.sim = row.sim
                            """
                                .trimIndent(),
                            mapOf(
                                "subjectId" to subjectId,
                                "rows" to
                                    outcome.autoRepeats.map {
                                        mapOf(
                                            "a" to it.pair.a,
                                            "b" to it.pair.b,
                                            "rung" to it.rung,
                                            "sim" to it.sim,
                                        )
                                    },
                            ),
                        )
                        .consume()
                tx.run(
                        "MATCH (a:Claim {subjectId: ${'$'}subjectId})-[q:JUDGE_QUEUED]->() " +
                            "DELETE q",
                        mapOf("subjectId" to subjectId),
                    )
                    .consume()
                if (outcome.queue.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (a:Claim {claimId: row.a}) WHERE a.subjectId = ${'$'}subjectId
                            MATCH (b:Claim {claimId: row.b}) WHERE b.subjectId = ${'$'}subjectId
                            CREATE (a)-[q:JUDGE_QUEUED {rank: row.rank,
                                        blockScore: row.blockScore, sources: row.sources,
                                        humanAsserted: row.humanAsserted,
                                        withContext: row.withContext, status: 'QUEUED'}]->(b)
                            """
                                .trimIndent(),
                            mapOf(
                                "subjectId" to subjectId,
                                "rows" to outcome.queue.map { it.toMap() },
                            ),
                        )
                        .consume()
                Unit
            }
        }
    }

    // ---- JUDGE phase (LLD §11.6) ----------------------------------------------------

    /**
     * The next chunk of QUEUED pairs by ascending rank, hydrated for judging: both claims' §11.6
     * context-card fields, each side's sidecar text (rendered only in the withContext variant), and
     * the entities both claims mention. The `status` flip in [applyJudgeOutcome] is the phase
     * cursor — a killed poll re-reads exactly the pairs it never finished (the graph-as-cursor
     * idiom; re-judged pairs hit the verdict cache anyway).
     */
    fun judgeQueueBatch(subjectId: String, limit: Int): List<PairToJudge> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (a:Claim {subjectId: ${'$'}subjectId})
                              -[q:JUDGE_QUEUED {status: 'QUEUED'}]->(b:Claim)
                        WITH a, b, q ORDER BY q.rank ASC LIMIT ${'$'}limit
                        RETURN a.claimId AS aId, a.text AS aText, a.type AS aType,
                               a.claimedDate AS aDate, a.sourceClass AS aSourceClass,
                               a.relationship AS aRelationship, a.speakerRole AS aSpeakerRole,
                               [ (xa:Explanation)-[:EXPLAINS]->(a) | xa.text ][0] AS aExplanation,
                               b.claimId AS bId, b.text AS bText, b.type AS bType,
                               b.claimedDate AS bDate, b.sourceClass AS bSourceClass,
                               b.relationship AS bRelationship, b.speakerRole AS bSpeakerRole,
                               [ (xb:Explanation)-[:EXPLAINS]->(b) | xb.text ][0] AS bExplanation,
                               q.rank AS rank, q.withContext AS withContext,
                               q.humanAsserted AS humanAsserted,
                               [ (a)-[:MENTIONS]->(e:Entity)<-[:MENTIONS]-(b) |
                                 e.canonicalName ] AS sharedEntities
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "limit" to limit),
                    )
                    .list { r ->
                        PairToJudge(
                            pair = ClaimPair(r["aId"].asString(), r["bId"].asString()),
                            rank = r["rank"].asLong(),
                            withContext = r["withContext"].asBoolean(false),
                            humanAsserted = r["humanAsserted"].asBoolean(false),
                            a = r.toCard("a"),
                            b = r.toCard("b"),
                            sharedEntities = r["sharedEntities"].asList { it.asString() }.sorted(),
                        )
                    }
            }
        }

    private fun org.neo4j.driver.Record.toCard(prefix: String): ClaimCard =
        ClaimCard(
            claimId = this["${prefix}Id"].asString(),
            text = this["${prefix}Text"].asString(""),
            type = this["${prefix}Type"].takeUnless { it.isNull }?.asString(),
            claimedDate = this["${prefix}Date"].takeUnless { it.isNull }?.asString(),
            sourceClass = this["${prefix}SourceClass"].takeUnless { it.isNull }?.asString(),
            relationship = this["${prefix}Relationship"].takeUnless { it.isNull }?.asString(),
            speakerRole = this["${prefix}SpeakerRole"].takeUnless { it.isNull }?.asString(),
            explanationText = this["${prefix}Explanation"].takeUnless { it.isNull }?.asString(),
        )

    /** Queue entries in [status] — 'QUEUED' is the remaining work, 'JUDGED' the done count. */
    fun countJudgeQueue(subjectId: String, status: String): Long =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        "MATCH (a:Claim {subjectId: ${'$'}subjectId})" +
                            "-[q:JUDGE_QUEUED {status: ${'$'}status}]->() RETURN count(q) AS c",
                        mapOf("subjectId" to subjectId, "status" to status),
                    )
                    .single()["c"]
                    .asLong()
            }
        }

    /**
     * Persist one judged chunk as a single transaction: every pair's `JUDGE_QUEUED` edge flips to
     * JUDGED and records both verdict variants (the pair record VA-16 lifts fact edges from), and
     * bare-majority REPEATS verdicts write the claim-level `REPEATS {method: JUDGE}` edge that
     * drives clustering. Clustering reads the **bare** verdict by design: facts must be identical
     * across the §11.9 dual passes, and a sidecar may mitigate penalties but never merge claims.
     * Vote maps store as JSON strings (Neo4j properties are scalars/arrays only). A failed chunk
     * persists nothing — the QUEUED statuses retry it whole, cache-hit-free of charge.
     */
    fun applyJudgeOutcome(subjectId: String, judged: List<JudgedPair>) {
        if (judged.isEmpty()) return
        val rows =
            judged.map { p ->
                mapOf(
                    "a" to p.pair.a,
                    "b" to p.pair.b,
                    "relation" to p.bare.relation.name,
                    "confidence" to p.bare.confidence,
                    "votes" to Json.writeLine(p.bare.votes),
                    "rationale" to p.bare.rationale,
                    "temporalNote" to p.bare.temporalNote,
                    "tie" to p.bare.tie,
                    "floored" to p.bare.floored,
                    "ctxJudged" to (p.ctx != null),
                    "ctxRelation" to p.ctx?.relation?.name,
                    "ctxConfidence" to p.ctx?.confidence,
                    "ctxVotes" to p.ctx?.let { Json.writeLine(it.votes) },
                    "ctxRationale" to p.ctx?.rationale,
                    "ctxTemporalNote" to p.ctx?.temporalNote,
                    "ctxExplanationRelevant" to p.ctx?.explanationRelevant,
                    "judgeModel" to p.judgeModel,
                    "promptHash" to p.promptStamp,
                    "repeats" to (p.bare.relation == JudgeRelation.REPEATS),
                )
            }
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        UNWIND ${'$'}rows AS row
                        MATCH (a:Claim {claimId: row.a})-[q:JUDGE_QUEUED]->(b:Claim {claimId: row.b})
                        WHERE a.subjectId = ${'$'}subjectId
                        SET q.status = 'JUDGED', q.relation = row.relation,
                            q.confidence = row.confidence, q.votes = row.votes,
                            q.rationale = row.rationale, q.temporalNote = row.temporalNote,
                            q.tie = row.tie, q.floored = row.floored,
                            q.ctxJudged = row.ctxJudged, q.ctxRelation = row.ctxRelation,
                            q.ctxConfidence = row.ctxConfidence, q.ctxVotes = row.ctxVotes,
                            q.ctxRationale = row.ctxRationale,
                            q.ctxTemporalNote = row.ctxTemporalNote,
                            q.ctxExplanationRelevant = row.ctxExplanationRelevant,
                            q.judgeModel = row.judgeModel, q.promptHash = row.promptHash
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "rows" to rows),
                    )
                    .consume()
                tx.run(
                        """
                        UNWIND ${'$'}rows AS row
                        WITH row WHERE row.repeats
                        MATCH (a:Claim {claimId: row.a}) WHERE a.subjectId = ${'$'}subjectId
                        MATCH (b:Claim {claimId: row.b}) WHERE b.subjectId = ${'$'}subjectId
                        MERGE (a)-[r:REPEATS]->(b)
                        SET r.method = 'JUDGE', r.confidence = row.confidence,
                            r.votes = row.votes, r.judgeModel = row.judgeModel,
                            r.promptHash = row.promptHash
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "rows" to rows),
                    )
                    .consume()
                Unit
            }
        }
    }

    // ---- ASSEMBLE phase (LLD §11.7) ---------------------------------------------------

    /** The subject's claims with their mention entity-types — the assembler's kind patterns. */
    fun claimsForAssembly(subjectId: String): List<ClaimToAssemble> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (c:Claim {subjectId: ${'$'}subjectId})
                        RETURN c.claimId AS claimId, c.type AS type, c.text AS text,
                               c.claimedDate AS claimedDate, c.sourceClass AS sourceClass,
                               [ (c)-[:MENTIONS]->(e:Entity) | e.entityType ] AS mentionTypes
                        ORDER BY c.claimId
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r ->
                        ClaimToAssemble(
                            claimId = r["claimId"].asString(),
                            type = r["type"].takeUnless { it.isNull }?.asString(),
                            text = r["text"].asString(""),
                            claimedDate = r["claimedDate"].takeUnless { it.isNull }?.asString(),
                            sourceClass = r["sourceClass"].takeUnless { it.isNull }?.asString(),
                            mentionTypes = r["mentionTypes"].asList { it.asString() }.sorted(),
                        )
                    }
            }
        }

    /** Every claim-level REPEATS edge (AUTO + JUDGE) — the clustering input (§11.7). */
    fun repeatsPairs(subjectId: String): List<ClaimPair> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        "MATCH (a:Claim {subjectId: ${'$'}subjectId})-[:REPEATS]->(b:Claim) " +
                            "RETURN a.claimId AS a, b.claimId AS b ORDER BY a, b",
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r -> ClaimPair.of(r["a"].asString(), r["b"].asString()) }
            }
        }

    /** JUDGED pair records off the queue edges — the §11.7 lifting input. */
    fun judgedPairRecords(subjectId: String): List<JudgedPairRecord> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (a:Claim {subjectId: ${'$'}subjectId})
                              -[q:JUDGE_QUEUED {status: 'JUDGED'}]->(b:Claim)
                        RETURN a.claimId AS a, b.claimId AS b, q.relation AS relation,
                               q.confidence AS confidence, q.votes AS votes,
                               q.rationale AS rationale, q.temporalNote AS temporalNote,
                               q.ctxJudged AS ctxJudged, q.ctxRelation AS ctxRelation,
                               q.ctxConfidence AS ctxConfidence,
                               q.ctxExplanationRelevant AS ctxExplanationRelevant,
                               q.judgeModel AS judgeModel, q.promptHash AS promptHash,
                               [ (a)-[:MENTIONS]->(e:Entity)<-[:MENTIONS]-(b) |
                                 e.canonicalName ] AS sharedEntities
                        ORDER BY a, b
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r ->
                        JudgedPairRecord(
                            pair = ClaimPair(r["a"].asString(), r["b"].asString()),
                            relation =
                                JudgeRelation.fromOrNull(
                                    r["relation"].takeUnless { it.isNull }?.asString()
                                ) ?: JudgeRelation.NEUTRAL,
                            confidence = r["confidence"].asDouble(0.0),
                            votesJson = r["votes"].takeUnless { it.isNull }?.asString(),
                            rationale = r["rationale"].takeUnless { it.isNull }?.asString(),
                            temporalNote = r["temporalNote"].takeUnless { it.isNull }?.asString(),
                            ctxJudged = r["ctxJudged"].asBoolean(false),
                            ctxRelation =
                                JudgeRelation.fromOrNull(
                                    r["ctxRelation"].takeUnless { it.isNull }?.asString()
                                ),
                            ctxConfidence = r["ctxConfidence"].takeUnless { it.isNull }?.asDouble(),
                            ctxExplanationRelevant =
                                r["ctxExplanationRelevant"].takeUnless { it.isNull }?.asBoolean(),
                            judgeModel = r["judgeModel"].takeUnless { it.isNull }?.asString(),
                            promptHash = r["promptHash"].takeUnless { it.isNull }?.asString(),
                            sharedEntities = r["sharedEntities"].asList { it.asString() }.sorted(),
                        )
                    }
            }
        }

    /**
     * Persist one assembly as a single transaction, wholesale (the MATCH-queue idiom): the
     * subject's `:Fact` layer is detach-deleted and rebuilt, so a re-run yields exactly the new
     * clustering — deterministic factIds keep rung-5 exemplar substitution stable across runs. A
     * failed tick persists nothing (the phase is one idempotent tick).
     */
    fun applyAssembleOutcome(subjectId: String, outcome: AssembleOutcome) {
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        "MATCH (f:Fact {subjectId: ${'$'}subjectId}) DETACH DELETE f",
                        mapOf("subjectId" to subjectId),
                    )
                    .consume()
                if (outcome.facts.isNotEmpty()) {
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            CREATE (f:Fact {factId: row.factId})
                            SET f.subjectId = ${'$'}subjectId,
                                f.exemplarClaimId = row.exemplarClaimId, f.label = row.label,
                                f.factKind = row.factKind, f.slot = row.slot,
                                f.validFrom = row.validFrom, f.validTo = row.validTo,
                                f.datePrecision = row.datePrecision, f.anchored = row.anchored
                            """
                                .trimIndent(),
                            mapOf(
                                "subjectId" to subjectId,
                                "rows" to
                                    outcome.facts.map {
                                        mapOf(
                                            "factId" to it.factId,
                                            "exemplarClaimId" to it.exemplarClaimId,
                                            "label" to it.label,
                                            "factKind" to it.factKind,
                                            "slot" to it.slot,
                                            "validFrom" to it.validFrom,
                                            "validTo" to it.validTo,
                                            "datePrecision" to it.datePrecision,
                                            "anchored" to it.anchored,
                                        )
                                    },
                            ),
                        )
                        .consume()
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (f:Fact {factId: row.factId})
                            UNWIND row.members AS cid
                            MATCH (c:Claim {claimId: cid}) WHERE c.subjectId = ${'$'}subjectId
                            MERGE (c)-[:ASSERTS]->(f)
                            """
                                .trimIndent(),
                            mapOf(
                                "subjectId" to subjectId,
                                "rows" to
                                    outcome.facts.map {
                                        mapOf("factId" to it.factId, "members" to it.memberClaimIds)
                                    },
                            ),
                        )
                        .consume()
                }
                listOf("CORROBORATES", "CONTRADICTS").forEach { relation ->
                    val rows =
                        outcome.edges
                            .filter { it.relation == relation }
                            .map {
                                mapOf(
                                    "from" to it.fromFactId,
                                    "to" to it.toFactId,
                                    "confidence" to it.confidence,
                                    "votes" to it.votesJson,
                                    "rationale" to it.rationale,
                                    "temporalNote" to it.temporalNote,
                                    "judgeModel" to it.judgeModel,
                                    "promptHash" to it.promptHash,
                                    "withContext" to it.withContext,
                                    "ctxRelation" to it.ctxRelation,
                                    "ctxConfidence" to it.ctxConfidence,
                                    "explained" to it.explained,
                                    "temporalOverlap" to it.temporalOverlap,
                                    "severity" to it.severity,
                                    "reviewStatus" to it.reviewStatus,
                                    "viaEntities" to it.viaEntities,
                                    "contributingPairs" to it.contributingPairs,
                                )
                            }
                    if (rows.isEmpty()) return@forEach
                    // Relationship types cannot be parameterized — one statement per type.
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (f:Fact {factId: row.from}), (g:Fact {factId: row.to})
                            CREATE (f)-[r:$relation {confidence: row.confidence,
                                        votes: row.votes, rationale: row.rationale,
                                        temporalNote: row.temporalNote,
                                        judgeModel: row.judgeModel, promptHash: row.promptHash,
                                        withContext: row.withContext,
                                        ctxRelation: row.ctxRelation,
                                        ctxConfidence: row.ctxConfidence,
                                        viaEntities: row.viaEntities,
                                        contributingPairs: row.contributingPairs}]->(g)
                            SET r.explained = row.explained,
                                r.temporalOverlap = row.temporalOverlap,
                                r.severity = row.severity, r.reviewStatus = row.reviewStatus
                            """
                                .trimIndent(),
                            mapOf("rows" to rows),
                        )
                        .consume()
                }
                if (outcome.succeeds.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (f:Fact {factId: row.from}), (g:Fact {factId: row.to})
                            CREATE (f)-[r:SUCCEEDS {slot: row.slot}]->(g)
                            SET r.gapDays = row.gapDays
                            """
                                .trimIndent(),
                            mapOf(
                                "rows" to
                                    outcome.succeeds.map {
                                        mapOf(
                                            "from" to it.fromFactId,
                                            "to" to it.toFactId,
                                            "slot" to it.slot,
                                            "gapDays" to it.gapDays,
                                        )
                                    },
                            ),
                        )
                        .consume()
                Unit
            }
        }
    }

    // ---- SCORE phase (LLD §11.8) --------------------------------------------------------

    /** Everything the pure [Scorer] reads, in four subject-scoped queries. */
    fun scoreSnapshot(subjectId: String): GraphSnapshot =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                val claims =
                    tx.run(
                            """
                            MATCH (c:Claim {subjectId: ${'$'}subjectId})-[:ASSERTS]->(f:Fact)
                            RETURN c.claimId AS claimId, f.factId AS factId, c.type AS type,
                                   c.tierSeed AS tierSeed, c.sourceClass AS sourceClass,
                                   c.basis AS basis, c.favorability AS favorability,
                                   c.claimedDate AS claimedDate, c.assetId AS assetId,
                                   [ (c)-[:ATTESTED_BY]->(a:Attestor) | a.attestorKey ]
                                       AS attestorKeys
                            ORDER BY claimId
                            """
                                .trimIndent(),
                            mapOf("subjectId" to subjectId),
                        )
                        .list { r ->
                            ClaimSnapshot(
                                claimId = r["claimId"].asString(),
                                factId = r["factId"].asString(),
                                type = r["type"].takeUnless { it.isNull }?.asString(),
                                tierSeed = r["tierSeed"].takeUnless { it.isNull }?.asString(),
                                sourceClass = r["sourceClass"].takeUnless { it.isNull }?.asString(),
                                basis = r["basis"].takeUnless { it.isNull }?.asString(),
                                favorability =
                                    r["favorability"].takeUnless { it.isNull }?.asDouble(),
                                claimedDate = r["claimedDate"].takeUnless { it.isNull }?.asString(),
                                attestorKey =
                                    r["attestorKeys"].asList { it.asString() }.minOrNull(),
                                assetId = r["assetId"].takeUnless { it.isNull }?.asString(),
                            )
                        }
                val facts =
                    tx.run(
                            "MATCH (f:Fact {subjectId: ${'$'}subjectId}) " +
                                "RETURN f.factId AS factId, f.factKind AS factKind, " +
                                "f.exemplarClaimId AS exemplarClaimId, f.anchored AS anchored " +
                                "ORDER BY factId",
                            mapOf("subjectId" to subjectId),
                        )
                        .list { r ->
                            FactSnapshot(
                                factId = r["factId"].asString(),
                                factKind = r["factKind"].asString("TIMELESS"),
                                exemplarClaimId = r["exemplarClaimId"].asString(""),
                                anchored = r["anchored"].asBoolean(false),
                            )
                        }
                val attestors =
                    tx.run(
                            """
                            MATCH (c:Claim {subjectId: ${'$'}subjectId})-[:ATTESTED_BY]->(a:Attestor)
                            RETURN DISTINCT a.attestorKey AS attestorKey,
                                   a.trustPrior AS trustPrior, a.trust AS trust
                            ORDER BY attestorKey
                            """
                                .trimIndent(),
                            mapOf("subjectId" to subjectId),
                        )
                        .list { r ->
                            AttestorSnapshot(
                                attestorKey = r["attestorKey"].asString(),
                                trustPrior = r["trustPrior"].asDouble(0.5),
                                trust = r["trust"].asDouble(r["trustPrior"].asDouble(0.5)),
                            )
                        }
                val edges =
                    tx.run(
                            """
                            MATCH (f:Fact {subjectId: ${'$'}subjectId})
                                  -[r:CORROBORATES|CONTRADICTS]->(g:Fact)
                            RETURN f.factId AS fromFactId, g.factId AS toFactId,
                                   type(r) AS relation, r.confidence AS confidence,
                                   r.withContext AS withContext, r.ctxRelation AS ctxRelation,
                                   r.ctxConfidence AS ctxConfidence, r.explained AS explained
                            ORDER BY fromFactId, toFactId, relation
                            """
                                .trimIndent(),
                            mapOf("subjectId" to subjectId),
                        )
                        .list { r ->
                            EdgeSnapshot(
                                fromFactId = r["fromFactId"].asString(),
                                toFactId = r["toFactId"].asString(),
                                relation = r["relation"].asString(),
                                confidence = r["confidence"].asDouble(0.0),
                                withContext = r["withContext"].asBoolean(false),
                                ctxRelation = r["ctxRelation"].takeUnless { it.isNull }?.asString(),
                                ctxConfidence =
                                    r["ctxConfidence"].takeUnless { it.isNull }?.asDouble(),
                                explained = r["explained"].asBoolean(false),
                            )
                        }
                GraphSnapshot(claims, facts, attestors, edges)
            }
        }

    /**
     * Persist provisional scores graph-side (§11.10: the ledger stays untouched until publish):
     * fact beliefs + signals, per-claim score vector (the §3.2 shape, `scoreBare` included), and
     * the once-at-convergence attestor trust updates (global nodes — the §6 row 3 cross-subject
     * accrual). One transaction; signal maps store as JSON strings (Neo4j property model).
     */
    fun applyScoreOutcome(subjectId: String, outcome: ScoreOutcome) {
        val factSignals = outcome.facts.associateBy { it.factId }
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                if (outcome.facts.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (f:Fact {factId: row.factId})
                            WHERE f.subjectId = ${'$'}subjectId
                            SET f.belief = row.belief, f.beliefBare = row.beliefBare,
                                f.signals = row.signals
                            """
                                .trimIndent(),
                            mapOf(
                                "subjectId" to subjectId,
                                "rows" to
                                    outcome.facts.map {
                                        mapOf(
                                            "factId" to it.factId,
                                            "belief" to it.belief,
                                            "beliefBare" to it.beliefBare,
                                            "signals" to Json.writeLine(it.signals.toMap()),
                                        )
                                    },
                            ),
                        )
                        .consume()
                if (outcome.claims.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (c:Claim {claimId: row.claimId})
                            WHERE c.subjectId = ${'$'}subjectId
                            SET c.prior = row.prior, c.score = row.score,
                                c.scoreBare = row.scoreBare, c.signals = row.signals
                            """
                                .trimIndent(),
                            mapOf(
                                "subjectId" to subjectId,
                                "rows" to
                                    outcome.claims.map { c ->
                                        mapOf(
                                            "claimId" to c.claimId,
                                            "prior" to c.prior,
                                            "score" to c.score,
                                            "scoreBare" to c.scoreBare,
                                            "signals" to
                                                Json.writeLine(
                                                    claimSignalVector(
                                                        c,
                                                        factSignals.getValue(c.factId).signals,
                                                    )
                                                ),
                                        )
                                    },
                            ),
                        )
                        .consume()
                if (outcome.trustUpdates.isNotEmpty())
                    tx.run(
                            """
                            UNWIND ${'$'}rows AS row
                            MATCH (a:Attestor {attestorKey: row.attestorKey})
                            SET a.trust = row.trust, a.claimCount = row.factCount
                            """
                                .trimIndent(),
                            mapOf(
                                "rows" to
                                    outcome.trustUpdates.map {
                                        mapOf(
                                            "attestorKey" to it.attestorKey,
                                            "trust" to it.trust,
                                            "factCount" to it.factCount,
                                        )
                                    },
                            ),
                        )
                        .consume()
                Unit
            }
        }
    }

    /** The §11.10 queue size: PROPOSED, unexplained CONTRADICTS at/above the confidence floor. */
    fun countContradictionQueue(subjectId: String, floor: Double): Long =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (f:Fact {subjectId: ${'$'}subjectId})-[r:CONTRADICTS]->(:Fact)
                        WHERE r.reviewStatus = 'PROPOSED' AND r.explained = false
                          AND r.confidence >= ${'$'}floor
                        RETURN count(r) AS c
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "floor" to floor),
                    )
                    .single()["c"]
                    .asLong()
            }
        }

    /**
     * The §21 A.3 read-back for `GET /subjects/{id}/scores`: every scored claim with its vector,
     * its fact (label/kind/interval/beliefs) and the fact's judged edges — the "why this score"
     * decomposition panel's data, one row per claim.
     */
    fun scoresReadback(subjectId: String): List<ScoredClaimView> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (c:Claim {subjectId: ${'$'}subjectId})-[:ASSERTS]->(f:Fact)
                        WHERE c.score IS NOT NULL
                        RETURN c.claimId AS claimId, c.text AS text, c.type AS type,
                               c.tierSeed AS tierSeed, c.prior AS prior, c.score AS score,
                               c.scoreBare AS scoreBare, c.signals AS signals,
                               f.factId AS factId, f.label AS factLabel,
                               f.factKind AS factKind, f.slot AS slot,
                               f.validFrom AS validFrom, f.validTo AS validTo,
                               f.datePrecision AS datePrecision, f.anchored AS anchored,
                               f.belief AS belief, f.beliefBare AS beliefBare,
                               [ (f)-[r:CORROBORATES|CONTRADICTS]-(g:Fact) |
                                 {relation: type(r), otherFactId: g.factId,
                                  otherLabel: g.label, confidence: r.confidence,
                                  votes: r.votes, rationale: r.rationale,
                                  explained: r.explained, temporalOverlap: r.temporalOverlap,
                                  reviewStatus: r.reviewStatus,
                                  viaEntities: r.viaEntities} ] AS edges,
                               [ (x:Explanation)-[:EXPLAINS]->(c) | x.text ][0] AS explanation
                        ORDER BY c.score DESC, claimId
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r ->
                        ScoredClaimView(
                            claimId = r["claimId"].asString(),
                            text = r["text"].asString(""),
                            type = r["type"].takeUnless { it.isNull }?.asString(),
                            tierSeed = r["tierSeed"].takeUnless { it.isNull }?.asString(),
                            prior = r["prior"].takeUnless { it.isNull }?.asDouble(),
                            score = r["score"].asDouble(0.0),
                            scoreBare = r["scoreBare"].takeUnless { it.isNull }?.asDouble(),
                            signalsJson = r["signals"].takeUnless { it.isNull }?.asString(),
                            factId = r["factId"].asString(),
                            factLabel = r["factLabel"].asString(""),
                            factKind = r["factKind"].takeUnless { it.isNull }?.asString(),
                            slot = r["slot"].takeUnless { it.isNull }?.asString(),
                            validFrom = r["validFrom"].takeUnless { it.isNull }?.asString(),
                            validTo = r["validTo"].takeUnless { it.isNull }?.asString(),
                            datePrecision = r["datePrecision"].takeUnless { it.isNull }?.asString(),
                            anchored = r["anchored"].asBoolean(false),
                            belief = r["belief"].takeUnless { it.isNull }?.asDouble(),
                            beliefBare = r["beliefBare"].takeUnless { it.isNull }?.asDouble(),
                            edges = r["edges"].asList { it.asMap() },
                            explanation = r["explanation"].takeUnless { it.isNull }?.asString(),
                        )
                    }
            }
        }

    // ---- AWAITING_REVIEW queue + publish (LLD §11.10–§11.11) -----------------------------

    /**
     * The §11.10 contradiction queue: every PROPOSED, unexplained CONTRADICTS edge at/above the
     * floor, with both facts' member claims, the ensemble rationale + vote split, and the
     * provisional score impact (current vs bare delta). Edges address by Neo4j `elementId` — the
     * action endpoints' `{edgeId}`.
     */
    fun contradictionQueue(subjectId: String, floor: Double): List<ContradictionView> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (f:Fact {subjectId: ${'$'}subjectId})-[r:CONTRADICTS]->(g:Fact)
                        WHERE r.reviewStatus = 'PROPOSED' AND r.explained = false
                          AND r.confidence >= ${'$'}floor
                        RETURN elementId(r) AS edgeId, r.confidence AS confidence,
                               r.votes AS votes, r.rationale AS rationale,
                               r.temporalNote AS temporalNote, r.viaEntities AS viaEntities,
                               r.contributingPairs AS contributingPairs,
                               r.temporalOverlap AS temporalOverlap,
                               f.factId AS fromFactId, f.label AS fromLabel,
                               f.belief AS fromBelief, f.beliefBare AS fromBeliefBare,
                               [ (c:Claim)-[:ASSERTS]->(f) |
                                 {claimId: c.claimId, text: c.text,
                                  sourceClass: c.sourceClass, assetId: c.assetId} ] AS fromClaims,
                               g.factId AS toFactId, g.label AS toLabel,
                               g.belief AS toBelief, g.beliefBare AS toBeliefBare,
                               [ (c:Claim)-[:ASSERTS]->(g) |
                                 {claimId: c.claimId, text: c.text,
                                  sourceClass: c.sourceClass, assetId: c.assetId} ] AS toClaims
                        ORDER BY r.confidence DESC, fromFactId, toFactId
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "floor" to floor),
                    )
                    .list { r ->
                        ContradictionView(
                            edgeId = r["edgeId"].asString(),
                            confidence = r["confidence"].asDouble(0.0),
                            votesJson = r["votes"].takeUnless { it.isNull }?.asString(),
                            rationale = r["rationale"].takeUnless { it.isNull }?.asString(),
                            temporalNote = r["temporalNote"].takeUnless { it.isNull }?.asString(),
                            temporalOverlap =
                                r["temporalOverlap"].takeUnless { it.isNull }?.asBoolean(),
                            viaEntities = r["viaEntities"].asList { it.asString() },
                            contributingPairs = r["contributingPairs"].asList { it.asString() },
                            from = r.toContradictionSide("from"),
                            to = r.toContradictionSide("to"),
                        )
                    }
            }
        }

    private fun org.neo4j.driver.Record.toContradictionSide(prefix: String): ContradictionSide =
        ContradictionSide(
            factId = this["${prefix}FactId"].asString(),
            label = this["${prefix}Label"].asString(""),
            belief = this["${prefix}Belief"].takeUnless { it.isNull }?.asDouble(),
            beliefBare = this["${prefix}BeliefBare"].takeUnless { it.isNull }?.asDouble(),
            claims = this["${prefix}Claims"].asList { it.asMap() },
        )

    /** Resolve an action's edge id to its subject + pairs; null when the edge is gone. */
    fun findContradictionEdge(edgeId: String): ContradictionEdgeRef? =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (f:Fact)-[r:CONTRADICTS]->(g:Fact)
                        WHERE elementId(r) = ${'$'}edgeId
                        RETURN f.subjectId AS subjectId, f.factId AS fromFactId,
                               g.factId AS toFactId, r.reviewStatus AS reviewStatus,
                               r.contributingPairs AS contributingPairs
                        """
                            .trimIndent(),
                        mapOf("edgeId" to edgeId),
                    )
                    .list { r ->
                        ContradictionEdgeRef(
                            edgeId = edgeId,
                            subjectId = r["subjectId"].asString(),
                            fromFactId = r["fromFactId"].asString(),
                            toFactId = r["toFactId"].asString(),
                            reviewStatus = r["reviewStatus"].takeUnless { it.isNull }?.asString(),
                            contributingPairs = r["contributingPairs"].asList { it.asString() },
                        )
                    }
                    .firstOrNull()
            }
        }

    /** Confirm (§11.10): PROPOSED → CONFIRMED. False when the edge was not PROPOSED anymore. */
    fun confirmContradiction(subjectId: String, edgeId: String): Boolean =
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        MATCH (f:Fact {subjectId: ${'$'}subjectId})-[r:CONTRADICTS]->(:Fact)
                        WHERE elementId(r) = ${'$'}edgeId AND r.reviewStatus = 'PROPOSED'
                        SET r.reviewStatus = 'CONFIRMED'
                        RETURN count(r) AS c
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "edgeId" to edgeId),
                    )
                    .single()["c"]
                    .asLong() > 0
            }
        }

    /** Dismiss (§11.10): the judge was wrong — delete the edge. False when already gone. */
    fun deleteContradiction(subjectId: String, edgeId: String): Boolean =
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        MATCH (f:Fact {subjectId: ${'$'}subjectId})-[r:CONTRADICTS]->(:Fact)
                        WHERE elementId(r) = ${'$'}edgeId
                        DELETE r
                        RETURN count(r) AS c
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "edgeId" to edgeId),
                    )
                    .single()["c"]
                    .asLong() > 0
            }
        }

    /** The explain hook's edge update: fresh ctx verdict fields + the §11.9 explained flag. */
    fun updateContradictionContext(
        subjectId: String,
        edgeId: String,
        ctxRelation: String?,
        ctxConfidence: Double?,
        explained: Boolean,
    ) {
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        MATCH (f:Fact {subjectId: ${'$'}subjectId})-[r:CONTRADICTS]->(:Fact)
                        WHERE elementId(r) = ${'$'}edgeId
                        SET r.withContext = true, r.ctxRelation = ${'$'}ctxRelation,
                            r.ctxConfidence = ${'$'}ctxConfidence, r.explained = ${'$'}explained
                        """
                            .trimIndent(),
                        mapOf(
                            "subjectId" to subjectId,
                            "edgeId" to edgeId,
                            "ctxRelation" to ctxRelation,
                            "ctxConfidence" to ctxConfidence,
                            "explained" to explained,
                        ),
                    )
                    .consume()
                Unit
            }
        }
    }

    /**
     * Refresh the graph's sidecar projection for [rows]' claims (the explain action authors or
     * edits a §12.6 justification AFTER sync) — same MERGE shape as [mergeEvidence]'s explanation
     * leg, minus CITES (citation edits re-enter via a re-run's MATCH, not the hook).
     */
    fun upsertExplanations(subjectId: String, rows: List<ExplanationRow>) {
        if (rows.isEmpty()) return
        driver.session(sessionConfig()).use { s ->
            s.executeWrite { tx ->
                tx.run(
                        """
                        UNWIND ${'$'}rows AS row
                        MERGE (e:Explanation {explanationId: row.explanationId})
                        SET e.subjectId = ${'$'}subjectId, e.text = row.text,
                            e.author = row.author, e.createdAt = row.createdAt
                        WITH e, row
                        MATCH (c:Claim {claimId: row.claimId})
                        WHERE c.subjectId = ${'$'}subjectId
                        MERGE (e)-[:EXPLAINS]->(c)
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId, "rows" to rows.map { it.toMap() }),
                    )
                    .consume()
                Unit
            }
        }
    }

    /** Hydrate explicit pairs for the explain hook's re-judge — the [judgeQueueBatch] shape. */
    fun hydratePairs(subjectId: String, pairs: Collection<ClaimPair>): List<PairToJudge> {
        if (pairs.isEmpty()) return emptyList()
        return driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        UNWIND ${'$'}pairs AS p
                        MATCH (a:Claim {claimId: p.a}), (b:Claim {claimId: p.b})
                        WHERE a.subjectId = ${'$'}subjectId AND b.subjectId = ${'$'}subjectId
                        RETURN a.claimId AS aId, a.text AS aText, a.type AS aType,
                               a.claimedDate AS aDate, a.sourceClass AS aSourceClass,
                               a.relationship AS aRelationship, a.speakerRole AS aSpeakerRole,
                               [ (xa:Explanation)-[:EXPLAINS]->(a) | xa.text ][0] AS aExplanation,
                               b.claimId AS bId, b.text AS bText, b.type AS bType,
                               b.claimedDate AS bDate, b.sourceClass AS bSourceClass,
                               b.relationship AS bRelationship, b.speakerRole AS bSpeakerRole,
                               [ (xb:Explanation)-[:EXPLAINS]->(b) | xb.text ][0] AS bExplanation,
                               [ (a)-[:MENTIONS]->(e:Entity)<-[:MENTIONS]-(b) |
                                 e.canonicalName ] AS sharedEntities
                        ORDER BY aId, bId
                        """
                            .trimIndent(),
                        mapOf(
                            "subjectId" to subjectId,
                            "pairs" to pairs.map { mapOf("a" to it.a, "b" to it.b) },
                        ),
                    )
                    .list { r ->
                        val a = r.toCard("a")
                        val b = r.toCard("b")
                        PairToJudge(
                            pair = ClaimPair(a.claimId, b.claimId),
                            rank = 0,
                            withContext = a.explanationText != null || b.explanationText != null,
                            humanAsserted = false,
                            a = a,
                            b = b,
                            sharedEntities = r["sharedEntities"].asList { it.asString() }.sorted(),
                        )
                    }
            }
        }
    }

    /** Every provisionally scored claim — the PUBLISHING tick's ledger rows (§11.11). */
    fun scoredClaimsForPublish(subjectId: String): List<ScoredClaimForPublish> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (c:Claim {subjectId: ${'$'}subjectId})
                        WHERE c.score IS NOT NULL
                        RETURN c.claimId AS claimId, c.score AS score, c.signals AS signals
                        ORDER BY claimId
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r ->
                        ScoredClaimForPublish(
                            claimId = r["claimId"].asString(),
                            score = r["score"].asDouble(0.0),
                            signalsJson = r["signals"].takeUnless { it.isNull }?.asString(),
                        )
                    }
            }
        }

    // ---- Eval harness reads (LLD §13, VA-20) --------------------------------------------

    /** The subject's claim ids — the probe sampler's pair universe. */
    fun claimIds(subjectId: String): List<String> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        "MATCH (c:Claim {subjectId: ${'$'}subjectId}) " +
                            "RETURN c.claimId AS id ORDER BY id",
                        mapOf("subjectId" to subjectId),
                    )
                    .list { it["id"].asString() }
            }
        }

    /** Pairs the judge queue holds (any status) — the §13 "hard cases" sampling stratum. */
    fun judgeQueuePairs(subjectId: String): List<ClaimPair> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (a:Claim {subjectId: ${'$'}subjectId})-[q:JUDGE_QUEUED]->(b:Claim)
                        RETURN a.claimId AS a, b.claimId AS b ORDER BY a, b
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r -> ClaimPair(r["a"].asString(), r["b"].asString()) }
            }
        }

    /** Auto-REPEATS pairs (rungs 2/3) — blocking survivors resolved without the judge (§13). */
    fun autoRepeatsPairs(subjectId: String): List<ClaimPair> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (a:Claim {subjectId: ${'$'}subjectId})-[r:REPEATS {method: 'AUTO'}]
                              -(b:Claim)
                        WHERE a.claimId < b.claimId
                        RETURN DISTINCT a.claimId AS a, b.claimId AS b ORDER BY a, b
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r -> ClaimPair(r["a"].asString(), r["b"].asString()) }
            }
        }

    /**
     * Which of [pairs] survived PRUNED blocking — a pair record exists: a JUDGE_QUEUED entry (any
     * status) or a REPEATS edge (auto or judged). The §13 blocking-recall numerator test.
     */
    fun pairRecords(subjectId: String, pairs: Collection<ClaimPair>): Set<ClaimPair> {
        if (pairs.isEmpty()) return emptySet()
        return driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        UNWIND ${'$'}pairs AS p
                        MATCH (a:Claim {claimId: p.a}), (b:Claim {claimId: p.b})
                        WHERE a.subjectId = ${'$'}subjectId AND b.subjectId = ${'$'}subjectId
                          AND (EXISTS { MATCH (a)-[:JUDGE_QUEUED]-(b) }
                               OR EXISTS { MATCH (a)-[:REPEATS]-(b) })
                        RETURN p.a AS a, p.b AS b
                        """
                            .trimIndent(),
                        mapOf(
                            "subjectId" to subjectId,
                            "pairs" to pairs.map { mapOf("a" to it.a, "b" to it.b) },
                        ),
                    )
                    .list { r -> ClaimPair(r["a"].asString(), r["b"].asString()) }
                    .toSet()
            }
        }
    }

    /** Per-fact rows behind the §13 score-sanity flags on the reference subject. */
    fun factSanityRows(subjectId: String): List<FactSanityRow> =
        driver.session(sessionConfig()).use { s ->
            s.executeRead { tx ->
                tx.run(
                        """
                        MATCH (f:Fact {subjectId: ${'$'}subjectId})
                        OPTIONAL MATCH (c:Claim)-[:ASSERTS]->(f)
                        WITH f, collect(c.sourceClass) AS classes,
                             collect(c.favorability) AS favorabilities
                        OPTIONAL MATCH (f)-[r:CONTRADICTS]-(:Fact)
                        WITH f, classes, favorabilities,
                             max(CASE WHEN r.explained THEN 1 ELSE 0 END) AS explained
                        RETURN f.factId AS factId, f.belief AS belief,
                               f.beliefBare AS beliefBare,
                               coalesce(f.anchored, false) AS anchored,
                               classes, favorabilities,
                               explained = 1 AS hasExplainedContradiction
                        ORDER BY factId
                        """
                            .trimIndent(),
                        mapOf("subjectId" to subjectId),
                    )
                    .list { r ->
                        FactSanityRow(
                            factId = r["factId"].asString(),
                            belief = r["belief"].takeUnless { it.isNull }?.asDouble(),
                            beliefBare = r["beliefBare"].takeUnless { it.isNull }?.asDouble(),
                            anchored = r["anchored"].asBoolean(false),
                            sourceClasses = r["classes"].asList { v -> v.asString() },
                            favorabilities = r["favorabilities"].asList { v -> v.asDouble() },
                            hasExplainedContradiction =
                                r["hasExplainedContradiction"].asBoolean(false),
                        )
                    }
            }
        }
}

/** The §3.2 per-claim signal vector as persisted (claim `signals` JSON; ledger shape VA-18). */
internal fun claimSignalVector(claim: ClaimScore, signals: FactSignals): Map<String, Double> =
    mapOf(
        "prior" to claim.prior,
        "support" to signals.support,
        "conflict" to signals.conflict,
        "independence" to signals.independence,
        "recency" to signals.recency,
        "evidenceMass" to signals.evidenceMass,
        "scoreBare" to claim.scoreBare,
    )

internal fun FactSignals.toMap(): Map<String, Double> =
    mapOf(
        "support" to support,
        "conflict" to conflict,
        "independence" to independence,
        "recency" to recency,
        "evidenceMass" to evidenceMass,
    )

/** One §11.10 queue entry — a PROPOSED contradiction with everything the pair card shows. */
data class ContradictionView(
    val edgeId: String,
    val confidence: Double,
    val votesJson: String?,
    val rationale: String?,
    val temporalNote: String?,
    val temporalOverlap: Boolean?,
    val viaEntities: List<String>,
    val contributingPairs: List<String>,
    val from: ContradictionSide,
    val to: ContradictionSide,
) {
    /** The provisional score impact the card shows: current belief minus bare belief per side. */
    val impact: Map<String, Double?>
        get() =
            mapOf(
                from.factId to from.belief?.let { b -> from.beliefBare?.let { b - it } },
                to.factId to to.belief?.let { b -> to.beliefBare?.let { b - it } },
            )
}

data class ContradictionSide(
    val factId: String,
    val label: String,
    val belief: Double?,
    val beliefBare: Double?,
    val claims: List<Map<String, Any?>>,
)

/** An action's resolved edge: whose subject it belongs to and which claim pairs judged it. */
data class ContradictionEdgeRef(
    val edgeId: String,
    val subjectId: String,
    val fromFactId: String,
    val toFactId: String,
    val reviewStatus: String?,
    val contributingPairs: List<String>,
) {
    /** Parse the "a↔b" contributing-pair records back into [ClaimPair]s. */
    fun pairs(): List<ClaimPair> =
        contributingPairs.mapNotNull { raw ->
            val parts = raw.split("↔")
            if (parts.size == 2) ClaimPair.of(parts[0], parts[1]) else null
        }
}

/** One provisionally scored claim as PUBLISHING reads it back for the ledger (§11.11). */
data class ScoredClaimForPublish(
    val claimId: String,
    val score: Double,
    val signalsJson: String?,
)

/** One row of the §21 A.3 score read-back — the "why this score" panel's data (LLD §12). */
data class ScoredClaimView(
    val claimId: String,
    val text: String,
    val type: String?,
    val tierSeed: String?,
    val prior: Double?,
    val score: Double,
    val scoreBare: Double?,
    val signalsJson: String?,
    val factId: String,
    val factLabel: String,
    val factKind: String?,
    val slot: String?,
    val validFrom: String?,
    val validTo: String?,
    val datePrecision: String?,
    val anchored: Boolean,
    val belief: Double?,
    val beliefBare: Double?,
    val edges: List<Map<String, Any?>>,
    val explanation: String?,
)

/** One embedded claim ready to persist. */
data class EmbeddedClaim(val claimId: String, val embedding: List<Double>)

/** An entity whose vector is stale/missing — MATCH's §15 #6 re-embed work unit (VA-14). */
data class EntityToReembed(val entityId: String, val canonicalName: String)

/** Full entity row for the VA-12 admin operations (EntityRef + the alias arrays). */
data class EntityAdminRow(
    val entityId: String,
    val entityType: String,
    val canonicalKey: String,
    val canonicalName: String,
    val mergedInto: String?,
    val aliases: List<String>,
    val aliasKeys: List<String>,
) {
    fun toRef(): EntityRef =
        EntityRef(entityId, entityType, canonicalKey, canonicalName, mergedInto)
}

/** One MENTIONS edge into an entity — the split's re-resolution work unit (VA-12). */
data class EntityMentionRow(
    val claimId: String,
    val subjectId: String,
    val surface: String,
    val provisional: Boolean,
)

/** One fact's inputs to the §13 score-sanity flags (VA-20). */
data class FactSanityRow(
    val factId: String,
    val belief: Double?,
    val beliefBare: Double?,
    val anchored: Boolean,
    /** Member claims' source classes (nulls dropped by collect). */
    val sourceClasses: List<String>,
    val favorabilities: List<Double>,
    val hasExplainedContradiction: Boolean,
)

/** One re-embedded entity ready to persist. */
data class EntityEmbedding(val entityId: String, val embedding: List<Double>)

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

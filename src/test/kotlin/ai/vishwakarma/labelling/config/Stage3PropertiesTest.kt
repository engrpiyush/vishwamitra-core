package ai.vishwakarma.labelling.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * VA-7 acceptance: the §8.2 table binds with its documented defaults, and kebab-case keys /
 * Duration strings round-trip through the relaxed binder the same way application.yml does.
 */
class Stage3PropertiesTest {

    @Test
    fun `defaults match the LLD §8-2 table`() {
        val s3 = AppProperties.Stage3()
        assertEquals("bolt://localhost:7687", s3.neo4jUri)
        assertEquals("neo4j", s3.neo4jDatabase)
        assertEquals("gemini-embedding-001", s3.embeddingModel)
        assertEquals(3072, s3.embeddingDimensions)
        assertEquals(32, s3.embedBatchPerPoll)
        assertEquals(20, s3.entityBatchPerPoll)
        assertEquals("PRUNED", s3.matchingMode)
        assertEquals(false, s3.exhaustiveMatching)
        assertEquals(5.0, s3.episodeWindowYears)
        assertEquals(20, s3.knnK)
        assertEquals(0.60, s3.simFloor)
        assertEquals(0.93, s3.simAutoRepeat)
        assertEquals(0.85, s3.entityMergeThreshold)
        assertEquals(0.25, s3.entityIdfFloor)
        // VA-77 dials: hub gate + per-claim cap (owner-signed replay value) + thinking budget.
        assertEquals(0.30, s3.coMentionHubShare)
        assertEquals(80, s3.judgeCandidatesPerClaim)
        assertEquals(512, s3.judgeThinkingBudget)
        assertEquals(5, s3.ensembleK)
        assertEquals(0.7, s3.ensembleTemperature)
        assertEquals("ALTERNATE", s3.ensembleOrderings)
        assertEquals(0.55, s3.judgeConfidenceFloor)
        assertEquals(40, s3.judgePairsPerPoll)
        assertEquals(8, s3.judgeBatchSize)
        assertEquals(
            listOf("EMPLOYER", "ROLE", "RESIDENCE", "EDUCATION_ENROLLMENT"),
            s3.stateSlotTypes
        )
        assertEquals(mapOf("SKILL" to 5.0), s3.volatileHalfLifeYears)
        assertEquals(20, s3.maxIterations)
        assertEquals(0.005, s3.epsilon)
        assertEquals(0.5, s3.damping)
        assertEquals(0.8, s3.corroborationWeight)
        assertEquals(1.2, s3.contradictionWeight)
        assertEquals(0.4, s3.dependenceDamping)
        assertEquals(0.6, s3.explanationMitigation)
        assertEquals(0.2, s3.anchorPlasticity)
        assertEquals(0.4, s3.againstInterestBonus)
        assertEquals(-0.5, s3.inferredPenalty)
        assertEquals(0.65, s3.selfPraiseCeiling)
        assertEquals(5, s3.trustShrinkage)
        assertEquals(0.75, s3.tierHigh)
        assertEquals(0.45, s3.tierMedium)
        assertEquals(false, s3.crossSubjectEvidence)
        assertEquals(true, s3.publishRequiresReview)
        assertEquals(false, s3.dryRun)
        assertEquals(Duration.ofMinutes(2), s3.connectionLivenessCheckTimeout)
        assertEquals(Duration.ofMinutes(30), s3.maxConnectionLifetime)
        assertEquals(Duration.ofSeconds(30), s3.maxTransactionRetryTime)
        assertEquals(Duration.ofMinutes(15), s3.phaseTimeout)
    }

    @Test
    fun `kebab-case keys and duration strings bind like application yml`() {
        val source =
            MapConfigurationPropertySource(
                mapOf(
                    "app.stage3.neo4j-uri" to "neo4j+s://abcd1234.databases.neo4j.io",
                    "app.stage3.knn-k" to "33",
                    "app.stage3.entity-batch-per-poll" to "7",
                    "app.stage3.sim-floor" to "0.7",
                    "app.stage3.connection-liveness-check-timeout" to "90s",
                    "app.stage3.volatile-half-life-years.SKILL" to "4.5",
                    "app.stage3.dry-run" to "true",
                    "app.stage3.matching-mode" to "exhaustive",
                    "app.stage3.episode-window-years" to "3",
                    "app.stage3.judge-thinking-budget" to "1024",
                    "app.stage3.judge-candidates-per-claim" to "40",
                )
            )
        val bound =
            Binder(source).bind("app.stage3", Bindable.of(AppProperties.Stage3::class.java)).get()
        assertEquals("neo4j+s://abcd1234.databases.neo4j.io", bound.neo4jUri)
        assertEquals(33, bound.knnK)
        assertEquals(7, bound.entityBatchPerPoll)
        assertEquals(0.7, bound.simFloor)
        assertEquals(Duration.ofSeconds(90), bound.connectionLivenessCheckTimeout)
        assertEquals(mapOf("SKILL" to 4.5), bound.volatileHalfLifeYears)
        assertEquals(true, bound.dryRun)
        // The mode flag is case-insensitive (config files say "exhaustive", code asks the flag).
        assertEquals(true, bound.exhaustiveMatching)
        assertEquals(3.0, bound.episodeWindowYears)
        assertEquals(1024, bound.judgeThinkingBudget)
        assertEquals(40, bound.judgeCandidatesPerClaim)
        // Untouched keys keep their constructor defaults.
        assertEquals(5, bound.ensembleK)
    }
}

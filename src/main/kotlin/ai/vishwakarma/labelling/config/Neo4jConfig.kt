package ai.vishwakarma.labelling.config

import java.util.concurrent.TimeUnit
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Logging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The Stage 3 claim-graph driver (LLD §8.1) — plain `neo4j-java-driver`, no Spring Data Neo4j by
 * design (the access pattern is MERGE/MATCH + vector queries, not entity CRUD; see
 * `stage3/Stage3GraphRepository`).
 *
 * Constructing the driver opens no connection (first use does), so the app boots fine with Neo4j
 * down — the health indicator and the run-submit guard surface unreachability instead.
 *
 * The pool settings are the AuraDB session-loss defense: Aura's load balancer silently drops
 * connections idled past a few minutes, which otherwise surfaces as SessionExpired /
 * ServiceUnavailable on the next use of a stale pooled connection. Liveness-check + bounded
 * lifetime keep stale connections out of circulation, and every repository access runs in a
 * *managed* transaction (`Session.executeRead/executeWrite`), which the driver retries on
 * transient/session-expired failures for up to [AppProperties.Stage3.maxTransactionRetryTime].
 */
@Configuration
class Neo4jConfig {

    @Bean // Driver is AutoCloseable — Spring infers close() at shutdown.
    fun neo4jDriver(props: AppProperties): Driver {
        val s3 = props.stage3
        val config =
            Config.builder()
                // Idle-past-this connections are liveness-tested (and replaced when dead) before
                // reuse — keep it comfortably under Aura's idle-kill horizon.
                .withConnectionLivenessCheckTimeout(
                    s3.connectionLivenessCheckTimeout.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
                // Hard age cap — forces periodic refresh below load-balancer horizons.
                .withMaxConnectionLifetime(
                    s3.maxConnectionLifetime.toMillis(),
                    TimeUnit.MILLISECONDS
                )
                // Managed-transaction retry window (SessionExpired / ServiceUnavailable /
                // transient).
                .withMaxTransactionRetryTime(
                    s3.maxTransactionRetryTime.toMillis(),
                    TimeUnit.MILLISECONDS
                )
                .withLogging(Logging.slf4j())
                .build()
        // Encryption comes from the URI scheme (neo4j+s:// on Aura, bolt:// on local Docker) —
        // never set on Config as well, the driver rejects the combination.
        return GraphDatabase.driver(
            s3.neo4jUri,
            AuthTokens.basic(s3.neo4jUser, s3.neo4jPassword),
            config
        )
    }
}

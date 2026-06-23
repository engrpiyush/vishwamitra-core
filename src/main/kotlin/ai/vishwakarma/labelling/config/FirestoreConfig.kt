package ai.vishwakarma.labelling.config

import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.NoCredentials
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.IOException

/**
 * Builds the Firestore client for the named database (`vishwakarma-labelling`).
 *
 * Connection precedence:
 *  - If `FIRESTORE_EMULATOR_HOST` is set (local dev), talk to the emulator with no credentials.
 *  - Otherwise use Application Default Credentials (Cloud Run SA). If ADC is unavailable the bean
 *    still builds (with NoCredentials) so the context loads; RPCs will fail until creds exist.
 */
@Configuration
class FirestoreConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun firestore(props: AppProperties): Firestore {
        val builder = FirestoreOptions.newBuilder()
            .setProjectId(props.gcp.projectId)
            .setDatabaseId(props.gcp.firestoreDatabase)

        val emulator = System.getenv("FIRESTORE_EMULATOR_HOST")
        if (!emulator.isNullOrBlank()) {
            log.info("Firestore: using emulator at {} (db={})", emulator, props.gcp.firestoreDatabase)
            // The emulator speaks plaintext h2c — wire an explicit plaintext channel + no credentials.
            builder
                .setCredentials(NoCredentials.getInstance())
                .setHost(emulator)
                .setChannelProvider(
                    InstantiatingGrpcChannelProvider.newBuilder()
                        .setEndpoint(emulator)
                        .setChannelConfigurator { channel -> channel.usePlaintext() }
                        .build(),
                )
        } else {
            try {
                builder.setCredentials(GoogleCredentials.getApplicationDefault())
                log.info("Firestore: using Application Default Credentials (db={})", props.gcp.firestoreDatabase)
            } catch (e: IOException) {
                log.warn("Firestore: no ADC found ({}); RPCs will fail until credentials or emulator are configured", e.message)
                builder.setCredentials(NoCredentials.getInstance())
            }
        }

        return builder.build().service
    }
}

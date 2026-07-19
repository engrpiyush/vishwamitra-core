package ai.vishwakarma.labelling.stage3.gatekeeper

import ai.vishwakarma.labelling.config.AppProperties
import com.google.api.gax.retrying.RetrySettings
import com.google.cloud.pubsub.v1.Publisher
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import com.google.pubsub.v1.TopicName
import jakarta.annotation.PreDestroy
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.threeten.bp.Duration as GaxDuration

/**
 * Publishes one [GatekeeperRunRequest] to the `gatekeeper-requests` topic (Gatekeeper LLD §5).
 *
 * This is the only message this application publishes anywhere. The seam exists so dev and tests
 * exercise the whole JUDGE path — payload construction included — without a topic, mirroring the
 * `MailTransport` idiom.
 */
interface GatekeeperPublisher {

    /**
     * Publish, or throw. Callers translate a throw into a visible `PUBLISH_FAILED` on the run
     * rather than a lost trigger — the one thing this integration must never do quietly is drop a
     * request the operator asked for (LLD §14).
     */
    fun publish(request: GatekeeperRunRequest): String

    /** Operator-facing posture for the status endpoint and the run page. */
    val posture: String
}

/**
 * The real transport. Retry/backoff is gax's own [RetrySettings] rather than the app's
 * `VertexBackoff`: that helper catches Spring's `RestClientResponseException` and would not match a
 * gax `ApiException`, so it would rethrow on the first failure while looking like it retried.
 */
class PubSubGatekeeperPublisher(private val props: AppProperties) : GatekeeperPublisher {

    private val log = LoggerFactory.getLogger(PubSubGatekeeperPublisher::class.java)
    private val cfg = props.gatekeeper

    private val topicName: TopicName =
        TopicName.of(cfg.projectId.ifBlank { props.gcp.projectId }, cfg.topic)

    /** Non-null once the lazy [publisher] has actually been built — the shutdown-hook guard. */
    @Volatile private var built: Publisher? = null

    /**
     * Built on first use, not at startup: a `Publisher` opens a channel and starts batching
     * threads, and a boot that cannot reach Pub/Sub must still serve every other surface (the
     * FirestoreConfig posture — never fail the context on a transport).
     */
    private val publisher: Publisher by lazy {
        Publisher.newBuilder(topicName)
            .setRetrySettings(
                RetrySettings.newBuilder()
                    .setTotalTimeout(GaxDuration.ofMillis(cfg.publishTimeout.toMillis()))
                    .setInitialRpcTimeout(GaxDuration.ofMillis(cfg.publishTimeout.toMillis()))
                    .setMaxRpcTimeout(GaxDuration.ofMillis(cfg.publishTimeout.toMillis()))
                    .setInitialRetryDelay(
                        GaxDuration.ofMillis(cfg.publishInitialBackoff.toMillis())
                    )
                    .setMaxRetryDelay(GaxDuration.ofMillis(cfg.publishMaxBackoff.toMillis()))
                    .setRetryDelayMultiplier(2.0)
                    .setMaxAttempts(cfg.publishMaxAttempts)
                    .build()
            )
            .build()
            .also { built = it }
    }

    override val posture: String
        get() = "publishing to ${topicName.topic} in ${topicName.project}"

    override fun publish(request: GatekeeperRunRequest): String {
        val message =
            PubsubMessage.newBuilder().setData(ByteString.copyFrom(request.toBytes())).build()
        // Blocking on purpose: the caller is a poll tick that must record the outcome on the run
        // before it returns, and a fire-and-forget future would let a failure escape unrecorded.
        val messageId =
            publisher.publish(message).get(cfg.publishTimeout.toSeconds(), TimeUnit.SECONDS)
        log.info(
            "Gatekeeper: published {} {} for stage3Run {} (runRequestId {}, messageId {})",
            request.mode,
            request.gate,
            request.stage3RunId,
            request.runRequestId,
            messageId,
        )
        return messageId
    }

    @PreDestroy
    fun shutdown() {
        // `Publisher` is not AutoCloseable — Spring cannot infer this the way it does for the
        // Neo4j Driver, so the hook is explicit or the batching threads outlive the context. Guard
        // on `built`: a context that never published must not construct a real gRPC channel here
        // just to tear it down (touching the `by lazy` would do exactly that).
        built?.let {
            runCatching {
                it.shutdown()
                it.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }
}

/**
 * Dev/test transport: logs the exact bytes that would have gone on the wire and returns a fake
 * message id. Every step before the publish — id minting, payload construction, run bookkeeping —
 * runs for real, so the dry-run path exercises the contract rather than skipping it.
 */
class NoOpGatekeeperPublisher(private val reason: String) : GatekeeperPublisher {

    private val log = LoggerFactory.getLogger(NoOpGatekeeperPublisher::class.java)

    override val posture: String
        get() = "DRY-RUN — $reason"

    override fun publish(request: GatekeeperRunRequest): String {
        // WARN, not DEBUG, and it names the flag: the mode dial is live-editable while this
        // transport is restart-bound, so "I set judgeMode=GATEKEEPER and nothing happened" is a
        // reachable and otherwise silent state.
        log.warn(
            "Gatekeeper publish SUPPRESSED ({}) — {} {} for stage3Run {} would have been sent: {}",
            reason,
            request.mode,
            request.gate,
            request.stage3RunId,
            String(request.toBytes(), Charsets.UTF_8),
        )
        return "dryrun-${request.runRequestId}"
    }
}

/**
 * Picks the transport. A blank topic is an independent hard no-op alongside the flag, so a box that
 * was never configured cannot publish even if the flag is flipped off (the blank-bucket convention
 * used for `intake-bucket` and the checkpoint locator).
 */
@Configuration
class GatekeeperPublisherConfig {

    @Bean
    fun gatekeeperPublisher(props: AppProperties): GatekeeperPublisher =
        when {
            props.gatekeeper.dryRun -> NoOpGatekeeperPublisher("app.gatekeeper.dry-run=true")
            props.gatekeeper.topic.isBlank() ->
                NoOpGatekeeperPublisher("app.gatekeeper.topic is blank")
            else -> PubSubGatekeeperPublisher(props)
        }
}

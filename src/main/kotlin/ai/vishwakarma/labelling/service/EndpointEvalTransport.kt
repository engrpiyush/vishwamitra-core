package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.ModelVersion
import ai.vishwakarma.labelling.domain.ServingState
import ai.vishwakarma.labelling.serving.ChatRequest
import ai.vishwakarma.labelling.stage4.DryRunEvalReplies
import ai.vishwakarma.labelling.stage4.EvalTransportState
import ai.vishwakarma.labelling.stage4.Stage4EvalTransport
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Short-lived Vertex endpoint transport (VA-67): the automated §14 path. Everything rides the VA-80
 * serving control plane — [AdvocateServingService] owns the guards (READY + checkpoint, the
 * one-live invariant against operator serves AND advocate windows) and the `ModelVersion` serving
 * state, so the models/serve page and the orphan-deployment monitoring stay the single truth while
 * an eval occupies the endpoint. Under `app.serving.dry-run` the deploy settles instantly and
 * [chat] answers scripted (no endpoint exists to ask).
 */
@Component
class EndpointEvalTransport(
    private val serving: AdvocateServingService,
    private val props: AppProperties,
) : Stage4EvalTransport {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = "endpoint"

    override fun begin(version: ModelVersion): EvalTransportState =
        serving.serve(version.id).fold({ error(it.message) }, { stateOf(it.servingState) })

    override fun advance(version: ModelVersion): EvalTransportState =
        when (version.servingState) {
            ServingState.DEPLOYING ->
                serving.poll(version.id).fold({ error(it.message) }, { stateOf(it.servingState) })
            else -> stateOf(version.servingState)
        }

    override fun chat(version: ModelVersion, request: ChatRequest): String {
        if (props.serving.dryRun) {
            return DryRunEvalReplies.reply(request.messages.lastOrNull()?.content ?: "")
        }
        val backend = checkNotNull(serving.backend()) { "no serving backend configured" }
        return backend.chat(request)
    }

    override fun release(version: ModelVersion): EvalTransportState {
        val current = serving.poll(version.id).getOrNull()?.servingState ?: version.servingState
        return when (current) {
            ServingState.NONE -> EvalTransportState.RELEASED
            ServingState.TEARING_DOWN -> EvalTransportState.RELEASING
            // Deploy still in flight: nothing undeployable yet — keep pumping; the poll above
            // advances the LRO and the next release() tick catches LIVE/FAILED.
            ServingState.DEPLOYING -> EvalTransportState.RELEASING
            ServingState.LIVE,
            ServingState.FAILED ->
                serving
                    .teardown(version.id)
                    .fold(
                        {
                            log.warn("Eval release of {} could not tear down: {}", version.id, it)
                            EvalTransportState.FAILED
                        },
                        {
                            if (it.servingState == ServingState.NONE) EvalTransportState.RELEASED
                            else EvalTransportState.RELEASING
                        },
                    )
        }
    }

    /** Deploy-phase view of a serving state; NONE mid-deploy means the serve vanished under us. */
    private fun stateOf(state: ServingState): EvalTransportState =
        when (state) {
            ServingState.DEPLOYING -> EvalTransportState.PREPARING
            ServingState.LIVE -> EvalTransportState.READY
            ServingState.TEARING_DOWN -> EvalTransportState.RELEASING
            ServingState.NONE -> EvalTransportState.FAILED
            ServingState.FAILED -> EvalTransportState.FAILED
        }
}

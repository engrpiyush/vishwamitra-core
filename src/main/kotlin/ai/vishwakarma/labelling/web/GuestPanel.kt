package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.security.SubjectCtx
import org.springframework.ui.Model

/**
 * Shared model attributes for the §8.3 guest panel — both the landing GET and the wall POST
 * re-render (`subject/landing`) go through here so the panel variants stay in one place.
 */
object GuestPanel {

    /** The panel's state-adaptive variant (LLD §8.3): which copy/form the guest sees. */
    enum class State {
        /** Valid guest capability session on the request — chat surface (VA-43) goes here. */
        CONNECTED,
        /** Advocate LIVE — the access-code form. */
        LIVE,
        /** Window being provisioned — "getting ready" + page-driven poll. */
        PROVISIONING,
        /** UNPROVISIONED / DEPROVISIONING / DEPLOY_FAILED — request-provisioning CTA. */
        OFFLINE,
        /** NOT_BUILT / BUILDING — nothing to talk to yet. */
        BUILDING,
    }

    fun populate(model: Model, ctx: SubjectCtx, state: AdvocateState, connected: Boolean) {
        model.addAttribute("ctx", ctx)
        model.addAttribute("pageTitle", ctx.displayName)
        model.addAttribute("guestPanelState", stateFor(state, connected).name)
    }

    private fun stateFor(state: AdvocateState, connected: Boolean): State =
        when {
            connected -> State.CONNECTED
            state == AdvocateState.LIVE -> State.LIVE
            state == AdvocateState.PROVISIONING -> State.PROVISIONING
            state == AdvocateState.UNPROVISIONED ||
                state == AdvocateState.DEPROVISIONING ||
                state == AdvocateState.DEPLOY_FAILED -> State.OFFLINE
            else -> State.BUILDING
        }
}

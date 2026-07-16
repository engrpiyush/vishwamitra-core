package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.security.SubjectCtx
import org.springframework.ui.Model

/**
 * Shared model attributes for the §8.3 guest panel — both the landing GET and the wall POST
 * re-render (`subject/landing`) go through here so the panel variants stay in one place.
 */
object GuestPanel {

    /**
     * The panel's state-adaptive variant (LLD §8.3): which copy/form the guest sees. A guest with a
     * valid capability session never reaches the landing anymore — the §8.3 v1.1 decision tree
     * ([SubjectRoot], VA-43) sends them straight to chat.
     */
    enum class State {
        /** Advocate LIVE — the access-code form. */
        LIVE,
        /** Window being provisioned — "getting ready" + page-driven poll. */
        PROVISIONING,
        /** UNPROVISIONED / DEPROVISIONING / DEPLOY_FAILED — request-provisioning CTA. */
        OFFLINE,
        /** NOT_BUILT / BUILDING — nothing to talk to yet. */
        BUILDING,
    }

    fun populate(model: Model, ctx: SubjectCtx, state: AdvocateState) {
        model.addAttribute("ctx", ctx)
        model.addAttribute("pageTitle", ctx.displayName)
        model.addAttribute("guestPanelState", stateFor(state).name)
    }

    private fun stateFor(state: AdvocateState): State =
        when (state) {
            AdvocateState.LIVE -> State.LIVE
            AdvocateState.PROVISIONING -> State.PROVISIONING
            AdvocateState.UNPROVISIONED,
            AdvocateState.DEPROVISIONING,
            AdvocateState.DEPLOY_FAILED -> State.OFFLINE
            else -> State.BUILDING
        }
}

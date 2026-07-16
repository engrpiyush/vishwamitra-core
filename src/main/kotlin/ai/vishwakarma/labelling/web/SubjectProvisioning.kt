package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.domain.WindowPreset

/**
 * The S9 vocabulary bridge (VA-40, LLD §7.3/§8.2): the provisioning switch renders ONLY what this
 * object emits — the grep-able §12.3 rule the training surface established. DEPROVISIONING and
 * DEPLOY_FAILED collapse into "not available right now" (failure detail is operator vocabulary);
 * DEPLOY_FAILED keeps the switch so the subject can simply try again (§7.3 retry edge).
 */
object SubjectProvisioning {

    /** Which card the switch page shows. Names double as the poll JSON's only vocabulary. */
    enum class SwitchView(val headline: String, val blurb: String) {
        /** NOT_BUILT / BUILDING — nothing to switch on yet. */
        TRAINING(
            "Still in training",
            "Your advocate isn't ready to talk yet — it unlocks once training completes.",
        ),
        /** UNPROVISIONED / DEPLOY_FAILED — offline, and the subject may turn it on. */
        OFFLINE(
            "Not available right now",
            "Your advocate is offline. Turn it on for a while so guests can talk to it.",
        ),
        /** PROVISIONING — the long cold start; the open page polls. */
        GETTING_READY(
            "Getting your advocate ready…",
            "This can take up to about half an hour. We'll email you the moment it's live — " +
                "you don't need to keep this page open.",
        ),
        /** LIVE — countdown + the off switch. */
        ONLINE("Your advocate is online", "Guests with an access code can talk to it right now."),
        /** DEPROVISIONING — wrapping up; nothing to do, the page refreshes itself. */
        UNAVAILABLE(
            "Not available right now",
            "Your advocate is wrapping up its last session — check back in a few minutes.",
        );

        /** True when the page should keep polling (a transition is in flight). */
        val polling: Boolean
            get() = this == GETTING_READY || this == UNAVAILABLE
    }

    fun viewFor(state: AdvocateState): SwitchView =
        when (state) {
            AdvocateState.NOT_BUILT,
            AdvocateState.BUILDING -> SwitchView.TRAINING
            AdvocateState.UNPROVISIONED,
            AdvocateState.DEPLOY_FAILED -> SwitchView.OFFLINE
            AdvocateState.PROVISIONING -> SwitchView.GETTING_READY
            AdvocateState.LIVE -> SwitchView.ONLINE
            AdvocateState.DEPROVISIONING -> SwitchView.UNAVAILABLE
        }

    /** One radio per F10 preset — the labels are the subject-world names for D1/D3/W1. */
    data class PresetOption(val name: String, val label: String)

    fun presets(): List<PresetOption> =
        listOf(
            PresetOption(WindowPreset.D1.name, "1 day"),
            PresetOption(WindowPreset.D3.name, "3 days"),
            PresetOption(WindowPreset.W1.name, "1 week"),
        )
}

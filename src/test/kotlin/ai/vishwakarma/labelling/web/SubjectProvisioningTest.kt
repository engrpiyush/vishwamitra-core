package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.web.SubjectProvisioning.SwitchView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The S9 vocabulary bridge (VA-40): the §8.2/§12.3 rule — the switch page renders only what
 * [SubjectProvisioning] emits, and DEPROVISIONING/DEPLOY_FAILED collapse into "not available".
 */
class SubjectProvisioningTest {

    @Test
    fun `every advocate state maps to a friendly view`() {
        assertEquals(SwitchView.TRAINING, SubjectProvisioning.viewFor(AdvocateState.NOT_BUILT))
        assertEquals(SwitchView.TRAINING, SubjectProvisioning.viewFor(AdvocateState.BUILDING))
        assertEquals(SwitchView.OFFLINE, SubjectProvisioning.viewFor(AdvocateState.UNPROVISIONED))
        assertEquals(SwitchView.OFFLINE, SubjectProvisioning.viewFor(AdvocateState.DEPLOY_FAILED))
        assertEquals(
            SwitchView.GETTING_READY,
            SubjectProvisioning.viewFor(AdvocateState.PROVISIONING),
        )
        assertEquals(SwitchView.ONLINE, SubjectProvisioning.viewFor(AdvocateState.LIVE))
        assertEquals(
            SwitchView.UNAVAILABLE,
            SubjectProvisioning.viewFor(AdvocateState.DEPROVISIONING),
        )
    }

    @Test
    fun `failure collapses to the same copy as wrapping up — no machine vocabulary`() {
        val failed = SubjectProvisioning.viewFor(AdvocateState.DEPLOY_FAILED)
        val wrapping = SubjectProvisioning.viewFor(AdvocateState.DEPROVISIONING)
        assertEquals("Not available right now", failed.headline)
        assertEquals("Not available right now", wrapping.headline)
        // The grep-able §12.3 sweep: no substrate words anywhere in the subject copy.
        for (view in SwitchView.entries) {
            val copy = (view.headline + " " + view.blurb).lowercase()
            for (banned in listOf("vertex", "endpoint", "gpu", "deploy", "replica", "v100")) {
                assertFalse(copy.contains(banned), "'$banned' leaked into $view copy")
            }
        }
    }

    @Test
    fun `only in-flight views poll`() {
        assertTrue(SwitchView.GETTING_READY.polling)
        assertTrue(SwitchView.UNAVAILABLE.polling)
        assertFalse(SwitchView.ONLINE.polling)
        assertFalse(SwitchView.OFFLINE.polling)
        assertFalse(SwitchView.TRAINING.polling)
    }

    @Test
    fun `presets are exactly the F10 trio with friendly labels`() {
        assertEquals(
            listOf("D1" to "1 day", "D3" to "3 days", "W1" to "1 week"),
            SubjectProvisioning.presets().map { it.name to it.label },
        )
    }
}

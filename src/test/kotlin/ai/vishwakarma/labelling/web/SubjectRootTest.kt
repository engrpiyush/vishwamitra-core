package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateState
import ai.vishwakarma.labelling.web.SubjectRoot.View
import kotlin.test.Test
import kotlin.test.assertEquals

/** The §8.3 v1.1 root decision tree (VA-43) — order is the contract. */
class SubjectRootTest {

    private fun view(
        operator: Boolean = false,
        bound: String? = null,
        guest: Boolean = false,
        state: AdvocateState = AdvocateState.LIVE,
    ) = SubjectRoot.viewFor(operator, bound, "host-subject", guest, state)

    @Test
    fun `operator and subject-of-host chat with the ribbon while LIVE`() {
        assertEquals(View.CHAT_SELF, view(operator = true))
        assertEquals(View.CHAT_SELF, view(bound = "host-subject"))
    }

    @Test
    fun `self falls back to the status card when not LIVE`() {
        for (state in AdvocateState.entries.filter { it != AdvocateState.LIVE }) {
            assertEquals(View.STATUS_SELF, view(operator = true, state = state))
            assertEquals(View.STATUS_SELF, view(bound = "host-subject", state = state))
        }
    }

    @Test
    fun `self wins over a guest cookie`() {
        assertEquals(View.CHAT_SELF, view(bound = "host-subject", guest = true))
        assertEquals(
            View.STATUS_SELF,
            view(operator = true, guest = true, state = AdvocateState.UNPROVISIONED),
        )
    }

    @Test
    fun `a valid guest session chats whatever the serving state`() {
        assertEquals(View.CHAT_GUEST, view(guest = true))
        // Not LIVE: the chat page renders and the send maps to the friendly 409 (§8.2).
        assertEquals(View.CHAT_GUEST, view(guest = true, state = AdvocateState.DEPROVISIONING))
    }

    @Test
    fun `a SUBJECT signed in on someone else's host is just a visitor`() {
        assertEquals(View.LANDING, view(bound = "another-subject"))
        assertEquals(View.CHAT_GUEST, view(bound = "another-subject", guest = true))
    }

    @Test
    fun `no gate passed renders the split landing`() {
        assertEquals(View.LANDING, view())
        assertEquals(View.LANDING, view(state = AdvocateState.NOT_BUILT))
    }
}

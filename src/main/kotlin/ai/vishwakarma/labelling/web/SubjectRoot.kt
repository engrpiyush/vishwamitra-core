package ai.vishwakarma.labelling.web

import ai.vishwakarma.labelling.domain.AdvocateState

/**
 * The §8.3 v1.1 root decision tree (VA-43), pure and unit-tested (the vocabulary-bridge idiom).
 * Order matters and mirrors the LLD exactly: an operator or the host's own subject wins over a
 * guest cookie; a signed-in SUBJECT on someone ELSE's host is neither — they fall through to the
 * guest branch or the split landing like any visitor.
 */
object SubjectRoot {

    /** What the root renders. */
    enum class View {
        /** Operator/subject with the advocate LIVE — chat + "testing your own advocate" ribbon. */
        CHAT_SELF,
        /** Operator/subject, advocate not LIVE — status card + link to S9. */
        STATUS_SELF,
        /** Valid guest capability session — chat + the F9 disclosure banner. */
        CHAT_GUEST,
        /** No gate passed — the S11 split landing. */
        LANDING,
    }

    fun viewFor(
        operator: Boolean,
        boundSubjectId: String?,
        hostSubjectId: String,
        guest: Boolean,
        state: AdvocateState,
    ): View {
        val self = operator || boundSubjectId == hostSubjectId
        return when {
            self && state == AdvocateState.LIVE -> View.CHAT_SELF
            self -> View.STATUS_SELF
            guest -> View.CHAT_GUEST
            else -> View.LANDING
        }
    }
}

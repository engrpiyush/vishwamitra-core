package ai.vishwakarma.labelling.security

import jakarta.servlet.http.HttpServletRequest

/**
 * Host-resolved subject tenancy for the current request (VA-30, LLD §4.3), attached by
 * [SubjectHostFilter] after the handle resolves to an ACTIVE subject. Its presence IS the "subject
 * world" signal: the subject security chain matches on it and subject-face controllers 404 without
 * it.
 */
data class SubjectCtx(
    val subjectId: String,
    val handle: String,
    val displayName: String,
) {
    companion object {
        /** Request attribute key carrying the resolved context. */
        const val ATTR = "SUBJECT_CTX"

        fun of(request: HttpServletRequest): SubjectCtx? = request.getAttribute(ATTR) as? SubjectCtx
    }
}

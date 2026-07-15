package ai.vishwakarma.labelling.security

import ai.vishwakarma.labelling.domain.Role
import ai.vishwakarma.labelling.service.TermsService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * The S1 terms gate (VA-30, LLD §4.4/§13.2): an authenticated SUBJECT on their OWN host is
 * redirected to `/terms` until `terms_acceptances` covers the current policy versions. Operators
 * are exempt (they accepted operator terms out-of-band); guests and a SUBJECT visiting someone
 * else's host are untouched (they only ever see public/denied pages). Registered on the subject
 * world's internal `/s` paths only (WebConfig), so the operator app never pays the check.
 */
@Component
class TermsGateInterceptor(private val terms: TermsService) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val ctx = SubjectCtx.of(request) ?: return true
        val path = request.requestURI
        if (path == "/s/terms" || path.startsWith("/s/terms/")) return true
        if (CurrentUser.role() != Role.SUBJECT) return true
        if (CurrentUser.subjectId() != ctx.subjectId) return true
        val email = CurrentUser.email() ?: return true
        if (terms.hasCurrentAcceptances(email)) return true
        if (request.method == "GET") {
            // Remember the pretty (external) path so accepting bounces straight back.
            request.session.setAttribute(RETURN_TO, path.removePrefix("/s").ifEmpty { "/" })
        }
        response.sendRedirect("/terms")
        return false
    }

    companion object {
        /** Session attribute holding the post-acceptance redirect target (external path). */
        const val RETURN_TO = "termsReturnTo"
    }
}

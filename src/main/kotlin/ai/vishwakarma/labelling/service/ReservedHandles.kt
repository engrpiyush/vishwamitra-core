package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * The §17.2 reserved-handle set (VA-31): the literal list, every top-level operator route (derived
 * mechanically from the live controller mappings so new operator routes can never collide with
 * future handles), and the operator domain's own first label (`labelling` in prod — a subject could
 * otherwise claim it and shadow the operator app's name). Creation-time validation is the
 * enforcement point — a handle that can't be created can't resolve on a host, so the host filter
 * needs no reserved check (the dev-profile seeded handle `dev` is the sanctioned exception, written
 * directly by DataSeeder). A curated keyword expansion (brand/system/abuse terms) is backlogged
 * separately.
 */
@Component
class ReservedHandles(
    private val mappings: ObjectProvider<RequestMappingHandlerMapping>,
    props: AppProperties,
) {

    private val operatorLabel: String =
        props.product.operatorDomain.substringBefore('.').lowercase()

    /** Lazily harvested on first validation — MVC is fully mapped well before any admin form. */
    private val derived: Set<String> by lazy {
        mappings.ifAvailable
            ?.handlerMethods
            ?.keys
            .orEmpty()
            .flatMap { info -> info.pathPatternsCondition?.patterns.orEmpty() }
            .mapNotNull { pattern ->
                pattern.patternString.removePrefix("/").substringBefore('/').takeIf {
                    it.isNotBlank() && !it.startsWith("{") && !it.contains('*')
                }
            }
            .map { it.lowercase() }
            .toSet()
    }

    fun contains(handle: String): Boolean {
        val h = handle.lowercase()
        return h == operatorLabel || h in LITERALS || h in derived
    }

    companion object {
        /**
         * §17.2 literals. Additions to the wiki list: `auth` (the VA-70 central OAuth host is a
         * reserved handle, §4.2 v1.1) and `s` (the subject world's internal rewrite prefix).
         */
        val LITERALS: Set<String> =
            setOf(
                "www",
                "api",
                "app",
                "admin",
                "mail",
                "smtp",
                "docs",
                "status",
                "blog",
                "dev",
                "test",
                "staging",
                "internal",
                "operator",
                "console",
                "support",
                "help",
                "legal",
                "privacy",
                "terms",
                "assets",
                "static",
                "cdn",
                "auth",
                "s",
            )
    }
}

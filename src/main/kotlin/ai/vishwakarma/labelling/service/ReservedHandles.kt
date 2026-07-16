package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.config.AppProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * The §17.2 reserved-handle set (VA-31 + VA-90): the curated literal list, every top-level operator
 * route (derived mechanically from the live controller mappings so new operator routes can never
 * collide with future handles), and the operator domain's own first label (`labelling` in prod — a
 * subject could otherwise claim it and shadow the operator app's name). Creation-time validation is
 * the enforcement point — a handle that can't be created can't resolve on a host, so the host
 * filter needs no reserved check (the dev-profile seeded handle `dev` is the sanctioned exception,
 * written directly by DataSeeder).
 *
 * Governance (VA-90 decision, 2026-07-16): the list is CODE-managed, not a §14A console config — it
 * changes at most a few times a year, a bad edit silently breaks member provisioning, and the two
 * derived arms already extend it without deploys where extension actually happens (new routes,
 * domain moves). Additions go through PR review like any other security control.
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
         * The original §17.2 list. Additions to the wiki list: `auth` (the VA-70 central OAuth host
         * is a reserved handle, §4.2 v1.1) and `s` (the subject world's internal rewrite prefix).
         * Entries shorter than the 6-char handle minimum are unreachable via format alone but stay
         * listed — the format rule may loosen; this list must not care.
         */
        private val SYSTEM =
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

        /** VA-90: brand and product terms — a handle must never impersonate the platform. */
        private val BRAND =
            setOf(
                "vishwakarma",
                "vishwakarmaai",
                "vishx",
                "vishwamitra",
                "neo",
                "advocate",
                "advocates",
                "labelling",
                "labeling",
            )

        /** VA-90: mail/infra hostnames that commonly get subdomain records pointed at them. */
        private val INFRA =
            setOf(
                "imap",
                "pop",
                "pop3",
                "ftp",
                "sftp",
                "ssh",
                "vpn",
                "proxy",
                "ns",
                "ns1",
                "ns2",
                "mx",
                "mx1",
                "mx2",
                "webmail",
                "autodiscover",
                "autoconfig",
                "localhost",
                "server",
                "gateway",
                "monitor",
                "metrics",
                "backup",
                "database",
                "sandbox",
                "production",
            )

        /** VA-90: auth/security bait — names phishing pages would want as their host label. */
        private val AUTH_BAIT =
            setOf(
                "login",
                "signin",
                "signout",
                "signup",
                "register",
                "account",
                "accounts",
                "password",
                "passwords",
                "security",
                "secure",
                "verify",
                "verification",
                "billing",
                "payments",
                "payment",
                "invoice",
                "invoices",
                "checkout",
                "wallet",
                "settings",
                "profile",
                "session",
                "sessions",
                "token",
                "tokens",
            )

        /** VA-90: impersonation/abuse pass — role words that read as "someone official". */
        private val IMPERSONATION =
            setOf(
                "official",
                "root",
                "system",
                "sysadmin",
                "administrator",
                "administrators",
                "moderator",
                "moderators",
                "webmaster",
                "postmaster",
                "hostmaster",
                "abuse",
                "noreply",
                "donotreply",
                "info",
                "contact",
                "sales",
                "marketing",
                "press",
                "media",
                "careers",
                "team",
                "staff",
                "trust",
                "safety",
                "feedback",
                "service",
                "services",
                "notifications",
            )

        /** §17.2 literals (VA-90 curated union) — creation-time validation checks this set. */
        val LITERALS: Set<String> = SYSTEM + BRAND + INFRA + AUTH_BAIT + IMPERSONATION
    }
}

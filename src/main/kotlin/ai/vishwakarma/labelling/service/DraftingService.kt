package ai.vishwakarma.labelling.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ai.vishwakarma.labelling.domain.ExampleTags
import ai.vishwakarma.labelling.domain.Scenario
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.drafting.CandidatePair
import ai.vishwakarma.labelling.drafting.DraftingProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Selects an available [DraftingProvider] (in providers-catalog order) and runs the requested draft.
 * Any missing-config or runtime error degrades to manual: callers get a Left and keep editing by hand
 * (this is the "ManualOnly" fallback referenced in the plan — no separate bean needed).
 */
@Service
class DraftingService(
    private val providers: List<DraftingProvider>,
    private val providerService: ProviderService,
    private val catalog: CatalogService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun activeProvider(): DraftingProvider? =
        providers.filter { it.available() }
            .minByOrNull { providerService.knownProviders.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    fun activeProviderId(): String? = activeProvider()?.id

    fun draftConversation(scenario: Scenario?, tags: ExampleTags): Either<DomainError, List<Turn>> =
        run { activeProvider()?.let { p -> attempt(p.id) { p.draftConversation(scenario, tags, tools()) } } ?: manual() }

    fun draftNextTurn(turns: List<Turn>): Either<DomainError, Turn> =
        run { activeProvider()?.let { p -> attempt(p.id) { p.draftNextTurn(turns, tools()) } } ?: manual() }

    fun draftTwoCandidates(promptTurns: List<Turn>): Either<DomainError, CandidatePair> =
        run { activeProvider()?.let { p -> attempt(p.id) { p.draftTwoCandidates(promptTurns, tools()) } } ?: manual() }

    private fun tools() = catalog.list(includeDeprecated = false)

    private fun <T> attempt(providerId: String, block: () -> T): Either<DomainError, T> =
        runCatching { block().right() as Either<DomainError, T> }
            .getOrElse { e ->
                log.warn("Drafting via {} failed: {}", providerId, e.message)
                DomainError.Invalid("Drafting via $providerId failed (${e.message}); edit manually").left()
            }

    private fun <T> manual(): Either<DomainError, T> =
        DomainError.Invalid("No drafting provider enabled; edit manually").left()
}

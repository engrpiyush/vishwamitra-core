package ai.vishwakarma.labelling.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ai.vishwakarma.labelling.domain.ExampleSource
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ReviewComment
import ai.vishwakarma.labelling.domain.SftExample
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.persistence.SftExampleRepository
import ai.vishwakarma.labelling.serialization.ContentsPartsSerializer
import ai.vishwakarma.labelling.serialization.SftValidator
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SftService(
    private val repo: SftExampleRepository,
    private val validator: SftValidator,
    private val serializer: ContentsPartsSerializer,
) {

    fun list(status: ExampleStatus? = null): List<SftExample> =
        if (status == null) repo.findAll() else repo.findByStatus(status)

    fun get(id: String): SftExample? = repo.findById(id)

    fun validate(example: SftExample): List<String> = validator.validate(example)

    fun preview(example: SftExample): String = serializer.toJsonl(example)

    fun createDraft(actor: String?, source: ExampleSource = ExampleSource.MANUAL, scenarioId: String? = null): SftExample {
        val now = Instant.now()
        val example = SftExample(
            id = repo.newId(),
            turns = listOf(Turn(role = TurnRole.USER, kind = TurnKind.TEXT)),
            status = ExampleStatus.DRAFT,
            source = source,
            scenarioId = scenarioId,
            createdBy = actor,
            createdAt = now,
            updatedAt = now,
        )
        repo.save(example)
        return example
    }

    fun updateTags(id: String, skill: String?, intent: String?, language: String?): Either<DomainError, SftExample> =
        mutate(id) { it.copy(tags = it.tags.copy(skill = skill?.ifBlank { null }, intent = intent?.ifBlank { null }, language = language?.ifBlank { null })) }

    fun addTurn(id: String, role: TurnRole, kind: TurnKind): Either<DomainError, SftExample> =
        mutate(id) { ex ->
            val turn = when (kind) {
                TurnKind.TEXT -> Turn(role = role, kind = kind)
                TurnKind.TOOL_CALL -> Turn(role = TurnRole.MODEL, kind = kind, toolName = "", argsJson = "{}")
                TurnKind.TOOL_RESPONSE -> Turn(role = TurnRole.USER, kind = kind, toolName = "", resultJson = "{}")
            }
            ex.copy(turns = ex.turns + turn)
        }

    fun updateTurn(
        id: String,
        index: Int,
        text: String?,
        toolName: String?,
        argsJson: String?,
        resultJson: String?,
    ): Either<DomainError, SftExample> = mutate(id) { ex ->
        val turns = ex.turns.toMutableList()
        val t = turns.getOrNull(index) ?: return@mutate ex
        turns[index] = t.copy(
            text = text ?: t.text,
            toolName = toolName ?: t.toolName,
            argsJson = argsJson ?: t.argsJson,
            resultJson = resultJson ?: t.resultJson,
        )
        ex.copy(turns = turns)
    }

    fun deleteTurn(id: String, index: Int): Either<DomainError, SftExample> = mutate(id) { ex ->
        ex.copy(turns = ex.turns.filterIndexed { i, _ -> i != index })
    }

    /** Replace all turns (used by LLM "generate conversation"). */
    fun replaceTurns(id: String, turns: List<Turn>): Either<DomainError, SftExample> =
        mutate(id) { it.copy(turns = turns, source = ExampleSource.LLM) }

    /** Append a single turn (used by LLM "draft next turn"). */
    fun appendTurn(id: String, turn: Turn): Either<DomainError, SftExample> =
        mutate(id) { it.copy(turns = it.turns + turn) }

    fun moveTurn(id: String, index: Int, delta: Int): Either<DomainError, SftExample> = mutate(id) { ex ->
        val turns = ex.turns.toMutableList()
        val target = index + delta
        if (index in turns.indices && target in turns.indices) {
            val tmp = turns[index]; turns[index] = turns[target]; turns[target] = tmp
        }
        ex.copy(turns = turns)
    }

    fun addComment(id: String, actor: String?, text: String): Either<DomainError, SftExample> =
        mutate(id) { it.copy(reviewComments = it.reviewComments + ReviewComment(actor, text.trim())) }

    // ---- Lifecycle transitions --------------------------------------------
    fun submit(id: String, actor: String?): Either<DomainError, SftExample> {
        val ex = repo.findById(id) ?: return DomainError.NotFound("Example $id not found").left()
        if (ex.status !in setOf(ExampleStatus.DRAFT, ExampleStatus.NEEDS_CHANGES)) {
            return DomainError.Invalid("Only drafts or sent-back examples can be submitted").left()
        }
        val errors = validator.validate(ex)
        if (errors.isNotEmpty()) return DomainError.Invalid("Fix before submitting: ${errors.joinToString("; ")}").left()
        return persist(ex.copy(status = ExampleStatus.SUBMITTED)).right()
    }

    fun approve(id: String, actor: String?): Either<DomainError, SftExample> {
        val ex = repo.findById(id) ?: return DomainError.NotFound("Example $id not found").left()
        if (ex.status != ExampleStatus.SUBMITTED) return DomainError.Invalid("Only submitted examples can be approved").left()
        return persist(ex.copy(status = ExampleStatus.APPROVED)).right()
    }

    fun sendBack(id: String, actor: String?, comment: String): Either<DomainError, SftExample> {
        val ex = repo.findById(id) ?: return DomainError.NotFound("Example $id not found").left()
        if (ex.status != ExampleStatus.SUBMITTED) return DomainError.Invalid("Only submitted examples can be sent back").left()
        if (comment.isBlank()) return DomainError.Invalid("A comment is required when sending back").left()
        val updated = ex.copy(
            status = ExampleStatus.NEEDS_CHANGES,
            reviewComments = ex.reviewComments + ReviewComment(actor, comment.trim()),
        )
        return persist(updated).right()
    }

    fun archive(id: String): Either<DomainError, SftExample> =
        mutate(id) { it.copy(status = ExampleStatus.ARCHIVED) }

    /** Stamp the example as included in an export snapshot. */
    fun markExported(id: String, exportId: String) {
        repo.findById(id)?.let { repo.save(it.copy(exportedIn = it.exportedIn + exportId)) }
    }

    fun delete(id: String) = repo.delete(id)

    // ---- helpers ----------------------------------------------------------
    private fun mutate(id: String, fn: (SftExample) -> SftExample): Either<DomainError, SftExample> {
        val ex = repo.findById(id) ?: return DomainError.NotFound("Example $id not found").left()
        return persist(fn(ex)).right()
    }

    private fun persist(ex: SftExample): SftExample {
        val hasToolCall = ex.turns.any { it.kind != TurnKind.TEXT }
        val updated = ex.copy(tags = ex.tags.copy(hasToolCall = hasToolCall), updatedAt = Instant.now())
        repo.save(updated)
        return updated
    }
}

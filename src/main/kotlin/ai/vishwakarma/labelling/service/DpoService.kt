package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.DpoPair
import ai.vishwakarma.labelling.domain.DpoSource
import ai.vishwakarma.labelling.domain.ExampleStatus
import ai.vishwakarma.labelling.domain.ReviewComment
import ai.vishwakarma.labelling.domain.Turn
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import ai.vishwakarma.labelling.persistence.DpoPairRepository
import ai.vishwakarma.labelling.serialization.DpoSerializer
import ai.vishwakarma.labelling.serialization.DpoValidator
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import org.springframework.stereotype.Service

@Service
class DpoService(
    private val repo: DpoPairRepository,
    private val validator: DpoValidator,
    private val serializer: DpoSerializer,
    private val sft: SftService,
) {

    fun list(status: ExampleStatus? = null): List<DpoPair> =
        if (status == null) repo.findAll() else repo.findByStatus(status)

    fun get(id: String): DpoPair? = repo.findById(id)

    fun validate(pair: DpoPair): List<String> = validator.validate(pair)

    fun preview(pair: DpoPair): String = serializer.toJsonl(pair)

    /** Approved SFT examples that can seed a DPO prompt. */
    fun seedableSft() = sft.list(ExampleStatus.APPROVED)

    fun createManualDraft(actor: String?): DpoPair {
        val now = Instant.now()
        val pair =
            DpoPair(
                id = repo.newId(),
                promptTurns = listOf(Turn(role = TurnRole.USER, kind = TurnKind.TEXT)),
                status = ExampleStatus.DRAFT,
                source = DpoSource.MANUAL,
                createdBy = actor,
                createdAt = now,
                updatedAt = now,
            )
        repo.save(pair)
        return pair
    }

    /**
     * Seed prompt from an approved SFT example's leading user turns; chosen = its final model text.
     */
    fun createFromSft(sftId: String, actor: String?): Either<DomainError, DpoPair> {
        val ex =
            sft.get(sftId) ?: return DomainError.NotFound("SFT example $sftId not found").left()
        if (ex.status != ExampleStatus.APPROVED)
            return DomainError.Invalid("Only approved SFT examples can seed a DPO pair").left()

        val prompt = ex.turns.takeWhile { it.role == TurnRole.USER }.ifEmpty { ex.turns.take(1) }
        val chosen =
            ex.turns.lastOrNull { it.role == TurnRole.MODEL && it.kind == TurnKind.TEXT }?.text
                ?: ""
        val now = Instant.now()
        val pair =
            DpoPair(
                id = repo.newId(),
                promptTurns = prompt,
                chosenText = chosen,
                tags = ex.tags.copy(hasToolCall = prompt.any { it.kind != TurnKind.TEXT }),
                status = ExampleStatus.DRAFT,
                source = DpoSource.MANUAL,
                fromSftId = sftId,
                createdBy = actor,
                createdAt = now,
                updatedAt = now,
            )
        repo.save(pair)
        return pair.right()
    }

    fun setPrompt(id: String, promptText: String): Either<DomainError, DpoPair> =
        mutate(id) {
            it.copy(
                promptTurns =
                    listOf(
                        Turn(role = TurnRole.USER, kind = TurnKind.TEXT, text = promptText.trim())
                    )
            )
        }

    fun setCandidates(id: String, chosen: String, rejected: String): Either<DomainError, DpoPair> =
        mutate(id) { it.copy(chosenText = chosen.trim(), rejectedText = rejected.trim()) }

    fun swapCandidates(id: String): Either<DomainError, DpoPair> =
        mutate(id) { it.copy(chosenText = it.rejectedText, rejectedText = it.chosenText) }

    fun updateTags(
        id: String,
        skill: String?,
        intent: String?,
        language: String?
    ): Either<DomainError, DpoPair> =
        mutate(id) {
            it.copy(
                tags =
                    it.tags.copy(
                        skill = skill?.ifBlank { null },
                        intent = intent?.ifBlank { null },
                        language = language?.ifBlank { null }
                    )
            )
        }

    fun addComment(id: String, actor: String?, text: String): Either<DomainError, DpoPair> =
        mutate(id) {
            it.copy(reviewComments = it.reviewComments + ReviewComment(actor, text.trim()))
        }

    // ---- lifecycle --------------------------------------------------------
    fun submit(id: String, actor: String?): Either<DomainError, DpoPair> {
        val p = repo.findById(id) ?: return DomainError.NotFound("Pair $id not found").left()
        if (p.status !in setOf(ExampleStatus.DRAFT, ExampleStatus.NEEDS_CHANGES)) {
            return DomainError.Invalid("Only drafts or sent-back pairs can be submitted").left()
        }
        val errors = validator.validate(p)
        if (errors.isNotEmpty())
            return DomainError.Invalid("Fix before submitting: ${errors.joinToString("; ")}").left()
        return persist(p.copy(status = ExampleStatus.SUBMITTED)).right()
    }

    fun approve(id: String, actor: String?): Either<DomainError, DpoPair> {
        val p = repo.findById(id) ?: return DomainError.NotFound("Pair $id not found").left()
        if (p.status != ExampleStatus.SUBMITTED)
            return DomainError.Invalid("Only submitted pairs can be approved").left()
        return persist(p.copy(status = ExampleStatus.APPROVED)).right()
    }

    fun sendBack(id: String, actor: String?, comment: String): Either<DomainError, DpoPair> {
        val p = repo.findById(id) ?: return DomainError.NotFound("Pair $id not found").left()
        if (p.status != ExampleStatus.SUBMITTED)
            return DomainError.Invalid("Only submitted pairs can be sent back").left()
        if (comment.isBlank())
            return DomainError.Invalid("A comment is required when sending back").left()
        return persist(
                p.copy(
                    status = ExampleStatus.NEEDS_CHANGES,
                    reviewComments = p.reviewComments + ReviewComment(actor, comment.trim())
                )
            )
            .right()
    }

    fun archive(id: String): Either<DomainError, DpoPair> =
        mutate(id) { it.copy(status = ExampleStatus.ARCHIVED) }

    /** Stamp the pair as included in an export snapshot. */
    fun markExported(id: String, exportId: String) {
        repo.findById(id)?.let { repo.save(it.copy(exportedIn = it.exportedIn + exportId)) }
    }

    fun delete(id: String) = repo.delete(id)

    private fun mutate(id: String, fn: (DpoPair) -> DpoPair): Either<DomainError, DpoPair> {
        val p = repo.findById(id) ?: return DomainError.NotFound("Pair $id not found").left()
        return persist(fn(p)).right()
    }

    private fun persist(p: DpoPair): DpoPair {
        val hasToolCall = p.promptTurns.any { it.kind != TurnKind.TEXT }
        val updated =
            p.copy(tags = p.tags.copy(hasToolCall = hasToolCall), updatedAt = Instant.now())
        repo.save(updated)
        return updated
    }
}

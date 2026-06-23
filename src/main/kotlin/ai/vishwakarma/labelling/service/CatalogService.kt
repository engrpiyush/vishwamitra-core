package ai.vishwakarma.labelling.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ai.vishwakarma.labelling.domain.Tool
import ai.vishwakarma.labelling.domain.ToolParam
import ai.vishwakarma.labelling.domain.ToolStatus
import ai.vishwakarma.labelling.persistence.ToolRepository
import org.springframework.stereotype.Service
import java.time.Instant

/** Manages the admin-editable tool (function-signature) catalog. */
@Service
class CatalogService(private val tools: ToolRepository) {

    fun list(includeDeprecated: Boolean = true): List<Tool> =
        tools.findAll().filter { includeDeprecated || it.status == ToolStatus.ACTIVE }

    fun get(id: String): Tool? = tools.findById(id)

    fun create(
        name: String,
        description: String,
        params: List<ToolParam>,
        status: ToolStatus,
        actor: String?,
    ): Either<DomainError, Tool> {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return DomainError.Invalid("Tool name is required").left()
        if (tools.findAll().any { it.name.equals(cleanName, ignoreCase = true) }) {
            return DomainError.Conflict("A tool named '$cleanName' already exists").left()
        }
        val tool = Tool(
            id = tools.newId(),
            name = cleanName,
            description = description.trim(),
            params = params,
            status = status,
            updatedBy = actor,
            updatedAt = Instant.now(),
        )
        tools.save(tool)
        return tool.right()
    }

    fun update(id: String, mutate: (Tool) -> Tool, actor: String?): Either<DomainError, Tool> {
        val existing = tools.findById(id) ?: return DomainError.NotFound("Tool $id not found").left()
        val updated = mutate(existing).copy(updatedBy = actor, updatedAt = Instant.now())
        tools.save(updated)
        return updated.right()
    }

    fun delete(id: String) = tools.delete(id)
}

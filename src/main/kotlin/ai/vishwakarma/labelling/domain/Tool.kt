package ai.vishwakarma.labelling.domain

import java.time.Instant

enum class ToolStatus {
    ACTIVE,
    DEPRECATED
}

/** A function-call parameter in a tool signature. */
data class ToolParam(
    val name: String,
    val type: String = "string",
    val required: Boolean = true,
    val desc: String = "",
)

/**
 * An admin-editable tool (function) signature. Drives tool-call turns in the SFT editor and the
 * tool-aware drafting prompts.
 */
data class Tool(
    val id: String,
    val name: String,
    val description: String = "",
    val params: List<ToolParam> = emptyList(),
    val status: ToolStatus = ToolStatus.ACTIVE,
    val updatedBy: String? = null,
    val updatedAt: Instant? = null,
)

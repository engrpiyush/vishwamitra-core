package ai.vishwakarma.labelling.domain

import java.time.Instant

/** A seed scenario used to LLM-generate SFT conversations. */
data class Scenario(
    val id: String,
    val title: String,
    val description: String = "",
    val skill: String? = null,
    val intent: String? = null,
    val promptTemplate: String = "",
    val createdBy: String? = null,
    val createdAt: Instant? = null,
)

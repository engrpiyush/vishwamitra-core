package ai.vishwakarma.labelling.domain

import java.time.Instant

/** A seed scenario used to LLM-generate SFT conversations. */
data class Scenario(
    val id: String,
    val title: String,
    val description: String = "",
    val claimType: ClaimType? = null,
    val labels: List<String> = emptyList(),
    val promptTemplate: String = "",
    val createdBy: String? = null,
    val createdAt: Instant? = null,
)

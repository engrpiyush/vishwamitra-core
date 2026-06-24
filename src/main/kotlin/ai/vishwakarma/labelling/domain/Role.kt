package ai.vishwakarma.labelling.domain

/**
 * Access roles, ordered least → most privileged. Hierarchy (configured in security): ADMIN ⊃
 * REVIEWER ⊃ AUTHOR.
 * - AUTHOR create/edit own drafts, run LLM drafting, submit for review.
 * - REVIEWER author rights + approve / send-back / export / submit tuning jobs.
 * - ADMIN all + manage catalogs, taxonomy, scenarios, users, providers.
 */
enum class Role {
    AUTHOR,
    REVIEWER,
    ADMIN,
    ;

    val authority: String
        get() = "ROLE_$name"

    companion object {
        fun fromOrNull(value: String?): Role? =
            value?.trim()?.uppercase()?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

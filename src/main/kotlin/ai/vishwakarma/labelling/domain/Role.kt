package ai.vishwakarma.labelling.domain

/**
 * Access roles, ordered least → most privileged. Hierarchy (configured in security): ADMIN ⊃
 * REVIEWER ⊃ AUTHOR.
 * - SUBJECT the modelled person on the product face (VA-29, LLD §4.1) — OUTSIDE the operator
 *   hierarchy: implies nothing, nothing implies it. What grants anything is the binding
 *   ([User.subjectId] → the `SUBJECT_ID_<id>` authority), scoped to that subject's own host.
 * - AUTHOR create/edit own drafts, run LLM drafting, submit for review.
 * - REVIEWER author rights + approve / send-back / export / submit tuning jobs.
 * - ADMIN all + manage catalogs, taxonomy, scenarios, users, providers.
 */
enum class Role {
    SUBJECT,
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

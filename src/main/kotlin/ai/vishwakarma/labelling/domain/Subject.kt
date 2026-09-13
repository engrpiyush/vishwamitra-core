package ai.vishwakarma.labelling.domain

import java.time.Instant

/** Lifecycle of a [Subject]. */
enum class SubjectStatus {
    ACTIVE,
    ARCHIVED;

    companion object {
        fun fromOrNull(raw: String?): SubjectStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * The person a Neo model is being built for. Every [Asset] and [IntakeManifest] hangs off a subject
 * (`subjectId`), so the pipeline is multi-subject from day one.
 */
data class Subject(
    val id: String,
    val displayName: String,
    /** Optional human-friendly slug, e.g. for object paths / URLs. */
    val handle: String? = null,
    /**
     * Contact email the trained-in system prompt may quote (`{{subject_email}}`, LLD §9.6) —
     * operator-entered on the admin subject form, deliberately NOT sourced from any login identity.
     * Blank/null drops the email sentence from the composed header.
     */
    val contactEmail: String? = null,
    val notes: String = "",
    val status: SubjectStatus = SubjectStatus.ACTIVE,
    val createdBy: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

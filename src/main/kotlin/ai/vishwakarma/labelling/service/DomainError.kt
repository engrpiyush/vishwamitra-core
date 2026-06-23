package ai.vishwakarma.labelling.service

/** Typed errors returned (via Arrow `Either`) from service mutations. */
sealed interface DomainError {
    val message: String

    data class NotFound(override val message: String) : DomainError
    data class Conflict(override val message: String) : DomainError
    data class Invalid(override val message: String) : DomainError
}

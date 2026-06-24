package ai.vishwakarma.labelling.domain

/**
 * Single-document taxonomy of tags used across SFT/DPO examples and seed generation. Stored as one
 * doc (id = "default") in the `taxonomy` collection.
 */
data class Taxonomy(
    val skills: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
) {
    enum class Dimension {
        SKILLS,
        INTENTS,
        LANGUAGES
    }

    companion object {
        const val DOC_ID = "default"
    }
}

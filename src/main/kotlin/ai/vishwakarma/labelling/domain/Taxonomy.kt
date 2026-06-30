package ai.vishwakarma.labelling.domain

/**
 * Single-document vocabulary of freeform tag [labels] suggested across SFT/DPO examples and seed
 * generation. The fixed tag dimensions (claim type, authenticity tier) are enums, not stored here.
 * Stored as one doc (id = "default") in the `taxonomy` collection.
 */
data class Taxonomy(val labels: List<String> = emptyList()) {

    companion object {
        const val DOC_ID = "default"
    }
}

package ai.vishwakarma.labelling.domain

/**
 * The JSONL family an SFT export is serialized in — both accepted by the managed OSS tuning API
 * (open-model tuning docs, captured 2026-07-24), so the pick is portability/UX, not correctness.
 * DPO stays [GENERATE_CONTENT]-only: no messages-shaped preference format is documented.
 */
enum class DatasetFormat {
    /** `{"contents":[{role, parts:[{text}]}...]}` — the legacy Vertex GenerateContent shape. */
    GENERATE_CONTENT,
    /**
     * OpenAI-style turn-based chat: `{"messages":[{"role":"system|user|assistant","content":...}]}`
     * — the shape that carries the trained-in system prompt (LLD §9.6) and travels to TRL /
     * third-party tooling unchanged. Export-only: the import path stays contents-shaped.
     */
    OPENAI_CHAT;

    companion object {
        fun fromOrNull(raw: String?): DatasetFormat? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

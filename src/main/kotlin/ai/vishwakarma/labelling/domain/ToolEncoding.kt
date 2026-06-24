package ai.vishwakarma.labelling.domain

/**
 * How tool-call / tool-response turns are rendered into the `contents`/`parts` dataset. Vertex
 * open-model (OSS) tuning only accepts `text`/`inline_data`/`file_data` parts — structured
 * `functionCall`/`functionResponse` parts are rejected — so every encoding below emits a plain
 * `text` part; they differ only in how the call/response is framed inside that text. Chosen at
 * export time because the right framing depends on the target base model's chat template.
 */
enum class ToolEncoding(val label: String) {
    /**
     * Qwen/Hermes convention: `<tool_call>{"name","arguments"}</tool_call>` + `<tool_response>…`.
     */
    QWEN_HERMES("Qwen/Hermes <tool_call> tags"),

    /** Bare JSON `{"name","args"}` for the call, raw result JSON for the response. */
    PLAIN_JSON("Plain JSON text"),

    /** Gemma-style fenced blocks: ```tool_call / ```tool_output containing JSON. */
    GEMMA_FENCED("Gemma fenced code blocks");

    companion object {
        val DEFAULT = QWEN_HERMES
    }
}

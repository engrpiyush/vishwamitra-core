package ai.vishwakarma.labelling.serialization

import tools.jackson.databind.json.JsonMapper

/**
 * Minimal JSON helper (Jackson 3) used by the dataset serializer — independent of Spring's mapper.
 */
object Json {
    val mapper: JsonMapper = JsonMapper.builder().build()

    fun writeLine(value: Any?): String = mapper.writeValueAsString(value)

    /** Parse a JSON string into Map/List/scalar, or null when blank. */
    fun parse(json: String?): Any? =
        if (json.isNullOrBlank()) null else mapper.readValue(json, Any::class.java)

    fun isValidObject(json: String?): Boolean =
        runCatching { mapper.readValue(json ?: "", Any::class.java) is Map<*, *> }
            .getOrDefault(false)

    fun isValid(json: String?): Boolean =
        runCatching {
                mapper.readValue(json ?: "", Any::class.java)
                true
            }
            .getOrDefault(false)
}

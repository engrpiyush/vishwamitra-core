package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.BaseKind

/** Pure per-family `vMAJOR.MINOR` numbering rules (no I/O). */
object Versioning {

    private fun parse(v: String): Pair<Int, Int> =
        v.removePrefix("v").split(".").let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }

    /**
     * @param existing all version strings already in the family (e.g. ["v1.0","v1.1","v2.0"])
     * @param baseKind FOUNDATION → new major `v{maxMajor+1}.0`; CONTINUATION → `v{parentMajor}.{maxMinorInMajor+1}`
     * @param parentVersion required for CONTINUATION
     */
    fun nextVersion(existing: List<String>, baseKind: BaseKind, parentVersion: String?): String {
        val parsed = existing.map { parse(it) }
        return when (baseKind) {
            BaseKind.FOUNDATION -> {
                val maxMajor = parsed.maxOfOrNull { it.first } ?: 0
                "v${maxMajor + 1}.0"
            }
            BaseKind.CONTINUATION -> {
                val parentMajor = parse(parentVersion ?: error("continuation needs a parent version")).first
                val maxMinor = parsed.filter { it.first == parentMajor }.maxOfOrNull { it.second } ?: 0
                "v$parentMajor.${maxMinor + 1}"
            }
        }
    }
}

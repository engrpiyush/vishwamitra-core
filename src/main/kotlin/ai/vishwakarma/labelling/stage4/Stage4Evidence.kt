package ai.vishwakarma.labelling.stage4

/**
 * The §9.3 evidence rendering shared by GENERATE, JUDGE and the §12 DPO drafter: the claim text
 * with its score, explanation context and F5-precision dates — everything a prompt may ground on,
 * nothing it may not.
 */
object Stage4Evidence {

    fun line(e: EvidencedClaim): String = buildString {
        append("[${e.claim.id}] \"${e.claim.text}\" — score ")
        append("%.2f".format(e.score))
        e.claim.authenticityTier?.let { append(" (${it.name})") }
        e.sidecar?.let { append("; explanation on record: \"$it\"") }
        e.claim.factStamp?.let { fs ->
            val dates = listOfNotNull(fs.validFrom, fs.validTo).distinct()
            if (dates.isNotEmpty()) {
                append("; dated ${dates.joinToString(" → ")}")
                fs.datePrecision?.let { append(" ($it precision — never voice finer)") }
            }
        }
    }
}

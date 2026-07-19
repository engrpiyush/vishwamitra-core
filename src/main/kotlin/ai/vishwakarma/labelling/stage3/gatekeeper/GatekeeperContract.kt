package ai.vishwakarma.labelling.stage3.gatekeeper

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The one message that crosses the vishwamitra ↔ lakshmana boundary (Gatekeeper LLD §5, D‑6).
 *
 * `lakshmana-core/contracts/gatekeeper_run_request.proto` is the normative schema and **neither
 * repo generates code from it** — D‑6 rules out a shared artifact, so both sides hand-write the
 * mapping and the golden fixtures under `lakshmana-core/contracts/fixtures/` are what actually pin
 * them together. `GatekeeperContractTest` asserts this file still emits those exact bytes; the
 * Python side's `tests/test_payload_contract.py` does the same against its own codec.
 *
 * IDs only: claim text never transits Pub/Sub (privacy + size), and config never travels either —
 * it is frozen into `gatekeeper_runs.configSnapshot` when the run doc is created, so a mid-run
 * config edit cannot produce a mixed-calibration run.
 */
object GatekeeperContract {

    /** The version this build emits. An unknown version goes to the DLQ, never guessed (§11). */
    const val SCHEMA_VERSION = 1

    /**
     * RFC3339 UTC at millisecond precision — the fixtures' `2026-07-19T04:15:00.000Z` shape.
     * `Instant.toString()` is NOT equivalent: it drops the sub-second field entirely on a whole
     * second and prints microseconds when it has them, so the wire form would drift by input.
     */
    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun formatTimestamp(at: Instant): String = TIMESTAMP.format(at)

    /** UUIDv4, canonical lowercase — the Python codec rejects any other version or casing. */
    fun mintRunRequestId(): String = UUID.randomUUID().toString()
}

/**
 * The four cascade gates that appear on the wire and in the run doc's gate map. `FINALIZE` is
 * deliberately absent: it is an internal worker step after G4, never published and never a key in
 * `gatekeeper_runs.gates` (LLD §7.1, §8).
 */
enum class Gate {
    G1_NEUTRAL,
    G2_CORROBORATION,
    G3_CONTRADICTION,
    G4_ESCALATION;

    /** Operator-facing short label for the run-page chips. */
    val shortLabel: String
        get() =
            when (this) {
                G1_NEUTRAL -> "G1 neutral"
                G2_CORROBORATION -> "G2 corroboration"
                G3_CONTRADICTION -> "G3 contradiction"
                G4_ESCALATION -> "G4 escalation"
            }

    companion object {
        fun fromOrNull(raw: String?): Gate? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** FULL drives the whole chain from G1; FROM_GATE re-enters at one failed gate (LLD §10). */
enum class RunMode {
    FULL,
    FROM_GATE,
}

/** Who asked for this transition. */
enum class TriggeredBy {
    OPERATOR,
    SYSTEM,
    SWEEPER,
}

/**
 * Which judge decides a Stage 3 run (Gatekeeper LLD §9). Pinned into `Stage3Run.paramsSnapshot` at
 * run start and read from there by **both** services — that shared read is the split-brain guard.
 *
 * |Mode        |Cascade                |LLM ensemble    |Verdict writer                         |
 * |------------|-----------------------|----------------|---------------------------------------|
 * |[LLM]       |not invoked            |decides         |vishwamitra                            |
 * |[SHADOW]    |full run, G4 suppressed|decides         |vishwamitra (cascade → `shadow.*` only)|
 * |[GATEKEEPER]|decides                |**must not run**|lakshmana (+ human queue)              |
 *
 * The ensemble is therefore skipped in [GATEKEEPER] alone. SHADOW's whole purpose is a live
 * disagreement report, which needs both judges to answer — a SHADOW run that skipped the ensemble
 * would produce no authoritative verdicts at all, since lakshmana writes only `shadow.*` in that
 * mode. (The LLD §9 prose said "assert judgeMode == LLM"; its own table and the bold "must not run"
 * on GATEKEEPER alone are the normative reading, confirmed by the owner 2026-07-19.)
 */
enum class JudgeMode {
    LLM,
    GATEKEEPER,
    SHADOW;

    /** The cascade is asked to run in both non-legacy modes. */
    val publishesToGatekeeper: Boolean
        get() = this != LLM

    /** Only GATEKEEPER hands the verdict pen over; SHADOW keeps the ensemble as the decider. */
    val gatekeeperDecides: Boolean
        get() = this == GATEKEEPER

    companion object {
        val NAMES: List<String> = entries.map { it.name }

        fun fromOrNull(raw: String?): JudgeMode? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * An attempted gate transition — the Pub/Sub message body.
 *
 * Property declaration order **is** proto field-number order, which is the canonical JSON key order
 * the fixtures pin. Do not reorder these without regenerating the fixtures on both sides.
 */
data class GatekeeperRunRequest(
    val schemaVersion: Int = GatekeeperContract.SCHEMA_VERSION,
    val runRequestId: String,
    val intakeId: String,
    val stage3RunId: String,
    val gate: Gate,
    val mode: RunMode,
    val triggeredBy: TriggeredBy,
    /** RFC3339 UTC, millisecond precision — see [GatekeeperContract.formatTimestamp]. */
    val requestTimestamp: String,
) {

    /** proto3 JSON mapping: lowerCamelCase keys in proto field-number order. */
    fun toWire(): Map<String, Any> =
        linkedMapOf(
            "schemaVersion" to schemaVersion,
            "runRequestId" to runRequestId,
            "intakeId" to intakeId,
            "stage3RunId" to stage3RunId,
            "gate" to gate.name,
            "mode" to mode.name,
            "triggeredBy" to triggeredBy.name,
            "requestTimestamp" to requestTimestamp,
        )

    /**
     * The exact byte form stored in `contracts/fixtures/` — two-space indent, trailing newline,
     * mirroring Python's `json.dumps(..., indent=2, ensure_ascii=False) + "\n"`.
     */
    fun toCanonicalJson(): String = CanonicalJson.pretty(toWire())

    /** Compact UTF-8 bytes for the Pub/Sub message body (Python's `separators=(",", ":")`). */
    fun toBytes(): ByteArray = CanonicalJson.compact(toWire()).toByteArray(Charsets.UTF_8)
}

/**
 * A hand-rolled writer for the two canonical forms above.
 *
 * Deliberately not Jackson: the contract is a byte-level agreement with a Python `json.dumps`, and
 * matching it through a configurable mapper means depending on that mapper's pretty-printer
 * defaults (Jackson's own separator default is `" : "`, not `": "`) staying put across upgrades.
 * The payload is eight scalars; a writer that handles exactly `Int` and `String` is smaller than
 * the configuration it replaces, and it cannot drift.
 */
internal object CanonicalJson {

    fun compact(fields: Map<String, Any>): String =
        fields.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${scalar(v)}" }

    fun pretty(fields: Map<String, Any>): String =
        fields.entries.joinToString(
            separator = ",\n",
            prefix = "{\n",
            postfix = "\n}\n",
        ) { (k, v) ->
            "  ${quote(k)}: ${scalar(v)}"
        }

    private fun scalar(value: Any): String =
        when (value) {
            is Int -> value.toString()
            is String -> quote(value)
            else -> error("Unsupported payload value type ${value::class.simpleName}")
        }

    /**
     * JSON string escaping as Python's `json.dumps(..., ensure_ascii=False)` does it: quote and
     * backslash escaped, the five short escapes used where they exist, every other control
     * character as `\uXXXX`, and non-ASCII passed through verbatim.
     */
    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch == '\b' -> append("\\b")
                ch == '\u000C' -> append("\\f")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}

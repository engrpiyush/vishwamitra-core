package ai.vishwakarma.labelling.stage3.gatekeeper

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VA-106's half of the byte-for-byte contract with lakshmana-core (Gatekeeper LLD §5, D‑6).
 *
 * Neither repo generates code from `gatekeeper_run_request.proto`, so the golden fixtures under
 * `lakshmana-core/contracts/fixtures/` are the only thing pinning the two hand-written codecs
 * together. This test asserts the Kotlin publisher emits those exact bytes; `test_payload_contract`
 * on the Python side does the same. If lakshmana regenerates a fixture (a threshold moves, a model
 * sha lands), the matching change lands here — a red test here means "the contract moved", and the
 * fix is to look at the sibling repo's diff, never to loosen the assertion.
 *
 * The fixtures live in an independent sibling repo, not a submodule, so the path is a build
 * property (`-Dlakshmana.contracts.dir`, defaulted in the pom to `../lakshmana-core/contracts`).
 * When it is absent the test **fails loudly** rather than skipping: a green-because-skipped
 * contract test in CI is worse than no test, and a fresh clone without the sibling repo should
 * surface that as a setup problem, not hide it.
 */
class GatekeeperContractTest {

    private val fixtures: File by lazy {
        val dir =
            System.getProperty("lakshmana.contracts.dir")
                ?: "../lakshmana-core/contracts" // IDE runs bypass surefire and get no property
        File(dir, "fixtures").also {
            assertTrue(
                it.isDirectory,
                "gatekeeper contract fixtures not found at ${it.absolutePath}. They live in the " +
                    "sibling lakshmana-core repo; set -Dlakshmana.contracts.dir if it is elsewhere.",
            )
        }
    }

    private fun fixture(name: String): String = File(fixtures, name).readText()

    @Test
    fun `the FULL G1 trigger serializes byte-for-byte to the golden fixture`() {
        val request =
            GatekeeperRunRequest(
                runRequestId = "3f7c2a18-9b4e-4d6a-8c11-5e2f0a7d9b34",
                intakeId = "intake-2026-07-19-reference",
                stage3RunId = "stage3run-209claims-001",
                gate = Gate.G1_NEUTRAL,
                mode = RunMode.FULL,
                triggeredBy = TriggeredBy.OPERATOR,
                requestTimestamp = "2026-07-19T04:15:00.000Z",
            )
        assertEquals(
            fixture("gatekeeper_run_request.g1_full.json"),
            request.toCanonicalJson(),
        )
    }

    @Test
    fun `the FROM_GATE G3 retrigger serializes byte-for-byte to the golden fixture`() {
        val request =
            GatekeeperRunRequest(
                runRequestId = "3f7c2a18-9b4e-4d6a-8c11-5e2f0a7d9b34",
                intakeId = "intake-2026-07-19-reference",
                stage3RunId = "stage3run-209claims-001",
                gate = Gate.G3_CONTRADICTION,
                mode = RunMode.FROM_GATE,
                triggeredBy = TriggeredBy.OPERATOR,
                requestTimestamp = "2026-07-19T04:15:00.000Z",
            )
        assertEquals(
            fixture("gatekeeper_run_request.g3_from_gate.json"),
            request.toCanonicalJson(),
        )
    }

    @Test
    fun `the compact wire body is the fixture with whitespace removed`() {
        val request =
            GatekeeperRunRequest(
                runRequestId = "3f7c2a18-9b4e-4d6a-8c11-5e2f0a7d9b34",
                intakeId = "intake-2026-07-19-reference",
                stage3RunId = "stage3run-209claims-001",
                gate = Gate.G1_NEUTRAL,
                mode = RunMode.FULL,
                triggeredBy = TriggeredBy.OPERATOR,
                requestTimestamp = "2026-07-19T04:15:00.000Z",
            )
        // Python's json.dumps(separators=(",", ":")) — no spaces, no trailing newline.
        val expected =
            fixture("gatekeeper_run_request.g1_full.json").replace(" ", "").replace("\n", "")
        assertEquals(expected, String(request.toBytes(), Charsets.UTF_8))
    }

    @Test
    fun `the timestamp formatter matches the fixtures' millisecond RFC3339 shape`() {
        // A whole second must still print .000 — Instant.toString() would drop it entirely.
        assertEquals(
            "2026-07-19T04:15:00.000Z",
            GatekeeperContract.formatTimestamp(Instant.parse("2026-07-19T04:15:00Z")),
        )
        // Sub-second precision is truncated to millis, not printed as micros.
        assertEquals(
            "2026-07-19T04:15:00.123Z",
            GatekeeperContract.formatTimestamp(Instant.parse("2026-07-19T04:15:00.123456Z")),
        )
    }

    @Test
    fun `minted run request ids are canonical lowercase UUIDv4`() {
        val id = GatekeeperContract.mintRunRequestId()
        // The Python codec rejects any non-v4, non-canonical-lowercase id (payload._require_uuid4).
        assertTrue(
            id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
        )
    }

    @Test
    fun `every gate and mode token on the wire matches the proto enum names`() {
        // The wire uses proto3 enum-by-name; these are the exact tokens the Python side parses.
        assertEquals(
            listOf("G1_NEUTRAL", "G2_CORROBORATION", "G3_CONTRADICTION", "G4_ESCALATION"),
            Gate.entries.map { it.name },
        )
        assertEquals(listOf("FULL", "FROM_GATE"), RunMode.entries.map { it.name })
        assertEquals(listOf("OPERATOR", "SYSTEM", "SWEEPER"), TriggeredBy.entries.map { it.name })
    }
}

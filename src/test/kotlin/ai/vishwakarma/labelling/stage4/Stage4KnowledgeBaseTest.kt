package ai.vishwakarma.labelling.stage4

import ai.vishwakarma.labelling.domain.AuthenticityTier
import ai.vishwakarma.labelling.domain.Claim
import ai.vishwakarma.labelling.domain.ClaimType
import ai.vishwakarma.labelling.domain.PublishedAttestor
import ai.vishwakarma.labelling.domain.PublishedFactStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Stage4KnowledgeBase] (VA-164): the code-computed posture is the single source of truth, so its
 * band edges (0.45 / 0.75), the row-5 / not-SFT-eligible → ACKNOWLEDGE-ONLY degrade, and the
 * id-free marker rendering are pinned here; the KB is a pure function of the eligible set
 * (deterministic, sorted by claim id, capped).
 */
class Stage4KnowledgeBaseTest {

    private val kb = Stage4KnowledgeBase()

    private fun claim(
        id: String,
        score: Double = 0.9,
        text: String = "claim $id",
        favorability: Double? = null,
        tier: AuthenticityTier? = null,
        attestorKind: String? = null,
        anchored: Boolean = false,
        claimType: ClaimType = ClaimType.EPISODE,
        sensitive: Boolean = false,
        edgeCounts: Map<String, Int>? = null,
        validFrom: String? = null,
        validTo: String? = null,
        datePrecision: String? = null,
    ) =
        EvidencedClaim(
            Claim(
                id = id,
                subjectId = "s1",
                assetId = "a1",
                claimType = claimType,
                text = text,
                authenticityScore = score,
                authenticityScoreBare = score,
                authenticitySignals = mapOf("conflict" to 0.0, "independence" to 0.0),
                authenticityTier = tier,
                favorability = favorability,
                sensitive = sensitive,
                attestor = attestorKind?.let { PublishedAttestor(kind = it) },
                factStamp =
                    if (anchored || validFrom != null || validTo != null)
                        PublishedFactStamp(
                            factId = "f-$id",
                            label = text,
                            anchored = anchored,
                            validFrom = validFrom,
                            validTo = validTo,
                            datePrecision = datePrecision,
                        )
                    else null,
                edgeCounts = edgeCounts,
                scoreRunId = "pub-1",
                publishContractVersion = 2,
            )
        )

    private fun posture(e: EvidencedClaim) = kb.postureOf(e)

    // ---- posture band edges (the single source of truth) --------------------------------------

    @Test
    fun `posture bands break at 0_45 and 0_75`() {
        assertEquals(
            Stage4KnowledgeBase.Posture.ACKNOWLEDGE_ONLY,
            posture(claim("c", score = 0.44))
        )
        assertEquals(Stage4KnowledgeBase.Posture.HEDGE, posture(claim("c", score = 0.45)))
        assertEquals(Stage4KnowledgeBase.Posture.HEDGE, posture(claim("c", score = 0.74)))
        assertEquals(Stage4KnowledgeBase.Posture.ASSERT, posture(claim("c", score = 0.75)))
    }

    @Test
    fun `an unexplained CONFIRMED conflict is ACKNOWLEDGE-ONLY even at a top score`() {
        // Row 5: contradictsConfirmed with no explanation. sftEligibleAlone is false here too, so
        // this one case pins both the confirmed-conflict and the not-SFT-eligible degrade.
        val row5 = claim("c", score = 0.95, edgeCounts = mapOf("contradictsConfirmed" to 1))
        assertEquals(Stage4KnowledgeBase.Posture.ACKNOWLEDGE_ONLY, posture(row5))
        assertTrue(
            !Stage4VoicingPlanner().sftEligibleAlone(row5),
            "row 5 is not SFT-eligible alone"
        )
    }

    @Test
    fun `an explained contradiction at a high score still asserts`() {
        // contradictsExplained > 0 makes `explained` true, so it is NOT the row-5 exclusion.
        val explained = claim("c", score = 0.9, edgeCounts = mapOf("contradictsExplained" to 1))
        assertEquals(Stage4KnowledgeBase.Posture.ASSERT, posture(explained))
    }

    // ---- line rendering ------------------------------------------------------------------------

    @Test
    fun `a line carries text, posture, score, tier, markers, favourability and dates — id-free`() {
        val e =
            claim(
                "cLONGID",
                score = 0.82,
                text = "Led the payments migration",
                tier = AuthenticityTier.HIGH,
                favorability = 0.2,
                attestorKind = "ISSUER",
                anchored = true,
                validFrom = "2019",
                datePrecision = "YEAR",
            )

        val line = kb.render(listOf(e), kbMaxClaims = 400).single()

        assertTrue(line.contains("Led the payments migration"), line)
        assertTrue(line.contains("ASSERT"), line)
        assertTrue(line.contains("score 0.82"), line)
        assertTrue(line.contains("(HIGH)"), line)
        assertTrue(line.contains("anchored"), line)
        assertTrue(line.contains("independent"), line)
        assertTrue(line.contains("favourability 0.20"), line)
        assertTrue(line.contains("dated 2019"), line)
        assertTrue(line.contains("YEAR precision"), line)
        // Id-free: the claim id never appears bracketed (the F7 leak the KB span would cause).
        assertTrue("[cLONGID]" !in line, line)
        assertTrue("cLONGID" !in line, line)
    }

    @Test
    fun `the standing rules carry the never-deny contract`() {
        assertTrue(kb.standingRules().contains("never deny"))
        assertTrue(kb.standingRules().contains("ACKNOWLEDGE-ONLY"))
        assertTrue(kb.standingRules().contains("nothing outside this list"))
    }

    // ---- determinism, ordering, cap ------------------------------------------------------------

    @Test
    fun `render is deterministic and sorted by claim id regardless of input order`() {
        val claims =
            listOf(
                claim("c3", text = "gamma"),
                claim("c1", text = "alpha"),
                claim("c2", text = "beta"),
            )

        val first = kb.render(claims, kbMaxClaims = 400)
        val second = kb.render(claims, kbMaxClaims = 400)

        assertEquals(first, second)
        // Sorted by id: alpha (c1), beta (c2), gamma (c3).
        assertTrue(first[0].contains("alpha"), first[0])
        assertTrue(first[1].contains("beta"), first[1])
        assertTrue(first[2].contains("gamma"), first[2])
    }

    @Test
    fun `the cap keeps the first claims by id and drops the rest`() {
        val claims =
            listOf(
                claim("c1", text = "alpha"),
                claim("c2", text = "beta"),
                claim("c3", text = "gamma"),
            )

        val capped = kb.render(claims, kbMaxClaims = 2)

        assertEquals(2, capped.size)
        assertTrue(capped[0].contains("alpha"))
        assertTrue(capped[1].contains("beta"))
        // The highest-id claim is the one dropped (a WARN names the count, see render()).
        assertTrue(capped.none { it.contains("gamma") })
    }
}

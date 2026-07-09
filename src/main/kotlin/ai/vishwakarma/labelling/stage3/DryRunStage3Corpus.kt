package ai.vishwakarma.labelling.stage3

/** One canned corpus claim — the seeded Firestore row and the dry-run tables in one place. */
data class CorpusClaim(
    /** Stable seeded claim id (`asha-cNN`, zero-padded so id order = corpus order). */
    val claimId: String,
    /**
     * Lowercase text fingerprint the dry-run mention/verdict tables match on (`contains` over the
     * lowercased card text) — claims re-extracted with different ids still hit their rows, and
     * non-corpus claims simply match nothing.
     */
    val marker: String,
    val text: String,
    /** [ai.vishwakarma.labelling.domain.ClaimType] name. */
    val type: String,
    /** Key into [DryRunStage3Corpus.assets]. */
    val assetKey: String,
    /** ISO yyyy-MM-dd or null. */
    val claimedDate: String?,
    /** [ai.vishwakarma.labelling.domain.AuthenticityTier] name — the §11.8 p0 seed. */
    val tierSeed: String,
    val favorability: Double,
    val mentions: List<ExtractedMention>,
    /** §18.2 Q2: the documentary issuer's surface (must also appear in [mentions]). */
    val issuerSurface: String? = null,
    /** §12.6 SIDECARED justification — projected as `:Explanation` (the §11.9 dual pass). */
    val sidecar: String? = null,
)

/** One canned corpus asset (`:Source` provenance + attestor derivation inputs). */
data class CorpusAsset(
    val key: String,
    val title: String,
    /** [ai.vishwakarma.labelling.domain.AssetModality] name. */
    val modality: String,
    /** [ai.vishwakarma.labelling.domain.ContentType] name (fixes the source class + prior). */
    val contentType: String,
    /** [ai.vishwakarma.labelling.domain.Relationship] name. */
    val relationship: String,
) {
    fun assetId(subjectId: String): String = "$subjectId-$key"
}

/** One scripted ensemble row: the per-sample answers for a (pair, variant) — length = 5. */
private data class VerdictScript(
    val samples: List<JudgeSample>,
)

/** One verdict-table row, matched by unordered marker pair. */
private data class VerdictRow(
    val markerA: String,
    val markerB: String,
    val bare: VerdictScript,
    val ctx: VerdictScript? = null,
)

/**
 * The §11.12 sample corpus: the LLD §11.8 worked example ("Asha", claims c01–c06) extended so the
 * offline pipeline exercises **every §11 branch** — see each claim's comment for the branch it
 * carries. [DryRunEntityMentionExtractor] and [DryRunJudgeSampler] key off this table by text
 * marker; [ai.vishwakarma.labelling.service.Stage3CorpusSeeder] writes the claims to Firestore so a
 * dev run reproduces the worked-example numbers end-to-end (F1 0.90 · F2 0.74 · F3 0.31 bare / 0.35
 * explained · F4 0.60, empty queue at AWAITING_REVIEW).
 *
 * Text/surface choices are tuned against [PseudoEmbeddingService] cosines and pinned by
 * `DryRunStage3CorpusTest` — the load-bearing ones:
 * - "payments migration" vs "the payments migration" ≈ 0.854 ≥ 0.85 → confident kNN merge + alias
 *   adoption (c06 links to the entity c05 minted, giving (c05,c06) its co-mention block);
 * - "AWS Solutions Architect" vs "AWS Solutions Architect Associate" ≈ 0.799 → the §11.3 near-miss
 *   band → provisional link + entity-review list;
 * - c08 vs c09 claim cosine ≈ 0.955 ≥ τ_high 0.93 → rung-3 auto-REPEATS;
 * - c16 (SKILL Java) vs c17 (PLACE Java) claim cosine ≈ 0.21 → never a candidate pair, while the
 *   two "Java" entities stay type-scoped twins in the canon.
 */
object DryRunStage3Corpus {

    /** The seeded subject id — fixed so re-seeds overwrite and pair cache keys stay stable. */
    const val SUBJECT_ID = "stage3-dryrun-asha"

    const val SUBJECT_NAME = "Asha"

    val assets: List<CorpusAsset> =
        listOf(
            CorpusAsset("self", "Self interview (dry-run)", "AUDIO", "SELF_INTERVIEW", "SELF"),
            CorpusAsset(
                "degree",
                "Degree transcript (dry-run)",
                "DOCUMENT",
                "DEGREE_TRANSCRIPT",
                "INSTITUTION",
            ),
            CorpusAsset(
                "manager",
                "Manager endorsement call (dry-run)",
                "AUDIO",
                "MANAGER_ENDORSEMENT",
                "MANAGER",
            ),
            CorpusAsset(
                "peer",
                "Peer endorsement call (dry-run)",
                "AUDIO",
                "PEER_ENDORSEMENT",
                "PEER",
            ),
            CorpusAsset("public", "Public profile (dry-run)", "LINK", "LINKEDIN", "UNKNOWN"),
            CorpusAsset(
                "awscert",
                "AWS certificate (dry-run)",
                "DOCUMENT",
                "CERTIFICATE",
                "INSTITUTION",
            ),
        )

    private val asha = ExtractedMention(SUBJECT_NAME, EntityType.PERSON)

    val claims: List<CorpusClaim> =
        listOf(
            // ---- The §11.8 worked example (c01–c06) --------------------------------------
            // c01+c02 → F1 (judged REPEATS 5/5): anchored EVENT 2016, B0 = 0.9025 → 0.90.
            CorpusClaim(
                claimId = "asha-c01",
                marker = "completed a b.e.",
                text = "Asha completed a B.E. in Computer Science at University X in 2016.",
                type = "EPISODE",
                assetKey = "degree",
                claimedDate = "2016-06-15",
                tierSeed = "HIGH",
                favorability = 0.8,
                mentions = listOf(asha, ExtractedMention("University X", EntityType.INSTITUTION)),
                issuerSurface = "University X",
            ),
            CorpusClaim(
                claimId = "asha-c02",
                marker = "graduated in computer science",
                text = "Asha graduated in Computer Science from University X.",
                type = "EPISODE",
                assetKey = "self",
                claimedDate = null,
                tierSeed = "LOW",
                favorability = 0.7,
                mentions = listOf(asha, ExtractedMention("University X", EntityType.INSTITUTION)),
            ),
            // c03+c04(+c07) → F2 (judged REPEATS 4/5): volatile TIMELESS, B0 = 0.74.
            CorpusClaim(
                claimId = "asha-c03",
                marker = "strong kotlin engineer",
                text = "Asha is a strong Kotlin engineer.",
                type = "SKILL",
                assetKey = "manager",
                claimedDate = null,
                tierSeed = "MEDIUM",
                favorability = 0.9,
                mentions = listOf(asha, ExtractedMention("Kotlin", EntityType.SKILL)),
            ),
            CorpusClaim(
                claimId = "asha-c04",
                marker = "expert kotlin developer",
                text = "Asha is an expert Kotlin developer.",
                type = "SKILL",
                assetKey = "self",
                claimedDate = null,
                tierSeed = "LOW",
                favorability = 0.9,
                mentions = listOf(asha, ExtractedMention("Kotlin", EntityType.SKILL)),
            ),
            // c05 → F3, c06 → F4: the overlapping-2019 CONTRADICTS (4/5, conf 0.72) that survives
            // the temporal gate; c05's sidecar drives the §11.9 dual judge whose ctx verdict
            // (3/5 × 0.6 = 0.36) falls below the 0.55 floor → explained=true, queue stays empty.
            CorpusClaim(
                claimId = "asha-c05",
                marker = "led the payments migration",
                text = "Asha led the payments migration in 2019.",
                type = "EPISODE",
                assetKey = "self",
                claimedDate = "2019-03-01",
                tierSeed = "LOW",
                favorability = 0.9,
                mentions = listOf(asha, ExtractedMention("payments migration", EntityType.PROJECT)),
                sidecar =
                    "I led the backend workstream of the payments migration; Vikram was the " +
                        "overall program lead.",
            ),
            CorpusClaim(
                claimId = "asha-c06",
                marker = "led by vikram",
                text = "The payments migration was led by Vikram; Asha worked on it.",
                type = "EPISODE",
                assetKey = "peer",
                claimedDate = "2019-03-01",
                tierSeed = "MEDIUM",
                favorability = 0.4,
                mentions =
                    listOf(
                        asha,
                        // ≈0.854 to c05's mint → confident kNN merge + alias adoption (§11.3).
                        ExtractedMention("the payments migration", EntityType.PROJECT),
                        ExtractedMention("Vikram", EntityType.PERSON),
                    ),
            ),
            // ---- Extension claims (every remaining §11 branch) ---------------------------
            // c07: rung-2 same-source dupe of c04 (same asset, normalization-identical text) —
            // AUTO REPEATS without the judge; F2 grows to {c03,c04,c07}, B0 unchanged (same-SELF
            // group collapses under noisy-OR max).
            CorpusClaim(
                claimId = "asha-c07",
                marker = "expert kotlin developer",
                text = "Asha is an expert Kotlin developer!",
                type = "SKILL",
                assetKey = "self",
                claimedDate = null,
                tierSeed = "LOW",
                favorability = 0.9,
                mentions = listOf(asha, ExtractedMention("Kotlin", EntityType.SKILL)),
            ),
            // c08+c09: rung-3 τ_high auto-REPEATS (claim cosine ≈ 0.955, different assets).
            CorpusClaim(
                claimId = "asha-c08",
                marker = "stem mentor on weekends",
                text = "Asha volunteers as a STEM mentor on weekends.",
                type = "VALUE",
                assetKey = "self",
                claimedDate = null,
                tierSeed = "LOW",
                favorability = 0.8,
                mentions = listOf(asha),
            ),
            CorpusClaim(
                claimId = "asha-c09",
                marker = "stem mentor on weekend.",
                text = "Asha volunteers as a STEM mentor on weekend.",
                type = "VALUE",
                assetKey = "public",
                claimedDate = null,
                tierSeed = "MEDIUM",
                favorability = 0.8,
                mentions = listOf(asha),
            ),
            // c10+c11: judged CORROBORATES (credential → episode); c10 exercises the second
            // issuer upgrade; c11's credential surface lands in the §11.3 near-miss band
            // (≈0.799) → provisional link + entity-review list.
            CorpusClaim(
                claimId = "asha-c10",
                marker = "solutions architect certification",
                text =
                    "Asha holds an AWS Solutions Architect certification issued by Amazon Web " +
                        "Services in 2021.",
                type = "EPISODE",
                assetKey = "awscert",
                claimedDate = "2021-08-01",
                tierSeed = "HIGH",
                favorability = 0.8,
                mentions =
                    listOf(
                        asha,
                        ExtractedMention("AWS Solutions Architect", EntityType.CREDENTIAL),
                        ExtractedMention("AWS", EntityType.SKILL),
                        ExtractedMention("Amazon Web Services", EntityType.ORG),
                    ),
                issuerSurface = "Amazon Web Services",
            ),
            CorpusClaim(
                claimId = "asha-c11",
                marker = "designed the cloud deployment",
                text =
                    "Asha designed the cloud deployment architecture on AWS and is preparing " +
                        "for the AWS Solutions Architect Associate exam.",
                type = "EPISODE",
                assetKey = "peer",
                claimedDate = null,
                tierSeed = "MEDIUM",
                favorability = 0.8,
                mentions =
                    listOf(
                        asha,
                        ExtractedMention("AWS", EntityType.SKILL),
                        ExtractedMention(
                            "AWS Solutions Architect Associate",
                            EntityType.CREDENTIAL,
                        ),
                    ),
            ),
            // c12+c13: judged CONTRADICTS whose STATE intervals are disjoint → the §11.7 gate
            // drops the edge and the EMPLOYER slot sequences them (SUCCEEDS chain).
            CorpusClaim(
                claimId = "asha-c12",
                marker = "at infosys in bengaluru",
                text = "Asha is a software engineer at Infosys in Bengaluru.",
                type = "IDENTITY",
                assetKey = "self",
                claimedDate = "2016-01-10",
                tierSeed = "LOW",
                favorability = 0.6,
                mentions =
                    listOf(
                        asha,
                        ExtractedMention("Infosys", EntityType.ORG),
                        ExtractedMention("Bengaluru", EntityType.PLACE),
                    ),
            ),
            CorpusClaim(
                claimId = "asha-c13",
                marker = "at google in singapore",
                text = "Asha is a software engineer at Google in Singapore.",
                type = "IDENTITY",
                assetKey = "self",
                claimedDate = "2024-02-01",
                tierSeed = "LOW",
                favorability = 0.6,
                mentions =
                    listOf(
                        asha,
                        ExtractedMention("Google", EntityType.ORG),
                        ExtractedMention("Singapore", EntityType.PLACE),
                    ),
            ),
            // c14+c15: the tie branch — 2×NEUTRAL / 2×CONTRADICTS / 1×CORROBORATES → NEUTRAL by
            // precedence, tie=true (the §15 #4 counter), no edge.
            CorpusClaim(
                claimId = "asha-c14",
                marker = "speaks fluent german",
                text = "Asha speaks fluent German.",
                type = "SKILL",
                assetKey = "self",
                claimedDate = null,
                tierSeed = "LOW",
                favorability = 0.8,
                mentions = listOf(asha, ExtractedMention("German", EntityType.SKILL)),
            ),
            CorpusClaim(
                claimId = "asha-c15",
                marker = "learning basic german",
                text = "Asha is still learning basic German.",
                type = "SKILL",
                assetKey = "peer",
                claimedDate = null,
                tierSeed = "MEDIUM",
                favorability = 0.5,
                mentions = listOf(asha, ExtractedMention("German", EntityType.SKILL)),
            ),
            // c16+c17: the "Java" type-scoping case — same surface, SKILL vs PLACE, two canon
            // entities; the claims themselves never become a candidate pair (cosine ≈ 0.21).
            CorpusClaim(
                claimId = "asha-c16",
                marker = "expert in java programming",
                text = "Asha is an expert in Java programming.",
                type = "SKILL",
                assetKey = "self",
                claimedDate = null,
                tierSeed = "LOW",
                favorability = 0.9,
                mentions = listOf(asha, ExtractedMention("Java", EntityType.SKILL)),
            ),
            CorpusClaim(
                claimId = "asha-c17",
                marker = "living on java",
                text = "Asha spent a year living on Java before moving to Singapore.",
                type = "EPISODE",
                assetKey = "self",
                claimedDate = null,
                tierSeed = "LOW",
                favorability = 0.6,
                mentions =
                    listOf(
                        asha,
                        ExtractedMention("Java", EntityType.PLACE),
                        ExtractedMention("Singapore", EntityType.PLACE),
                    ),
            ),
            // c18+c19: the normalization-alias case — "R. Mehta" and "R Mehta" share one
            // canonical key, so one PERSON entity carries both surfaces (EXACT hits, §11.3).
            CorpusClaim(
                claimId = "asha-c18",
                marker = "reported to r. mehta",
                text = "Asha reported to R. Mehta and deepened her Kotlin skills at Infosys.",
                type = "EPISODE",
                assetKey = "self",
                claimedDate = null,
                tierSeed = "LOW",
                favorability = 0.7,
                mentions =
                    listOf(
                        asha,
                        ExtractedMention("R. Mehta", EntityType.PERSON),
                        ExtractedMention("Infosys", EntityType.ORG),
                        ExtractedMention("Kotlin", EntityType.SKILL),
                    ),
            ),
            CorpusClaim(
                claimId = "asha-c19",
                marker = "rated asha",
                text = "R Mehta rated Asha's Kotlin work highly in her annual review.",
                type = "SKILL",
                assetKey = "peer",
                claimedDate = null,
                tierSeed = "MEDIUM",
                favorability = 0.9,
                mentions =
                    listOf(
                        asha,
                        ExtractedMention("R Mehta", EntityType.PERSON),
                        ExtractedMention("Kotlin", EntityType.SKILL),
                    ),
            ),
        )

    // ---- dry-run extraction (LLD §11.12: canned typed mentions) --------------------------

    /**
     * Canned mentions for a corpus claim, matched by marker — null for non-corpus text (the
     * extractor stamps those with no mentions, exactly the pre-VA-19 behavior).
     */
    fun mentionsFor(text: String): ExtractedMentions? =
        claimFor(text)?.let { ExtractedMentions(it.mentions, it.issuerSurface) }

    private fun claimFor(text: String): CorpusClaim? {
        val hay = text.lowercase()
        return claims.firstOrNull { it.marker in hay }
    }

    // ---- dry-run judging (LLD §11.12: scripted verdict table) ----------------------------

    private fun repeats(conf: Double) =
        JudgeSample(JudgeRelation.REPEATS, conf, "Scripted: same proposition.", null, null)

    private fun corroborates(conf: Double) =
        JudgeSample(
            JudgeRelation.CORROBORATES,
            conf,
            "Scripted: distinct but supporting.",
            null,
            null,
        )

    private fun contradicts(conf: Double, temporalNote: String) =
        JudgeSample(JudgeRelation.CONTRADICTS, conf, "Scripted: incompatible.", temporalNote, null)

    private fun neutral(conf: Double) =
        JudgeSample(JudgeRelation.NEUTRAL, conf, "Scripted: unrelated.", null, null)

    /** The default answer for pairs outside the table — NEUTRAL, casting no edge. */
    private val NEUTRAL_DEFAULT = neutral(0.6)

    private val OVERLAP_NOTE = "Both claims describe the same 2019 payments migration."
    private val DISJOINT_NOTE =
        "The Infosys and Google roles are dated eight years apart — not simultaneous."

    private val verdictRows: List<VerdictRow> =
        listOf(
            // (c01,c02) — judged REPEATS 5/5 → F1.
            VerdictRow(
                markerA = "completed a b.e.",
                markerB = "graduated in computer science",
                bare = VerdictScript(List(5) { repeats(0.9) }),
            ),
            // (c03,c04)/(c03,c07) — judged REPEATS 4/5 → F2 (votes 4/5 per the worked example).
            VerdictRow(
                markerA = "strong kotlin engineer",
                markerB = "expert kotlin developer",
                bare = VerdictScript(List(4) { repeats(0.9) } + neutral(0.6)),
            ),
            // (c05,c06) — the worked example's contested episode: bare CONTRADICTS 4/5 × 0.9 =
            // 0.72; ctx CONTRADICTS 3/5 × 0.6 = 0.36 < floor → NEUTRAL with relevance affirmed.
            VerdictRow(
                markerA = "led the payments migration",
                markerB = "led by vikram",
                bare =
                    VerdictScript(
                        List(4) { contradicts(0.9, OVERLAP_NOTE) } + neutral(0.5),
                    ),
                ctx =
                    VerdictScript(
                        (List(3) { contradicts(0.6, OVERLAP_NOTE) } + List(2) { neutral(0.5) })
                            .map { it.copy(explanationRelevant = true) },
                    ),
            ),
            // (c10,c11) — judged CORROBORATES 4/5 × 0.8 = 0.64.
            VerdictRow(
                markerA = "solutions architect certification",
                markerB = "designed the cloud deployment",
                bare = VerdictScript(List(4) { corroborates(0.8) } + neutral(0.5)),
            ),
            // (c12,c13) — judged CONTRADICTS 4/5 × 0.9 = 0.72 with a disjoint-span note; the
            // §11.7 gate turns it into the EMPLOYER SUCCEEDS chain.
            VerdictRow(
                markerA = "at infosys in bengaluru",
                markerB = "at google in singapore",
                bare =
                    VerdictScript(
                        List(4) { contradicts(0.9, DISJOINT_NOTE) } + neutral(0.5),
                    ),
            ),
            // (c14,c15) — the tie: leaders {NEUTRAL, CONTRADICTS} → NEUTRAL precedence, tie=true.
            VerdictRow(
                markerA = "speaks fluent german",
                markerB = "learning basic german",
                bare =
                    VerdictScript(
                        listOf(
                            neutral(0.6),
                            contradicts(0.7, "Fluency and 'still learning' cannot both hold now."),
                            neutral(0.6),
                            contradicts(0.7, "Fluency and 'still learning' cannot both hold now."),
                            corroborates(0.5),
                        ),
                    ),
            ),
        )

    /**
     * The scripted per-sample answer for a pair variant, matched by unordered marker pair; falls
     * back to NEUTRAL (no edge) for anything outside the table, including [sampleIndex] beyond a
     * script's length. A row without a `ctx` script answers the withContext variant NEUTRAL with
     * relevance denied — only (c05,c06) carries a real dual verdict, per the worked example.
     */
    fun verdictSample(
        aText: String,
        bText: String,
        withContext: Boolean,
        sampleIndex: Int,
    ): JudgeSample {
        val a = aText.lowercase()
        val b = bText.lowercase()
        val row =
            verdictRows.firstOrNull {
                (it.markerA in a && it.markerB in b) || (it.markerA in b && it.markerB in a)
            }
        val script = if (withContext) row?.ctx else row?.bare
        val sample =
            script?.samples?.getOrNull(sampleIndex)
                ?: if (withContext) NEUTRAL_DEFAULT.copy(explanationRelevant = false)
                else NEUTRAL_DEFAULT
        return sample
    }
}

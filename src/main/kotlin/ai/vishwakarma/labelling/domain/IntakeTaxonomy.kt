package ai.vishwakarma.labelling.domain

/**
 * Stage 1 (Intake & Manifest) taxonomy — the dimensions that classify a piece of raw material about
 * a subject before Stage 2 (Transcribe + ClaimExtract) processes it unattended.
 *
 * Five orthogonal dimensions:
 * - [AssetModality] — the file-format lane; decides *how* Stage 2 processes the bytes.
 * - [SourceClass] — origin category; the primary driver of the authenticity prior.
 * - [ContentType] — *what it contains*; each maps to one [SourceClass] + a default prior.
 * - [Relationship] — the asset author/source's relationship to the subject; refines the prior.
 * - [ConsentStatus] — first-class, required for the audit trail and "delete on request".
 *
 * Every enum exposes a tolerant `fromOrNull(String?)` mirroring [ClaimType.fromOrNull], so reads of
 * legacy/missing/garbage values degrade to null rather than throwing.
 */

/** File-format lane → the Stage 2 processor that ingests it. */
enum class AssetModality {
    /** Audio (mp3, wav, m4a, aac, flac, ogg) → WhisperX + pyannote (ASR/diarization). */
    AUDIO,
    /** Video (mp4, mov, webm, mkv) → demux → ASR/diarization. */
    VIDEO,
    /** Images / scans (jpg, png, heic, webp, tiff) → Vision-LLM OCR. */
    IMAGE,
    /** Rich documents (pdf, docx, doc, rtf, odt, pptx) → text extraction. */
    DOCUMENT,
    /** Plain/written text (pasted story, md, txt) → used directly. */
    TEXT,
    /** External URL with no stored bytes → trafilatura / PyGithub / scraper. */
    LINK,
    /** Archive (zip: LinkedIn export, repo dump) → unpacked and re-routed. */
    ARCHIVE;

    companion object {
        fun fromOrNull(raw: String?): AssetModality? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Origin category. The coarse driver of the authenticity prior handed to Stage 3. */
enum class SourceClass {
    /** Produced by the subject themselves (low prior — unverified self-report). */
    SELF,
    /** Third-party testimony *about* the subject (prior depends on [Relationship]). */
    ENDORSEMENT,
    /** Verifiable artifact / hard anchor (cert, degree, award, commit, press). */
    DOCUMENTARY,
    /** Online presence / link (LinkedIn, GitHub, owned product, press). */
    PUBLIC_PROFILE,
    /** Audio/video capture of a specific real moment (ceremony, talk, match). */
    EVENT_CAPTURE;

    companion object {
        fun fromOrNull(raw: String?): SourceClass? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * What the asset *contains*. Each value belongs to exactly one [sourceClass] and carries a
 * [basePrior] used by [defaultPrior]. Grouped by source class for readability.
 */
enum class ContentType(val sourceClass: SourceClass, val basePrior: AuthenticityTier) {
    // --- SELF (subject-originated; low prior) ---------------------------------
    SELF_INTERVIEW(SourceClass.SELF, AuthenticityTier.LOW),
    ACCOMPLISHMENT_STORY(SourceClass.SELF, AuthenticityTier.LOW),
    RESUME_CV(SourceClass.SELF, AuthenticityTier.LOW),
    PERSONAL_BIO_STATEMENT(SourceClass.SELF, AuthenticityTier.LOW),
    WORK_SAMPLE_PORTFOLIO(SourceClass.SELF, AuthenticityTier.LOW),
    SKILL_DEMO(SourceClass.SELF, AuthenticityTier.LOW),
    AUTHORED_ARTICLE_BLOG(SourceClass.SELF, AuthenticityTier.LOW),
    AUTHORED_SOCIAL_POST(SourceClass.SELF, AuthenticityTier.LOW),

    // --- ENDORSEMENT (third-party testimony; base MEDIUM, refined by relationship) ---
    EXPERT_INTERVIEW(SourceClass.ENDORSEMENT, AuthenticityTier.MEDIUM),
    PEER_ENDORSEMENT(SourceClass.ENDORSEMENT, AuthenticityTier.MEDIUM),
    MANAGER_ENDORSEMENT(SourceClass.ENDORSEMENT, AuthenticityTier.MEDIUM),
    MENTOR_TEACHER_ENDORSEMENT(SourceClass.ENDORSEMENT, AuthenticityTier.MEDIUM),
    CLIENT_TESTIMONIAL(SourceClass.ENDORSEMENT, AuthenticityTier.MEDIUM),
    PERSONAL_REFERENCE(SourceClass.ENDORSEMENT, AuthenticityTier.MEDIUM),
    RECOMMENDATION_LETTER(SourceClass.ENDORSEMENT, AuthenticityTier.MEDIUM),

    // --- DOCUMENTARY (hard anchors; high prior) -------------------------------
    CERTIFICATE(SourceClass.DOCUMENTARY, AuthenticityTier.HIGH),
    DEGREE_TRANSCRIPT(SourceClass.DOCUMENTARY, AuthenticityTier.HIGH),
    AWARD_HONOR(SourceClass.DOCUMENTARY, AuthenticityTier.HIGH),
    LICENSE_ACCREDITATION(SourceClass.DOCUMENTARY, AuthenticityTier.HIGH),
    PATENT_PUBLICATION(SourceClass.DOCUMENTARY, AuthenticityTier.HIGH),
    PRESS_COVERAGE(SourceClass.DOCUMENTARY, AuthenticityTier.HIGH),
    COMPETITION_RESULT(SourceClass.DOCUMENTARY, AuthenticityTier.HIGH),
    EMPLOYMENT_RECORD(SourceClass.DOCUMENTARY, AuthenticityTier.HIGH),

    // --- PUBLIC_PROFILE (links; verifiable artifacts HIGH, social MEDIUM) -----
    LINKEDIN(SourceClass.PUBLIC_PROFILE, AuthenticityTier.MEDIUM),
    GITHUB(SourceClass.PUBLIC_PROFILE, AuthenticityTier.HIGH),
    PERSONAL_WEBSITE(SourceClass.PUBLIC_PROFILE, AuthenticityTier.MEDIUM),
    APP_STORE_LISTING(SourceClass.PUBLIC_PROFILE, AuthenticityTier.HIGH),
    SCHOLARLY_PROFILE(SourceClass.PUBLIC_PROFILE, AuthenticityTier.HIGH),
    SOCIAL_PROFILE(SourceClass.PUBLIC_PROFILE, AuthenticityTier.MEDIUM),
    PORTFOLIO_PLATFORM(SourceClass.PUBLIC_PROFILE, AuthenticityTier.MEDIUM),
    BLOG_PUBLICATION(SourceClass.PUBLIC_PROFILE, AuthenticityTier.MEDIUM),

    // --- EVENT_CAPTURE (A/V of a moment; medium prior) ------------------------
    AWARD_CEREMONY(SourceClass.EVENT_CAPTURE, AuthenticityTier.MEDIUM),
    SPEECH_TALK(SourceClass.EVENT_CAPTURE, AuthenticityTier.MEDIUM),
    SPORTS_PERFORMANCE(SourceClass.EVENT_CAPTURE, AuthenticityTier.MEDIUM),
    ARTISTIC_PERFORMANCE(SourceClass.EVENT_CAPTURE, AuthenticityTier.MEDIUM),
    DEMO_PITCH(SourceClass.EVENT_CAPTURE, AuthenticityTier.MEDIUM),
    TEACHING_SESSION(SourceClass.EVENT_CAPTURE, AuthenticityTier.MEDIUM);

    companion object {
        fun fromOrNull(raw: String?): ContentType? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** The asset author/source's relationship to the subject. Refines endorsement priors. */
enum class Relationship {
    SELF,
    EXPERT,
    PEER,
    MANAGER,
    MENTOR,
    CLIENT,
    FAMILY,
    /** Issuing body for a credential (university, certifier, awarding org). */
    INSTITUTION,
    /** Third-party media / journalist. */
    PRESS,
    UNKNOWN;

    companion object {
        fun fromOrNull(raw: String?): Relationship? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/** Consent state for an asset; required before its content can be baked into a model. */
enum class ConsentStatus {
    PENDING,
    GRANTED,
    REVOKED,
    /** The subject's own public artifact — no third-party consent needed. */
    NOT_REQUIRED;

    companion object {
        fun fromOrNull(raw: String?): ConsentStatus? =
            raw?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() }
    }
}

/**
 * Derive the default authenticity prior (handed to Stage 3) from the content type and the source's
 * relationship to the subject. Starts from [ContentType.basePrior] and only *refines* the
 * [SourceClass.ENDORSEMENT] case, where the relationship is the deciding factor:
 * - EXPERT → HIGH (a domain authority vouching)
 * - MANAGER / MENTOR / PEER / CLIENT → MEDIUM
 * - FAMILY / UNKNOWN / (anything else) → LOW
 *
 * For every other source class the relationship doesn't move the needle, so the base prior stands
 * (DOCUMENTARY = HIGH, SELF = LOW, EVENT_CAPTURE = MEDIUM, PUBLIC_PROFILE = per content type).
 */
fun defaultPrior(contentType: ContentType, relationship: Relationship?): AuthenticityTier =
    if (contentType.sourceClass == SourceClass.ENDORSEMENT) {
        when (relationship) {
            Relationship.EXPERT -> AuthenticityTier.HIGH
            Relationship.MANAGER,
            Relationship.MENTOR,
            Relationship.PEER,
            Relationship.CLIENT -> AuthenticityTier.MEDIUM
            else -> AuthenticityTier.LOW
        }
    } else {
        contentType.basePrior
    }

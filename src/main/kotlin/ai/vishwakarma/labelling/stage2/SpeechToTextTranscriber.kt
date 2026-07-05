package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.serialization.Json
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * [Transcriber] backed by Google Cloud Speech-to-Text v2 `batchRecognize` (async LRO with speaker
 * diarization), called over REST with the app SA's ADC bearer token — the same construction as
 * [ai.vishwakarma.labelling.vertex.TuningService]. Results are written by STT itself to the
 * transcripts bucket; poll reads them back and normalizes to [Transcript].
 *
 * With `app.stage2.dry-run` (dev profile) both calls short-circuit: submit returns a `dry-run/`
 * operation name and poll returns a canned diarized transcript, so the whole Stage 2 flow runs
 * without GCP.
 */
@Component
class SpeechToTextTranscriber(private val props: AppProperties, private val probe: Mp4AudioProbe) :
    Transcriber {

    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.create()

    private fun token(): String =
        GoogleCredentials.getApplicationDefault()
            .createScoped("https://www.googleapis.com/auth/cloud-platform")
            .also { it.refreshIfExpired() }
            .accessToken
            .tokenValue

    /**
     * STT batchRecognize location (§12.4): the configured `app.stage2.stt-location`, or the app
     * region when blank (the in-region, single-region default). This is decoupled from the app's
     * GCP region so diarization can run on a multi-region/global endpoint without moving the app.
     */
    private fun location(): String = props.stage2.sttLocation.ifBlank { props.gcp.region }

    private fun base(): String = "https://${sttHost(location())}/v2"

    override fun submit(
        subjectId: String,
        assetId: String,
        gcsUri: String,
        mimeType: String?,
        attempt: Int,
        hints: List<String>,
    ): String {
        if (props.stage2.dryRun) {
            log.info("Stage 2 dry-run: simulating batchRecognize submit for asset {}", assetId)
            return "$DRY_RUN_PREFIX$assetId"
        }
        val bucket =
            props.stage2.transcriptsBucket.ifBlank {
                error("app.stage2.transcripts-bucket not set")
            }
        val attempts = decodingAttempts(mimeType, gcsUri)
        require(attempt in attempts.indices) {
            "decoding attempts exhausted for mimeType=$mimeType " +
                "(attempt $attempt, ${attempts.size} available)"
        }
        val decoding = attempts[attempt]
        log.info(
            "Submitting batchRecognize for asset {} (decoding attempt {}: {}, mimeType={})",
            assetId,
            attempt,
            decoding.keys.first(),
            mimeType,
        )
        return submitWithDiarizationFallback(subjectId, assetId, gcsUri, bucket, decoding, hints)
    }

    /**
     * Ordered decoding candidates for an asset's mime type. Verified 2026-07-04: STT v2's
     * `autoDecodingConfig` rejects AAC-family containers (mp4/m4a/mov) with "Audio data does not
     * appear to be in a supported encoding" — those need `explicitDecodingConfig`. So AAC
     * containers lead with explicit (auto as the fallback); everything else leads with auto (an
     * MP4_AAC guess as the last resort for mislabelled uploads). Decode errors surface while the
     * operation RUNS, not at submit — [poll] flags them retryable and the caller resubmits with the
     * next [submit] attempt index.
     */
    private fun decodingAttempts(mimeType: String?, gcsUri: String): List<Map<String, Any>> {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase()
        val auto: Map<String, Any> = mapOf("autoDecodingConfig" to emptyMap<String, Any>())
        val aacEncoding = AAC_CONTAINER_ENCODINGS[normalized]
        // Chirp (USM) models auto-detect the container encoding — Google's chirp_3 sample uses
        // autoDecodingConfig even for AAC (mp4/m4a/mov). Sending an explicit AAC config to Chirp
        // both
        // wastes an async attempt and risks the sample-rate/channel mismatch that surfaces as
        // "Provided file is empty", so on Chirp lead with auto (explicit stays only as a defensive
        // fallback for AAC). The explicit-first path below is a legacy long/conformer need (§11.2).
        if (isChirpModel()) {
            return if (aacEncoding == null) listOf(auto)
            else listOf(auto, explicitDecoding(aacEncoding, probe.probe(gcsUri)))
        }
        if (aacEncoding == null) {
            // Non-AAC leads with auto; the explicit MP4_AAC is only a last-resort guess for a
            // mislabelled upload, so it keeps the configured defaults (no probe).
            return listOf(auto, explicitDecoding("MP4_AAC", null))
        }
        // AAC container → explicit leads: probe the header for the real sample rate/channels so a
        // non-48kHz/stereo capture decodes right (§12.7); config defaults are the fallback.
        val probed = probe.probe(gcsUri)
        if (probed != null)
            log.info(
                "Probed audio params for {}: {} Hz, {} channel(s)",
                gcsUri,
                probed.sampleRateHertz,
                probed.channelCount,
            )
        return listOf(explicitDecoding(aacEncoding, probed), auto)
    }

    /** Chirp (USM) models auto-detect encoding; the explicit-AAC cascade is a legacy-model need. */
    private fun isChirpModel(): Boolean =
        props.stage2.sttModel.startsWith("chirp", ignoreCase = true)

    private fun explicitDecoding(encoding: String, probed: AudioParams?): Map<String, Any> =
        mapOf(
            "explicitDecodingConfig" to
                mapOf(
                    "encoding" to encoding,
                    "sampleRateHertz" to
                        (probed?.sampleRateHertz ?: props.stage2.explicitSampleRateHertz),
                    "audioChannelCount" to
                        (probed?.channelCount ?: props.stage2.explicitChannelCount),
                )
        )

    private fun submitWithDiarizationFallback(
        subjectId: String,
        assetId: String,
        gcsUri: String,
        bucket: String,
        decoding: Map<String, Any>,
        hints: List<String>,
    ): String =
        try {
            submitBatch(subjectId, assetId, gcsUri, bucket, decoding, hints, diarization = true)
        } catch (e: HttpClientErrorException) {
            // Verified 2026-07-04: single-region locations (e.g. asia-southeast1) reject
            // diarization for batchRecognize. Degrade to an unattributed transcript (speaker =
            // null) instead of failing the asset; the diarized path resumes automatically if
            // Google adds regional support.
            if (
                e.statusCode.value() == 400 &&
                    e.responseBodyAsString.contains("Diarization is not currently supported")
            ) {
                log.warn(
                    "batchRecognize rejects diarization in {}; resubmitting without it " +
                        "(§12.4: point app.stage2.stt-location at a chirp_3 diarization endpoint)",
                    location(),
                )
                submitBatch(
                    subjectId,
                    assetId,
                    gcsUri,
                    bucket,
                    decoding,
                    hints,
                    diarization = false
                )
            } else throw e
        }

    private fun submitBatch(
        subjectId: String,
        assetId: String,
        gcsUri: String,
        bucket: String,
        decoding: Map<String, Any>,
        hints: List<String>,
        diarization: Boolean,
    ): String {
        val features = buildMap {
            put("enableWordTimeOffsets", true)
            put("enableAutomaticPunctuation", true)
            if (diarization) {
                put(
                    "diarizationConfig",
                    mapOf("minSpeakerCount" to 1, "maxSpeakerCount" to props.stage2.maxSpeakers),
                )
            }
        }
        val config = buildMap {
            put("model", props.stage2.sttModel)
            put("languageCodes", listOf(props.stage2.sttLanguage))
            putAll(decoding)
            put("features", features)
            // Phrase hints (subject name): without them ASR garbles the one term every claim
            // depends on. Boost 10 is Google's recommended starting strength (0–20). Gated by
            // app.stage2.stt-phrase-hints because the USM-based chirp_3 model may reject model
            // adaptation (§12.4 E1) — a chirp_3 deployment turns this off if the probe shows it.
            if (hints.isNotEmpty() && props.stage2.sttPhraseHints) {
                put(
                    "adaptation",
                    mapOf(
                        "phraseSets" to
                            listOf(
                                mapOf(
                                    "inlinePhraseSet" to
                                        mapOf(
                                            "phrases" to
                                                hints.map { mapOf("value" to it, "boost" to 10) }
                                        )
                                )
                            )
                    ),
                )
            }
        }
        val body =
            mapOf(
                "files" to listOf(mapOf("uri" to gcsUri)),
                "config" to config,
                "recognitionOutputConfig" to
                    mapOf(
                        "gcsOutputConfig" to
                            mapOf("uri" to "gs://$bucket/transcripts/$subjectId/$assetId/")
                    ),
            )
        val url =
            "${base()}/projects/${props.gcp.projectId}/locations/${location()}" +
                "/recognizers/_:batchRecognize"
        log.info(
            "Submitting batchRecognize for asset {} ({}, diarization={})",
            assetId,
            gcsUri,
            diarization,
        )
        val response =
            rest
                .post()
                .uri(url)
                .header("Authorization", "Bearer ${token()}")
                .body(body)
                .retrieve()
                .body(String::class.java) ?: error("empty batchRecognize response")
        val map =
            Json.parse(response) as? Map<*, *> ?: error("bad batchRecognize response: $response")
        return map["name"] as? String ?: error("batchRecognize response missing name: $response")
    }

    @Suppress("UNCHECKED_CAST")
    override fun poll(operationName: String): TranscriptionPoll {
        if (operationName.startsWith(DRY_RUN_PREFIX)) {
            log.info("Stage 2 dry-run: returning canned transcript for {}", operationName)
            return TranscriptionPoll.Done(cannedTranscript(), null)
        }
        val response =
            rest
                .get()
                .uri("${base()}/$operationName")
                .header("Authorization", "Bearer ${token()}")
                .retrieve()
                .body(String::class.java) ?: error("empty operation response")
        val map = Json.parse(response) as? Map<String, Any?> ?: error("bad operation response")
        if (map["done"] != true) return TranscriptionPoll.Running
        (map["error"] as? Map<String, Any?>)?.let {
            val message = it["message"] as? String ?: "batchRecognize failed"
            return TranscriptionPoll.Failed(message, isRetryableDecodeFailure(message))
        }
        val results =
            ((map["response"] as? Map<String, Any?>)?.get("results") as? Map<String, Any?>)
                ?: return TranscriptionPoll.Failed("operation done but carries no results")
        val fileResult =
            results.values.firstOrNull() as? Map<String, Any?>
                ?: return TranscriptionPoll.Failed("batchRecognize response has no per-file result")
        (fileResult["error"] as? Map<String, Any?>)?.let {
            val message = it["message"] as? String ?: "transcription failed"
            return TranscriptionPoll.Failed(message, isRetryableDecodeFailure(message))
        }
        val inline =
            ((fileResult["inlineResult"] as? Map<String, Any?>)?.get("transcript")
                as? Map<String, Any?>)
        val uri =
            fileResult["uri"] as? String
                ?: ((fileResult["cloudStorageResult"] as? Map<String, Any?>)?.get("uri") as? String)
        val raw =
            when {
                inline != null -> inline
                uri != null ->
                    Json.parse(readGcsObject(uri)) as? Map<String, Any?>
                        ?: error("bad transcript file at $uri")
                else ->
                    return TranscriptionPoll.Failed(
                        "batchRecognize result carries neither uri nor inlineResult"
                    )
            }
        return TranscriptionPoll.Done(normalize(raw), uri)
    }

    @Suppress("UNCHECKED_CAST")
    override fun fetchTranscript(transcriptUri: String): Transcript {
        val raw =
            Json.parse(readGcsObject(transcriptUri)) as? Map<String, Any?>
                ?: error("bad transcript file at $transcriptUri")
        return normalize(raw)
    }

    /**
     * Normalize STT v2 `BatchRecognizeResults` JSON. When word-level diarization is present,
     * consecutive words sharing a speaker label collapse into one segment; otherwise each result's
     * top alternative becomes an unattributed segment.
     */
    @Suppress("UNCHECKED_CAST")
    private fun normalize(raw: Map<String, Any?>): Transcript {
        val results = raw["results"] as? List<Map<String, Any?>> ?: emptyList()
        val segments = mutableListOf<TranscriptSegment>()
        var language: String? = null
        for (result in results) {
            language = language ?: result["languageCode"] as? String
            val alt =
                (result["alternatives"] as? List<Map<String, Any?>>)?.firstOrNull() ?: continue
            val words = alt["words"] as? List<Map<String, Any?>> ?: emptyList()
            if (words.any { it["speakerLabel"] != null }) {
                var speaker: String? = null
                var start: Double? = null
                var end: Double? = null
                val text = StringBuilder()
                for (w in words) {
                    val word = w["word"] as? String ?: continue
                    val label = w["speakerLabel"] as? String
                    if (label != null && label != speaker && text.isNotEmpty()) {
                        segments +=
                            TranscriptSegment(speakerName(speaker), start, end, text.toString())
                        text.clear()
                        start = null
                    }
                    if (label != null) speaker = label
                    if (start == null) start = seconds(w["startOffset"])
                    end = seconds(w["endOffset"]) ?: end
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(word)
                }
                if (text.isNotEmpty()) {
                    segments += TranscriptSegment(speakerName(speaker), start, end, text.toString())
                }
            } else {
                val transcript = (alt["transcript"] as? String)?.trim().orEmpty()
                if (transcript.isNotEmpty()) {
                    segments +=
                        TranscriptSegment(
                            null,
                            null,
                            seconds(result["resultEndOffset"]),
                            transcript
                        )
                }
            }
        }
        return Transcript(segments, language)
    }

    private fun speakerName(label: String?): String? =
        label?.let { if (it.startsWith("speaker", ignoreCase = true)) it else "Speaker $it" }

    /** Parse an STT duration ("12.500s") or bare number into seconds. */
    private fun seconds(value: Any?): Double? =
        when (value) {
            is String -> value.removeSuffix("s").toDoubleOrNull()
            is Number -> value.toDouble()
            else -> null
        }

    private fun readGcsObject(uri: String): String {
        val path = uri.removePrefix("gs://")
        val slash = path.indexOf('/')
        require(slash > 0) { "not a gs:// object uri: $uri" }
        val storage = StorageOptions.newBuilder().setProjectId(props.gcp.projectId).build().service
        val bytes =
            storage
                .get(BlobId.of(path.substring(0, slash), path.substring(slash + 1)))
                ?.getContent() ?: error("transcript object not found: $uri")
        return String(bytes, Charsets.UTF_8)
    }

    /** Dry-run stand-in: a small two-speaker endorser call rich enough to extract claims from. */
    private fun cannedTranscript(): Transcript =
        Transcript(
            segments =
                listOf(
                    TranscriptSegment(
                        "Speaker 1",
                        0.0,
                        6.5,
                        "Thanks for making time. How do you know the subject, and for how long?",
                    ),
                    TranscriptSegment(
                        "Speaker 2",
                        6.5,
                        15.0,
                        "I managed him for three years at Meridian Software, from 2019 to 2022, " +
                            "on the payments platform team.",
                    ),
                    TranscriptSegment(
                        "Speaker 2",
                        15.0,
                        27.5,
                        "The clearest memory I have is the 2021 gateway migration — he led it " +
                            "end to end and we cut checkout failures by forty percent.",
                    ),
                    TranscriptSegment(
                        "Speaker 1",
                        27.5,
                        32.0,
                        "What would you say his strongest technical skills are?",
                    ),
                    TranscriptSegment(
                        "Speaker 2",
                        32.0,
                        41.0,
                        "Distributed systems and Kotlin services; he is also the person everyone " +
                            "goes to for code reviews.",
                    ),
                    TranscriptSegment(
                        "Speaker 2",
                        41.0,
                        48.5,
                        "If I had to name a growth area, he takes on too much himself before " +
                            "delegating.",
                    ),
                ),
            language = "en-US",
        )

    companion object {
        const val DRY_RUN_PREFIX = "dry-run/"

        /**
         * STT endpoint host for a location (§12.4). Regional and **multi-regional** locations
         * (`us`, `eu`) use the `<loc>-speech.googleapis.com` form; the `global` location is the
         * sole exception — it is served at the bare `speech.googleapis.com` host.
         */
        fun sttHost(location: String): String =
            if (location == "global") "speech.googleapis.com" else "$location-speech.googleapis.com"

        /**
         * A batchRecognize failure where a *different* decoding candidate might still succeed, so
         * [poll] flags it retryable and the service advances the cascade instead of failing
         * outright:
         * - "…supported encoding" — `autoDecodingConfig` rejecting an AAC container (§11.2), and
         * - "Provided file is empty" — what **explicit** AAC decoding returns when the container's
         *   real sample-rate/channels don't line up with the config we sent (observed on an iTunes
         *   44.1 kHz mono m4a whose sample entry `Mp4AudioProbe` couldn't read, so it fell back to
         *   the 48 kHz/stereo default). Auto-decoding may still read it, so cascade rather than
         *   dead-end.
         *
         * A genuinely empty upload can't reach here — it is rejected at intake (IntakeService
         * completeAsset), so an "empty" at transcription time is always a decoding mismatch.
         */
        fun isRetryableDecodeFailure(message: String): Boolean {
            val m = message.lowercase()
            return "supported encoding" in m || "file is empty" in m
        }

        /** AAC-family containers that STT v2 only accepts with an explicit decoding config. */
        private val AAC_CONTAINER_ENCODINGS =
            mapOf(
                "video/mp4" to "MP4_AAC",
                "audio/mp4" to "M4A_AAC",
                "audio/x-m4a" to "M4A_AAC",
                "audio/m4a" to "M4A_AAC",
                "audio/aac" to "M4A_AAC",
                "video/quicktime" to "MOV_AAC",
            )
    }
}

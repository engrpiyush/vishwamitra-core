package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.service.StageConfigService
import com.google.cloud.ReadChannel
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import java.io.Closeable
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** An audio stream's real decoding params, read from the container header. */
data class AudioParams(val sampleRateHertz: Int, val channelCount: Int)

/**
 * Best-effort probe of an MP4 / M4A / MOV audio track's real sample rate and channel count so STT's
 * `explicitDecodingConfig` matches the source instead of the assumed 48 kHz / stereo (§12.7
 * hardening — a 44.1 kHz mono voice memo decodes wrong under the old fixed guess).
 *
 * It reads only the container header — it walks the top-level boxes with small ranged reads to find
 * the `moov` atom (at the file start for faststart MP4, at the very end for the typical
 * phone/screen-recorder capture) and never pulls the `mdat` media payload — then parses the audio
 * sample entry (`mp4a`/`enca`, ISO version 0) for `channelcount` and the 16.16 `samplerate`.
 *
 * Deliberately conservative: any unexpected shape (QuickTime v1/v2 sound descriptions, HE-AAC where
 * the sample-entry rate is the SBR base, an unparseable/short header) returns null, and the caller
 * falls back to the configured defaults — never a wrong guess dressed up as a real reading. The
 * `app.stage2.probe-audio-params` flag disables it entirely.
 */
@Component
class Mp4AudioProbe(private val config: StageConfigService) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Probe [gcsUri] (`gs://` in real envs, `file://` locally), or null to fall back to config. */
    fun probe(gcsUri: String): AudioParams? {
        if (!config.stage2().probeAudioParams) return null
        return try {
            openSource(gcsUri).use { source -> locateMoov(source)?.let { parseMoov(it) } }
        } catch (e: Exception) {
            log.warn("MP4 audio probe failed for {}: {}", gcsUri, e.message)
            null
        }
    }

    /**
     * Parse an audio track's params from the **content** of a `moov` box (its child boxes). Visible
     * for testing; the I/O of fetching the moov bytes is [locateMoov].
     */
    internal fun parseMoov(moov: ByteArray): AudioParams? {
        for (trak in findAll(moov, 0, moov.size, "trak")) {
            val mdia = findFirst(moov, trak.first, trak.second, "mdia") ?: continue
            val minf = findFirst(moov, mdia.first, mdia.second, "minf") ?: continue
            val stbl = findFirst(moov, minf.first, minf.second, "stbl") ?: continue
            val stsd = findFirst(moov, stbl.first, stbl.second, "stsd") ?: continue
            // stsd content = 4 bytes version+flags, 4 bytes entry_count, then the sample entries.
            val entriesStart = stsd.first + 8
            val entry =
                findFirst(moov, entriesStart, stsd.second, "mp4a")
                    ?: findFirst(moov, entriesStart, stsd.second, "enca")
                    ?: continue
            parseAudioSampleEntry(moov, entry.first, entry.second)?.let {
                return it
            }
        }
        return null
    }

    /**
     * An AudioSampleEntry (ISO version 0) laid out from its content start: 6 reserved, 2 data-ref,
     * 2 version, 2 revision, 4 vendor, 2 channelcount, 2 samplesize, 2 pre-defined, 2 reserved, 4
     * samplerate (16.16 — integer part is the high 16 bits).
     */
    private fun parseAudioSampleEntry(buf: ByteArray, start: Int, end: Int): AudioParams? {
        if (end - start < 28) return null
        if (u16(buf, start + 8) != 0)
            return null // QuickTime v1/v2 shift the layout — bail, use config
        val channels = u16(buf, start + 16)
        val sampleRate = u16(buf, start + 24)
        return if (channels in 1..8 && sampleRate in 8_000..192_000)
            AudioParams(sampleRate, channels)
        else null
    }

    // ---- box walking --------------------------------------------------------

    /** Content range [start, end) of the first child box of [type] within [start, end). */
    private fun findFirst(buf: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var pos = start
        while (pos + 8 <= end) {
            val size = u32(buf, pos)
            val boxType = ascii(buf, pos + 4)
            var contentStart = pos + 8
            val boxEnd =
                when (size) {
                    1L -> {
                        if (pos + 16 > end) return null
                        contentStart = pos + 16
                        pos + u64(buf, pos + 8)
                    }
                    0L -> end.toLong()
                    else -> pos + size
                }
            if (boxEnd < contentStart || boxEnd > end) return null
            if (boxType == type) return contentStart to boxEnd.toInt()
            pos = boxEnd.toInt()
        }
        return null
    }

    private fun findAll(buf: ByteArray, start: Int, end: Int, type: String): List<Pair<Int, Int>> {
        val found = mutableListOf<Pair<Int, Int>>()
        var pos = start
        while (pos + 8 <= end) {
            val size = u32(buf, pos)
            val boxType = ascii(buf, pos + 4)
            var contentStart = pos + 8
            val boxEnd =
                when (size) {
                    1L -> {
                        if (pos + 16 > end) break
                        contentStart = pos + 16
                        pos + u64(buf, pos + 8)
                    }
                    0L -> end.toLong()
                    else -> pos + size
                }
            if (boxEnd < contentStart || boxEnd > end) break
            if (boxType == type) found += contentStart to boxEnd.toInt()
            pos = boxEnd.toInt()
        }
        return found
    }

    /** Walk the top-level boxes and return the `moov` box content, or null. */
    private fun locateMoov(source: ByteSource): ByteArray? {
        var pos = 0L
        val size = source.size
        while (pos + 8 <= size) {
            val header = source.read(pos, 8)
            if (header.size < 8) return null
            var boxSize = u32(header, 0)
            val type = ascii(header, 4)
            var contentStart = pos + 8
            when (boxSize) {
                1L -> {
                    val ext = source.read(pos + 8, 8)
                    if (ext.size < 8) return null
                    boxSize = u64(ext, 0)
                    contentStart = pos + 16
                }
                0L -> boxSize = size - pos
            }
            if (boxSize < 8) return null
            if (type == "moov") {
                val len = pos + boxSize - contentStart
                if (len <= 0 || len > MAX_MOOV_BYTES) return null
                return source.read(contentStart, len.toInt())
            }
            pos += boxSize
        }
        return null
    }

    private fun openSource(uri: String): ByteSource =
        when {
            uri.startsWith("gs://") -> {
                val path = uri.removePrefix("gs://")
                val slash = path.indexOf('/')
                require(slash > 0) { "not a gs:// object uri: $uri" }
                val storage =
                    StorageOptions.newBuilder()
                        .setProjectId(config.boot.gcp.projectId)
                        .build()
                        .service
                val blobId = BlobId.of(path.substring(0, slash), path.substring(slash + 1))
                val blob = storage.get(blobId) ?: error("object not found: $uri")
                GcsByteSource(storage, blobId, blob.size)
            }
            uri.startsWith("file://") -> FileByteSource(Path.of(URI(uri)))
            else -> error("unsupported stored-bytes uri scheme: $uri")
        }

    /** Random-access byte reads over a stored object, without pulling the whole thing. */
    private interface ByteSource : Closeable {
        val size: Long

        fun read(offset: Long, len: Int): ByteArray
    }

    private class GcsByteSource(
        private val storage: Storage,
        private val blobId: BlobId,
        override val size: Long,
    ) : ByteSource {
        override fun read(offset: Long, len: Int): ByteArray {
            val reader: ReadChannel = storage.reader(blobId)
            return reader.use {
                it.seek(offset)
                val buf = ByteBuffer.allocate(len)
                while (buf.hasRemaining()) {
                    if (it.read(buf) < 0) break
                }
                buf.flip()
                ByteArray(buf.remaining()).also { out -> buf.get(out) }
            }
        }

        override fun close() {}
    }

    private class FileByteSource(private val path: Path) : ByteSource {
        override val size: Long = Files.size(path)

        override fun read(offset: Long, len: Int): ByteArray =
            FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                ch.position(offset)
                val buf = ByteBuffer.allocate(len)
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) break
                }
                buf.flip()
                ByteArray(buf.remaining()).also { out -> buf.get(out) }
            }

        override fun close() {}
    }

    private companion object {
        /** A sane cap on the `moov` box we'll buffer (headers are KBs–low MBs, never the media). */
        const val MAX_MOOV_BYTES = 32 * 1024 * 1024

        fun u16(b: ByteArray, i: Int): Int =
            ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

        fun u32(b: ByteArray, i: Int): Long =
            ((b[i].toLong() and 0xFF) shl 24) or
                ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or
                (b[i + 3].toLong() and 0xFF)

        fun u64(b: ByteArray, i: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
            return v
        }

        fun ascii(b: ByteArray, i: Int): String = String(b, i, 4, Charsets.US_ASCII)
    }
}

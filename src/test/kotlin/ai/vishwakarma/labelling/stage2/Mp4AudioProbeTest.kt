package ai.vishwakarma.labelling.stage2

import ai.vishwakarma.labelling.config.AppProperties
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [Mp4AudioProbe] over hand-built minimal MP4 box trees (no binary fixture): the box walk that
 * locates `moov` wherever it sits, audio-track selection, and the AudioSampleEntry field parse —
 * the §12.7 real-sample-rate/channels fix, with config as the fallback.
 */
class Mp4AudioProbeTest {

    private val probe = Mp4AudioProbe(AppProperties())

    @Test
    fun `probes sample rate and channels from a faststart mp4`() {
        val uri =
            fileUri(
                box("ftyp", ByteArray(8)),
                box("moov", audioTrak(1, 44100)),
                box("mdat", ByteArray(4))
            )

        assertEquals(AudioParams(44100, 1), probe.probe(uri))
    }

    @Test
    fun `finds the moov atom at the end of the file (non-faststart capture)`() {
        val uri =
            fileUri(
                box("ftyp", ByteArray(8)),
                box("mdat", ByteArray(2048)),
                box("moov", audioTrak(2, 48000))
            )

        assertEquals(AudioParams(48000, 2), probe.probe(uri))
    }

    @Test
    fun `picks the audio track when a video track precedes it`() {
        assertEquals(AudioParams(48000, 2), probe.parseMoov(videoTrak() + audioTrak(2, 48000)))
    }

    @Test
    fun `returns null when probing is disabled`() {
        val uri =
            fileUri(
                box("ftyp", ByteArray(8)),
                box("moov", audioTrak(2, 48000)),
                box("mdat", ByteArray(4))
            )
        val disabled =
            Mp4AudioProbe(AppProperties(stage2 = AppProperties.Stage2(probeAudioParams = false)))

        assertNull(disabled.probe(uri))
    }

    @Test
    fun `returns null for a header without an audio sample entry`() {
        assertNull(probe.parseMoov(videoTrak()))
    }

    @Test
    fun `returns null for garbage bytes`() {
        assertNull(probe.parseMoov(ByteArray(10)))
    }

    // ---- minimal MP4 builders -------------------------------------------------

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val header =
            byteArrayOf(
                (size ushr 24).toByte(),
                (size ushr 16).toByte(),
                (size ushr 8).toByte(),
                size.toByte(),
            ) + type.toByteArray(Charsets.US_ASCII)
        return header + payload
    }

    /** AudioSampleEntry (ISO v0): channelcount @16, samplesize @18, samplerate 16.16 @24. */
    private fun mp4aPayload(channels: Int, rate: Int): ByteArray {
        val p = ByteArray(28)
        p[7] = 1 // data_reference_index
        p[16] = (channels ushr 8).toByte()
        p[17] = channels.toByte()
        p[19] = 16 // samplesize
        p[24] = (rate ushr 8).toByte()
        p[25] = rate.toByte()
        return p
    }

    private fun stsd(entry: ByteArray): ByteArray {
        val head = ByteArray(8) // version+flags (4) + entry_count (4)
        head[7] = 1
        return box("stsd", head + entry)
    }

    private fun audioTrak(channels: Int, rate: Int): ByteArray =
        box(
            "trak",
            box("mdia", box("minf", box("stbl", stsd(box("mp4a", mp4aPayload(channels, rate)))))),
        )

    private fun videoTrak(): ByteArray =
        box("trak", box("mdia", box("minf", box("stbl", stsd(box("avc1", ByteArray(28)))))))

    private fun fileUri(vararg parts: ByteArray): String {
        val tmp = Files.createTempFile("probe", ".mp4")
        Files.write(tmp, parts.reduce { a, b -> a + b })
        tmp.toFile().deleteOnExit()
        return tmp.toUri().toString()
    }
}

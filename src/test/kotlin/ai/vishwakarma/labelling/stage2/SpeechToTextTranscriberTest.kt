package ai.vishwakarma.labelling.stage2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §12.4 endpoint builder: `global` is the bare host, every other location is prefixed. */
class SpeechToTextTranscriberTest {

    @Test
    fun `global uses the bare speech host`() {
        assertEquals("speech.googleapis.com", SpeechToTextTranscriber.sttHost("global"))
    }

    @Test
    fun `multi-region and single-region locations are prefixed`() {
        assertEquals("us-speech.googleapis.com", SpeechToTextTranscriber.sttHost("us"))
        assertEquals("eu-speech.googleapis.com", SpeechToTextTranscriber.sttHost("eu"))
        assertEquals(
            "asia-southeast1-speech.googleapis.com",
            SpeechToTextTranscriber.sttHost("asia-southeast1"),
        )
    }

    @Test
    fun `an empty-file or unsupported-encoding failure cascades to the next decoding`() {
        assertTrue(
            SpeechToTextTranscriber.isRetryableDecodeFailure(
                "Audio data does not appear to be in a supported encoding"
            )
        )
        assertTrue(SpeechToTextTranscriber.isRetryableDecodeFailure("Provided file is empty."))
        assertFalse(SpeechToTextTranscriber.isRetryableDecodeFailure("Permission denied on object"))
    }
}

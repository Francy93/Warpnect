package io.warpnect.platform.audio.capture

import io.warpnect.audio.capture.AudioCaptureError
import org.junit.Assert.assertEquals
import org.junit.Test

class RootAudioStatusErrorTest {
    @Test fun `matching finite success phase accepted`() {
        listOf("PREPARED", "RECORDING", "STOPPED").forEach {
            assertEquals(AudioCaptureError.None, RootAudioStatusError.classify(it, it))
        }
    }

    @Test fun `unknown matching text cannot become success`() {
        assertEquals(AudioCaptureError.Unknown, RootAudioStatusError.classify("OTHER", "OTHER"))
    }

    @Test fun `backend unavailable distinguished from ring failure`() {
        assertEquals(AudioCaptureError.UnsupportedPlatform, result("REMOTE_SUBMIX_UNAVAILABLE"))
        assertEquals(AudioCaptureError.UnsupportedPlatform, result("AUDIO_API_UNAVAILABLE"))
    }

    @Test fun `uninitialized record preserved`() {
        assertEquals(AudioCaptureError.AudioRecordUninitialized, result("AUDIORECORD_UNINITIALIZED"))
    }

    @Test fun `record construction and identity failure preserved`() {
        assertEquals(AudioCaptureError.AudioRecordCreationFailed, result("AUDIORECORD_CONSTRUCTION_FAILED"))
        assertEquals(AudioCaptureError.AudioRecordCreationFailed, result("AUDIO_OP_PACKAGE_UNAVAILABLE"))
    }

    @Test fun `start failure preserved`() {
        assertEquals(AudioCaptureError.AudioRecordStartFailed, result("START_RECORDING_FAILED"))
    }

    @Test fun `unsupported format preserved`() {
        assertEquals(AudioCaptureError.UnsupportedFormat, result("AUDIO_FORMAT_UNAVAILABLE_-2"))
        assertEquals(AudioCaptureError.UnsupportedFormat, result("AUDIO_FORMAT_MISMATCH"))
    }

    @Test fun `descriptor failure is not a backend failure`() {
        assertEquals(AudioCaptureError.NotificationChannelFailed, result("FD_HANDOFF_FAILED"))
        assertEquals(AudioCaptureError.NotificationChannelFailed, result("ACK_NOT_PIPE"))
    }

    @Test fun `invalid region and descriptor set fail closed`() {
        assertEquals(AudioCaptureError.SharedRingCorrupt, result("RING_SETUP_FAILED"))
        assertEquals(AudioCaptureError.SharedRingCorrupt, result("FD_SET_INVALID"))
    }

    @Test fun `read and bounded stop failures preserved`() {
        assertEquals(AudioCaptureError.AudioRecordReadFailed, result("READ_FAILED_IOException"))
        assertEquals(AudioCaptureError.ThreadStopFailed, result("PRODUCER_STOP_TIMEOUT"))
    }

    @Test fun `wrong success phase does not pass`() {
        assertEquals(AudioCaptureError.NotPrepared, RootAudioStatusError.classify("STOPPED", "RECORDING"))
    }

    @Test fun `malformed or oversized state fails closed`() {
        assertEquals(AudioCaptureError.Unknown, result(""))
        assertEquals(AudioCaptureError.Unknown, result("READ_FAILED_" + "a".repeat(121)))
    }

    private fun result(boundary: String) = RootAudioStatusError.classify(boundary, "PREPARED")
}

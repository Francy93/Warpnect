package io.warpnect.audio.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCaptureControllerCoreTest {
    private val request = AudioCaptureRequest(source = AudioCaptureSource.MicrophoneAudio)
    private val format = AudioCaptureFormat(
        source = AudioCaptureSource.MicrophoneAudio,
        sampleRateHz = 48_000,
        channelCount = 1,
        encoding = AudioPcmEncoding.Pcm16,
        bytesPerFrame = 2,
        targetFramesPerChunk = 240,
        targetChunkDurationUs = 5_000,
    )

    @Test
    fun invalidChunkDurationFailsPrepare() {
        val core = AudioCaptureControllerCore()

        val error = core.beginPrepare(request.copy(targetChunkDurationUs = 100))

        assertEquals(AudioCaptureError.InvalidChunkDuration, error)
        assertEquals(AudioCaptureState.Stopped, core.snapshot().state)
        assertEquals(AudioCaptureError.InvalidChunkDuration, core.snapshot().lastError)
    }

    @Test
    fun prepareStartRecordStopLifecycle() {
        val core = AudioCaptureControllerCore()

        assertEquals(AudioCaptureError.None, core.beginPrepare(request))
        core.completePrepare(AudioCaptureError.None, format, actualBufferFrames = 960)
        assertEquals(AudioCaptureState.Prepared, core.snapshot().state)
        assertEquals(AudioCaptureError.None, core.beginStart())
        core.completeStart(AudioCaptureError.None)
        assertEquals(AudioCaptureState.Running, core.snapshot().state)

        core.recordChunk(
            sizeBytes = 480,
            frameCount = 240,
            firstFramePosition = 0,
            captureTimeNs = 1_000_000,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
        )

        assertEquals(1L, core.snapshot().chunksCaptured)
        assertEquals(240L, core.snapshot().framesCaptured)
        assertEquals(480L, core.snapshot().bytesCaptured)
        val stop = core.completeStop(AudioCaptureError.None)
        assertEquals(AudioCaptureState.Stopped, stop.snapshot.state)
    }

    @Test
    fun duplicateStartIsTyped() {
        val core = AudioCaptureControllerCore()
        core.beginPrepare(request)
        core.completePrepare(AudioCaptureError.None, format)
        core.beginStart()
        core.completeStart(AudioCaptureError.None)

        assertEquals(AudioCaptureError.AlreadyRunning, core.beginStart())
    }

    @Test
    fun sinkFailureIsRecorded() {
        val core = AudioCaptureControllerCore()
        core.beginPrepare(request)
        core.completePrepare(AudioCaptureError.None, format)

        core.recordSinkFailure()

        assertEquals(1L, core.snapshot().sinkFailures)
        assertEquals(AudioCaptureError.SinkFailure, core.snapshot().lastError)
    }
}

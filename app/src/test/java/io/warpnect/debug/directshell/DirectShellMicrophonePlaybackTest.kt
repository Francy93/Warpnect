package io.warpnect.debug.directshell

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackResult
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.playback.AudioPresentationTimestampResult
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectShellMicrophonePlaybackTest {
    private class Playback : AudioPlaybackController {
        var starts = 0
        var onSubmit: () -> Unit = {}
        var submitError = AudioPlaybackError.None
        private fun result(error: AudioPlaybackError = AudioPlaybackError.None) =
            AudioPlaybackResult(error, AudioPlaybackSnapshot())
        override fun prepare(config: AudioPlaybackConfig) = result()
        override fun start() = result(if (starts++ == 0) AudioPlaybackError.None else AudioPlaybackError.AlreadyRunning)
        override fun stop() = result()
        override fun snapshot() = AudioPlaybackSnapshot()
        override fun queryPresentationTimestamp(): AudioPresentationTimestampResult = error("unused")
        override fun querySourcePresentationAnchor(): AudioSourcePresentationAnchorResult = error("unused")
        override fun submitPcm(
            b: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            frameCount: Int,
            m: DecodedPcmMetadata,
        ): AudioPlaybackResult {
            onSubmit()
            return result(submitError)
        }
        override fun close() = Unit
    }
    private class Input : RemoteMicrophonePcmDestination {
        var frames = 0
        override fun start() = true
        override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) {
            this.frames += frames
        }
    }
    private fun config(generation: Long) = AudioPlaybackConfig(
        AudioCaptureSource.MicrophoneAudio,
        generation,
        48_000,
        1,
        5_000,
        240,
    )
    private fun pcm(generation: Long) = DecodedPcmMetadata(
        generation,
        0,
        0,
        AudioTimestampQuality.Unavailable,
        false,
        DecodedAudioFrameKind.Normal,
    )

    @Test fun alreadyRunningWarmOutputDoesNotReleaseInjection() {
        val input = Input()
        var releases = 0
        val session = DirectShellMicrophoneInputSession(input, AutoCloseable { releases++ })
        val wrapper = session.wrap(Playback())
        wrapper.prepare(config(1))
        wrapper.start()
        wrapper.start()
        wrapper.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        assertEquals(240, input.frames)
        assertEquals(0, releases)
        wrapper.stop()
        wrapper.close()
        assertEquals(0, releases)
        session.close()
        assertEquals(1, releases)
    }

    @Test fun staleDecodedGenerationCannotFeedCurrentInput() {
        val input = Input()
        val wrapper = DirectShellMicrophonePlayback(Playback()) { input }
        wrapper.prepare(config(2))
        wrapper.start()
        wrapper.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        assertEquals(0, input.frames)
        wrapper.close()
    }

    @Test fun diagnosticRingFullDoesNotDropValidAndroidInputPcmOrHideDiagnosticError() {
        val input = Input()
        val diagnostic = Playback().apply { submitError = AudioPlaybackError.PlaybackRingFull }
        val wrapper = DirectShellMicrophonePlayback(diagnostic) { input }
        wrapper.prepare(config(1))
        wrapper.start()
        val result = wrapper.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        assertEquals(AudioPlaybackError.PlaybackRingFull, result.error)
        assertEquals(240, input.frames)
    }

    @Test fun authenticatedFrameIdentityAndAdmissionPrecedeDiagnosticWork() {
        var position = -1L
        var admitted = -1L
        val input = object : RemoteMicrophonePcmDestination {
            override fun start() = true
            override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) = error("untimed handoff")
            override fun submitTimed(
                buffer: ByteBuffer,
                offset: Int,
                frames: Int,
                firstFramePosition: Long,
                admittedNs: Long,
            ) {
                position = firstFramePosition
                admitted = admittedNs
            }
        }
        val diagnostic = Playback().apply {
            onSubmit = {
                assertEquals(123_456L, position)
                org.junit.Assert.assertTrue(admitted > 0)
            }
        }
        val wrapper = DirectShellMicrophonePlayback(diagnostic) { input }
        wrapper.prepare(config(1))
        wrapper.start()
        wrapper.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1).copy(firstFramePosition = 123_456))
        wrapper.close()
    }

    @Test fun malformedMonoRegionCannotEnterIndependentInputDestination() {
        val input = Input()
        val wrapper = DirectShellMicrophonePlayback(Playback()) { input }
        wrapper.prepare(config(1))
        wrapper.start()
        wrapper.submitPcm(ByteBuffer.allocate(480), 2, 480, 240, pcm(1))
        wrapper.submitPcm(ByteBuffer.allocate(480), 0, 478, 240, pcm(1))
        assertEquals(0, input.frames)
        wrapper.close()
    }

    @Test fun preparedInputWithoutAnyDecodedConfigStillReleasesOnClose() {
        var releases = 0
        val session = DirectShellMicrophoneInputSession(Input(), AutoCloseable { releases++ })
        session.wrap(Playback()).close()
        session.close()
        session.close()
        assertEquals(1, releases)
    }

    @Test fun diagnosticRingFullCannotFeedAReplacementSessionOwner() {
        val oldInput = Input()
        val newInput = Input()
        var current: RemoteMicrophonePcmDestination? = oldInput
        val diagnostic = Playback().apply { submitError = AudioPlaybackError.PlaybackRingFull }
        val wrapper = DirectShellMicrophonePlayback(diagnostic) { current }
        wrapper.prepare(config(1))
        wrapper.start()
        current = newInput

        val result = wrapper.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))

        assertEquals(AudioPlaybackError.PlaybackRingFull, result.error)
        assertEquals(0, oldInput.frames)
        assertEquals(0, newInput.frames)
    }

    @Test fun reprepareRetainsSessionOwnerAndStopRejectsLatePcm() {
        val input = Input()
        var releases = 0
        val session = DirectShellMicrophoneInputSession(input, AutoCloseable { releases++ })
        val wrapper = session.wrap(Playback())
        wrapper.prepare(config(1))
        wrapper.prepare(config(2))
        assertEquals(0, releases)
        wrapper.stop()
        wrapper.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(2))
        assertEquals(0, input.frames)
        assertEquals(0, releases)
        session.close()
        assertEquals(1, releases)
    }

    @Test fun preCloseRejectsPcmWithoutWaitingForFrameworkLeaseTeardown() {
        val input = Input()
        var releases = 0
        val session = DirectShellMicrophoneInputSession(input, AutoCloseable { releases++ })
        val old = session.wrap(Playback())
        old.prepare(config(1))
        old.start()
        session.stopAcceptingPcm()
        old.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        session.wrap(Playback()).apply {
            prepare(config(2))
            start()
            submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(2))
            close()
        }
        assertEquals(0, input.frames)
        assertEquals(0, releases)
        session.close()
        session.close()
        assertEquals(1, releases)
    }

    @Test fun decoderResetReusesOnePolicyAndSessionCloseRejectsLatePcm() {
        val input = Input()
        var releases = 0
        val session = DirectShellMicrophoneInputSession(input, AutoCloseable { releases++ })
        val old = session.wrap(Playback())
        old.prepare(config(1))
        old.start()
        old.stop()
        old.close()
        val fresh = session.wrap(Playback())
        fresh.prepare(config(2))
        fresh.start()
        old.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(1))
        fresh.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(2))
        assertEquals(240, input.frames)
        assertEquals(0, releases)
        session.close()
        fresh.submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(2))
        session.wrap(Playback()).apply {
            prepare(config(3))
            start()
            submitPcm(ByteBuffer.allocate(480), 0, 480, 240, pcm(3))
        }
        assertEquals(240, input.frames)
        assertEquals(1, releases)
    }
}

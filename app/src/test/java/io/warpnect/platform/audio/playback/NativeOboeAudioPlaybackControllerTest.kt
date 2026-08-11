package io.warpnect.platform.audio.playback

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.playback.AudioPlaybackApi
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackPerformanceMode
import io.warpnect.audio.playback.AudioPlaybackSharingMode
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.playback.AudioPlaybackState
import io.warpnect.audio.playback.AudioPresentationTimestampResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOboeAudioPlaybackControllerTest {
    @Test
    fun preparePrimeAndStart() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)

        assertEquals(AudioPlaybackError.None, controller.prepare(config()).error)
        assertEquals(AudioPlaybackState.Prepared, controller.snapshot().state)
        assertEquals(AudioPlaybackError.PlaybackNotPrimed, controller.start().error)

        val pcm = directPcm()
        assertEquals(
            AudioPlaybackError.None,
            controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata()).error,
        )
        assertEquals(AudioPlaybackError.None, controller.start().error)
        assertEquals(AudioPlaybackState.Running, controller.snapshot().state)
        assertEquals(2, backend.startCalls)
    }

    @Test
    fun validationRejectsBadConfigAndInputBeforeNative() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)

        val badConfig = config(sampleRateHz = 44100)
        assertEquals(AudioPlaybackError.UnsupportedSampleRate, controller.prepare(badConfig).error)
        assertEquals(0, backend.createCalls)

        val prepared = NativeOboeAudioPlaybackController(backend)
        assertEquals(AudioPlaybackError.None, prepared.prepare(config()).error)
        val heap = ByteBuffer.allocate(480)
        assertEquals(
            AudioPlaybackError.NonDirectBuffer,
            prepared.submitPcm(heap, 0, heap.capacity(), 240, metadata()).error,
        )
        assertEquals(0, backend.submitCalls)
    }

    @Test
    fun submitRejectsInvalidRangeAndConfigMismatch() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        assertEquals(AudioPlaybackError.None, controller.prepare(config()).error)
        val pcm = directPcm()

        assertEquals(
            AudioPlaybackError.InvalidBufferRange,
            controller.submitPcm(pcm, -1, pcm.capacity(), 240, metadata()).error,
        )
        assertEquals(0, backend.submitCalls)

        val mismatchController = NativeOboeAudioPlaybackController(backend)
        assertEquals(AudioPlaybackError.None, mismatchController.prepare(config()).error)
        assertEquals(
            AudioPlaybackError.ConfigGenerationMismatch,
            mismatchController.submitPcm(pcm, 0, pcm.capacity(), 240, metadata(generation = 2)).error,
        )
        assertEquals(0, backend.submitCalls)
    }

    @Test
    fun ringFullSurfacesWithoutFatalState() {
        val backend = FakePlaybackBackend()
        backend.submitError = AudioPlaybackError.PlaybackRingFull
        val controller = NativeOboeAudioPlaybackController(backend)
        assertEquals(AudioPlaybackError.None, controller.prepare(config()).error)
        val pcm = directPcm()

        assertEquals(
            AudioPlaybackError.PlaybackRingFull,
            controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata()).error,
        )
        assertEquals(AudioPlaybackState.Prepared, controller.snapshot().state)
    }

    @Test
    fun presentationTimestampAndStopRestartClose() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        assertEquals(AudioPlaybackError.None, controller.prepare(config()).error)
        val pcm = directPcm()
        assertEquals(AudioPlaybackError.None, controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata()).error)
        assertEquals(AudioPlaybackError.None, controller.start().error)

        val timestamp = controller.queryPresentationTimestamp()
        assertTrue(timestamp.timestampValid)
        assertEquals(1234L, timestamp.streamFramePosition)
        assertEquals(99_000L, timestamp.presentationTimeNs)

        assertEquals(AudioPlaybackError.None, controller.stop().error)
        assertEquals(AudioPlaybackState.Prepared, controller.snapshot().state)
        assertEquals(AudioPlaybackError.None, controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata()).error)
        assertEquals(AudioPlaybackError.None, controller.start().error)
        controller.close()
        controller.close()
        assertEquals(1, backend.destroyCalls)
    }

    @Test
    fun sinkAdapterForwardsCachedGeneration() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        assertEquals(AudioPlaybackError.None, controller.prepare(config()).error)
        val sink = OboeDecodedPcmAudioSink(controller)
        sink.onOutputFormatChanged(
            io.warpnect.audio.decoder.DecodedAudioFormat(
                codec = io.warpnect.audio.encoder.AudioCodec.Opus,
                source = AudioCaptureSource.MicrophoneAudio,
                configGeneration = 1,
                sampleRateHz = 48000,
                channelCount = 1,
                frameDurationUs = 5000,
                samplesPerFrame = 240,
                bytesPerFrame = 2,
                lookaheadSamples = 120,
            ),
        )
        val pcm = directPcm()
        sink.onPcmFrame(
            buffer = pcm,
            offset = 0,
            sizeBytes = pcm.capacity(),
            frameCount = 240,
            firstFramePosition = 240,
            captureTimeUs = 5000,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            discontinuityBefore = true,
            frameKind = DecodedAudioFrameKind.PacketLossConcealment,
        )

        assertEquals(1, backend.submitCalls)
        assertEquals(1L, backend.lastMetadata?.configGeneration)
        assertEquals(DecodedAudioFrameKind.PacketLossConcealment, backend.lastMetadata?.frameKind)
    }

    private fun config(sampleRateHz: Int = 48000): AudioPlaybackConfig = AudioPlaybackConfig(
        source = AudioCaptureSource.MicrophoneAudio,
        configGeneration = 1,
        sampleRateHz = sampleRateHz,
        channelCount = 1,
        frameDurationUs = 5000,
        framesPerCodecFrame = 240,
    )

    private fun directPcm(): ByteBuffer = ByteBuffer.allocateDirect(240 * 2)

    private fun metadata(generation: Long = 1): DecodedPcmMetadata = DecodedPcmMetadata(
        configGeneration = generation,
        firstFramePosition = 0,
        captureTimeUs = 1000,
        timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
        discontinuityBefore = false,
        frameKind = DecodedAudioFrameKind.Normal,
    )
}

private class FakePlaybackBackend : OboeAudioPlaybackBackend {
    var createCalls = 0
    var submitCalls = 0
    var startCalls = 0
    var destroyCalls = 0
    var submitError = AudioPlaybackError.None
    var lastMetadata: DecodedPcmMetadata? = null
    private var submittedFrames = 0L
    private var running = false

    override fun create(config: AudioPlaybackConfig): OboePlaybackCreateResult {
        createCalls += 1
        return OboePlaybackCreateResult(
            error = AudioPlaybackError.None,
            handle = 7,
            snapshot = snapshot(config, AudioPlaybackState.Prepared),
        )
    }

    override fun submitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackError {
        submitCalls += 1
        lastMetadata = metadata
        if (submitError != AudioPlaybackError.None) return submitError
        submittedFrames += frameCount.toLong()
        return AudioPlaybackError.None
    }

    override fun start(handle: Long): AudioPlaybackError {
        startCalls += 1
        if (submittedFrames == 0L) return AudioPlaybackError.PlaybackNotPrimed
        running = true
        return AudioPlaybackError.None
    }

    override fun stop(handle: Long): AudioPlaybackError {
        running = false
        submittedFrames = 0
        return AudioPlaybackError.None
    }

    override fun queryPresentationTimestamp(handle: Long): AudioPresentationTimestampResult =
        AudioPresentationTimestampResult(
            error = AudioPlaybackError.None,
            timestampValid = true,
            streamFramePosition = 1234,
            presentationTimeNs = 99_000,
            latencyUs = 4200,
        )

    override fun snapshot(handle: Long, state: AudioPlaybackState): AudioPlaybackSnapshot = snapshot(config(), state)

    override fun destroy(handle: Long): AudioPlaybackError {
        destroyCalls += 1
        return AudioPlaybackError.None
    }

    private fun config(): AudioPlaybackConfig = AudioPlaybackConfig(
        source = AudioCaptureSource.MicrophoneAudio,
        configGeneration = 1,
        sampleRateHz = 48000,
        channelCount = 1,
        frameDurationUs = 5000,
        framesPerCodecFrame = 240,
    )

    private fun snapshot(config: AudioPlaybackConfig, state: AudioPlaybackState): AudioPlaybackSnapshot =
        AudioPlaybackSnapshot(
            state = state,
            source = config.source,
            configGeneration = config.configGeneration,
            requestedSampleRateHz = config.sampleRateHz,
            actualSampleRateHz = config.sampleRateHz,
            requestedChannelCount = config.channelCount,
            actualChannelCount = config.channelCount,
            frameDurationUs = config.frameDurationUs,
            framesPerCodecFrame = config.framesPerCodecFrame,
            actualPerformanceMode = AudioPlaybackPerformanceMode.LowLatency,
            actualSharingMode = AudioPlaybackSharingMode.Exclusive,
            audioApi = AudioPlaybackApi.AAudio,
            ringCapacityFrames = config.framesPerCodecFrame * config.ringCapacityCodecFrames,
            ringOccupancyFrames = submittedFrames.toInt(),
            pcmFramesSubmitted = submittedFrames,
            lastError = AudioPlaybackError.None,
        )
}

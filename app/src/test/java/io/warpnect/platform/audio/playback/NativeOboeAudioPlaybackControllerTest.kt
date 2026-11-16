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
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOboeAudioPlaybackControllerTest {
    @Test fun microphoneRingAdmissionIsIndependentOfNumericObservation() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        controller.prepare(config())
        val pcm = directPcm()
        controller.submitPcm(pcm, 0, 480, 240, metadata())
        controller.start()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.beforeSnapshot = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val observer = Thread { controller.sampleMicrophoneDiagnostics() }
        observer.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val admitted = CountDownLatch(1)
            val producer = Thread {
                repeat(10) {
                    assertEquals(
                        AudioPlaybackError.None,
                        controller.admitMicrophonePcm(pcm, 0, 480, 240, metadata()).error,
                    )
                }
                admitted.countDown()
            }
            producer.start()
            assertTrue(admitted.await(1, TimeUnit.SECONDS))
            producer.join(1000)
            assertEquals(11, backend.submitCalls)
            assertEquals(
                AudioPlaybackError.ConfigGenerationMismatch,
                controller.admitMicrophonePcm(pcm, 0, 480, 240, metadata(2)).error,
            )
            assertEquals(11, backend.submitCalls)
        } finally {
            release.countDown()
            observer.join(2000)
            controller.close()
        }
        assertEquals(AudioPlaybackError.NotRunning, controller.admitMicrophonePcm(pcm, 0, 480, 240, metadata()).error)
    }

    @Test fun closeWaitsForInFlightRingCopyAndRejectsLaterPcm() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        val pcm = directPcm()
        controller.prepare(config())
        controller.submitPcm(pcm, 0, 480, 240, metadata())
        controller.start()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        backend.beforeSubmit = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val producer = Thread { controller.admitMicrophonePcm(pcm, 0, 480, 240, metadata()) }
        producer.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread {
                controller.close()
                closed.countDown()
            }.start()
            assertTrue(!closed.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, backend.destroyCalls)
            assertEquals(
                AudioPlaybackError.NotRunning,
                controller.admitMicrophonePcm(pcm, 0, 480, 240, metadata()).error,
            )
        } finally {
            release.countDown()
            producer.join(2000)
        }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(1, backend.destroyCalls)
    }

    @Test
    fun preparePrimeAndStart() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)

        assertEquals(AudioPlaybackError.None, controller.prepare(config()).error)
        assertEquals(AudioPlaybackState.Prepared, controller.snapshot().state)
        assertEquals(AudioPlaybackError.PlaybackNotPrimed, controller.start().error)
        assertEquals(
            AudioPlaybackError.NotRunning,
            controller.admitMicrophonePcm(directPcm(), 0, 480, 240, metadata()).error,
        )

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
        assertEquals(AudioPlaybackError.None, controller.prepare(config(source = AudioCaptureSource.SystemAudio)).error)
        val pcm = directPcm()
        assertEquals(AudioPlaybackError.None, controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata()).error)
        assertEquals(AudioPlaybackError.None, controller.start().error)

        val timestamp = controller.queryPresentationTimestamp()
        assertTrue(timestamp.timestampValid)
        assertEquals(1234L, timestamp.streamFramePosition)
        assertEquals(99_000L, timestamp.presentationTimeNs)

        val anchor = controller.querySourcePresentationAnchor()
        assertTrue(anchor.valid)
        assertEquals(997L, anchor.sourceContentTimeUs)
        assertEquals(64L, anchor.outputFramePosition)

        assertEquals(AudioPlaybackError.None, controller.stop().error)
        assertEquals(AudioPlaybackState.Prepared, controller.snapshot().state)
        assertEquals(AudioPlaybackError.None, controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata()).error)
        assertEquals(AudioPlaybackError.None, controller.start().error)
        controller.close()
        controller.close()
        assertEquals(1, backend.destroyCalls)
    }

    @Test
    fun stopRetainsFinalCallbackEvidenceWithoutAnotherPcmSubmission() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        controller.prepare(config())
        val pcm = directPcm()
        controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata())
        controller.start()
        backend.finalUnderrunTimeNs = 55_000L

        val stopped = controller.stop()

        assertEquals(AudioPlaybackError.None, stopped.error)
        assertEquals(AudioPlaybackState.Prepared, stopped.snapshot.state)
        assertEquals(55_000L, stopped.snapshot.lastUnderrunTimeNs)
        assertEquals(208, stopped.snapshot.lastUnderrunMissingFrames)
        assertEquals(1L, stopped.snapshot.underrunCallbacks)
        assertEquals(1, backend.submitCalls)
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

    @Test
    fun microphoneSubmitAndSnapshotNeverInvokeSdkObservation() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        controller.prepare(config())
        val before = backend.snapshotCalls
        val pcm = directPcm()
        controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata())
        controller.snapshot()
        assertEquals(before, backend.snapshotCalls)
        assertEquals(0, backend.xrunCalls)
        assertEquals(AudioPlaybackError.PresentationTimestampUnavailable, controller.queryPresentationTimestamp().error)
        assertEquals(
            AudioPlaybackError.PresentationTimestampUnavailable,
            controller.querySourcePresentationAnchor().error,
        )
        controller.close()
    }

    @Test
    fun stalledSdkObserverCannotBlockMicrophoneAdmissionOrFreeItsHandle() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        controller.prepare(config())
        val pcm = directPcm()
        controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata())
        controller.start()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val admitted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        backend.sampleXrun = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            7
        }
        val observer = Thread { controller.sampleMicrophoneDiagnostics() }
        observer.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread {
                controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata())
                admitted.countDown()
            }.start()
            assertTrue(admitted.await(1, TimeUnit.SECONDS))
            Thread {
                controller.close()
                closed.countDown()
            }.start()
            assertTrue(!closed.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, backend.destroyCalls)
        } finally {
            release.countDown()
            observer.join(2000)
        }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(1, backend.destroyCalls)
        controller.sampleMicrophoneDiagnostics()
        assertEquals(1, backend.xrunCalls)
    }

    @Test
    fun microphoneObserverPreservesNativeRejectionsUnderrunsAndXruns() {
        val backend = FakePlaybackBackend()
        val controller = NativeOboeAudioPlaybackController(backend)
        controller.prepare(config())
        val pcm = directPcm()
        controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata())
        controller.start()
        backend.finalUnderrunTimeNs = 123L
        backend.sampleXrun = { 3 }
        controller.sampleMicrophoneDiagnostics()
        val snapshot = controller.snapshot()
        assertEquals(1L, snapshot.underrunCallbacks)
        assertEquals(3, snapshot.xRunCount)
        backend.submitError = AudioPlaybackError.PlaybackRingFull
        assertEquals(AudioPlaybackError.PlaybackRingFull, controller.submitPcm(pcm, 0, 480, 240, metadata()).error)
        controller.close()
    }

    private fun config(
        sampleRateHz: Int = 48000,
        source: AudioCaptureSource = AudioCaptureSource.MicrophoneAudio,
    ): AudioPlaybackConfig = AudioPlaybackConfig(
        source = source,
        configGeneration = 1,
        sampleRateHz = sampleRateHz,
        channelCount = 1,
        frameDurationUs = 5000,
        framesPerCodecFrame = 240,
        lookaheadSamples = 120,
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
    var finalUnderrunTimeNs = 0L
    var snapshotCalls = 0
    var xrunCalls = 0
    var sampleXrun: () -> Int = { -1 }
    var beforeSubmit: () -> Unit = {}
    var beforeSnapshot: () -> Unit = {}
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
        beforeSubmit()
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

    override fun querySourcePresentationAnchor(handle: Long): AudioSourcePresentationAnchorResult =
        AudioSourcePresentationAnchorResult(
            error = AudioPlaybackError.None,
            valid = true,
            sourceContentTimeUs = 997,
            sourceCaptureTimeUs = 1000,
            sourceFramePosition = 0,
            outputFramePosition = 64,
            localPresentationTimeNs = 88_000,
            oboeFramePosition = 1234,
            oboePresentationTimeNs = 99_000,
            ageNs = 1_000,
            configGeneration = 1,
            sampleRateHz = 48000,
            lookaheadSamples = 120,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            discontinuityBefore = false,
            frameKind = DecodedAudioFrameKind.Normal,
            latencyUs = 4200,
        )

    override fun snapshot(handle: Long, state: AudioPlaybackState): AudioPlaybackSnapshot {
        beforeSnapshot()
        snapshotCalls++
        return snapshot(config(), state)
    }

    override fun sampleXRunCount(handle: Long): Int {
        xrunCalls++
        return sampleXrun()
    }

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
        lookaheadSamples = 120,
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
            lookaheadSamples = config.lookaheadSamples,
            actualPerformanceMode = AudioPlaybackPerformanceMode.LowLatency,
            actualSharingMode = AudioPlaybackSharingMode.Exclusive,
            audioApi = AudioPlaybackApi.AAudio,
            ringCapacityFrames = config.framesPerCodecFrame * config.ringCapacityCodecFrames,
            ringOccupancyFrames = submittedFrames.toInt(),
            pcmFramesSubmitted = submittedFrames,
            lastUnderrunTimeNs = finalUnderrunTimeNs,
            lastUnderrunMissingFrames = if (finalUnderrunTimeNs == 0L) 0 else 208,
            underrunCallbacks = if (finalUnderrunTimeNs == 0L) 0 else 1,
            lastError = AudioPlaybackError.None,
        )
}

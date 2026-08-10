package io.warpnect.platform.audio.encoder

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioBitrateMode
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.encoder.AudioEncoderSnapshot
import io.warpnect.audio.encoder.AudioEncoderState
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.audio.encoder.EncodedAudioSink
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeOpusAudioEncoderControllerTest {
    @Test
    fun prepareStartAndMultipleFramesStaySynchronous() {
        val backend = FakeOpusBackend()
        val controller = NativeOpusAudioEncoderController(backend)
        val sink = RecordingEncodedSink()
        val request = request()

        assertEquals(AudioEncoderError.None, controller.prepare(request, sink).error)
        assertEquals(AudioEncoderError.None, controller.start().error)
        val pcm = ByteBuffer.allocateDirect(960)

        val error = controller.submitPcm(
            buffer = pcm,
            offset = 0,
            sizeBytes = 960,
            frameCount = 480,
            firstFramePosition = 1000,
            captureTimeNs = 10_000,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
        )

        assertEquals(AudioEncoderError.None, error)
        assertEquals(2, sink.frames.size)
        assertEquals(1000L, sink.frames[0].firstFramePosition)
        assertEquals(1240L, sink.frames[1].firstFramePosition)
        assertEquals(5_010_000L, sink.frames[1].captureTimeNs)
        assertSame(sink.frames[0].buffer, sink.frames[1].buffer)
        assertEquals(2L, controller.snapshot().encodedFrames)
    }

    @Test
    fun needMoreInputDoesNotEmitEncodedFrame() {
        val backend = FakeOpusBackend()
        val controller = NativeOpusAudioEncoderController(backend)
        val sink = RecordingEncodedSink()
        controller.prepare(request(), sink)
        controller.start()

        val error = controller.submitPcm(
            buffer = ByteBuffer.allocateDirect(240),
            offset = 0,
            sizeBytes = 240,
            frameCount = 120,
            firstFramePosition = 0,
            captureTimeNs = 0,
            timestampQuality = AudioTimestampQuality.EstimatedFromReadCompletion,
        )

        assertEquals(AudioEncoderError.None, error)
        assertEquals(0, sink.frames.size)
        assertEquals(120, controller.snapshot().partialFrameSamples)
    }

    @Test
    fun discontinuityIsReportedAndThenRetriedWithoutQueueing() {
        val backend = FakeOpusBackend()
        backend.forceDiscontinuityOnce = true
        val controller = NativeOpusAudioEncoderController(backend)
        val sink = RecordingEncodedSink()
        controller.prepare(request(), sink)
        controller.start()

        val error = controller.submitPcm(
            buffer = ByteBuffer.allocateDirect(480),
            offset = 0,
            sizeBytes = 480,
            frameCount = 240,
            firstFramePosition = 999,
            captureTimeNs = 1,
            timestampQuality = AudioTimestampQuality.Unavailable,
        )

        assertEquals(AudioEncoderError.None, error)
        assertEquals(1, sink.discontinuities)
        assertEquals(1, sink.frames.size)
    }

    @Test
    fun nonDirectPcmIsRejectedBeforeNativeSubmit() {
        val backend = FakeOpusBackend()
        val controller = NativeOpusAudioEncoderController(backend)
        val sink = RecordingEncodedSink()
        controller.prepare(request(), sink)
        controller.start()

        val error = controller.submitPcm(
            buffer = ByteBuffer.allocate(480),
            offset = 0,
            sizeBytes = 480,
            frameCount = 240,
            firstFramePosition = 0,
            captureTimeNs = 0,
            timestampQuality = AudioTimestampQuality.Unavailable,
        )

        assertEquals(AudioEncoderError.NonDirectPcmBuffer, error)
        assertEquals(0, backend.submitCalls)
        assertEquals(1, sink.errors.size)
    }

    @Test
    fun bitrateUpdateIsExplicitAndNoAdaptationRuns() {
        val backend = FakeOpusBackend()
        val controller = NativeOpusAudioEncoderController(backend)
        val sink = RecordingEncodedSink()
        controller.prepare(request(), sink)

        assertEquals(AudioEncoderError.None, controller.updateBitrate(96_000).error)
        assertEquals(96_000, controller.snapshot().bitrateBps)
        assertEquals(1, backend.bitrateUpdates)
    }

    @Test
    fun sinkFailureMarksOutputSinkFailure() {
        val backend = FakeOpusBackend()
        val controller = NativeOpusAudioEncoderController(backend)
        val sink = RecordingEncodedSink(throwOnFrame = true)
        controller.prepare(request(), sink)
        controller.start()

        val error = controller.submitPcm(
            buffer = ByteBuffer.allocateDirect(480),
            offset = 0,
            sizeBytes = 480,
            frameCount = 240,
            firstFramePosition = 0,
            captureTimeNs = 0,
            timestampQuality = AudioTimestampQuality.Unavailable,
        )

        assertEquals(AudioEncoderError.OutputSinkFailure, error)
        assertEquals(AudioEncoderState.Error, controller.snapshot().state)
    }

    @Test
    fun stopDropsTailAndRestartResetsIndex() {
        val backend = FakeOpusBackend()
        val controller = NativeOpusAudioEncoderController(backend)
        val sink = RecordingEncodedSink()
        controller.prepare(request(), sink)
        controller.start()
        controller.submitPcm(
            buffer = ByteBuffer.allocateDirect(240),
            offset = 0,
            sizeBytes = 240,
            frameCount = 120,
            firstFramePosition = 0,
            captureTimeNs = 0,
            timestampQuality = AudioTimestampQuality.Unavailable,
        )

        assertEquals(AudioEncoderError.None, controller.stop().error)
        assertEquals(120L, controller.snapshot().tailFramesDropped)
        assertEquals(AudioEncoderError.None, controller.start().error)
        controller.submitPcm(
            buffer = ByteBuffer.allocateDirect(480),
            offset = 0,
            sizeBytes = 480,
            frameCount = 240,
            firstFramePosition = 500,
            captureTimeNs = 0,
            timestampQuality = AudioTimestampQuality.Unavailable,
        )

        assertEquals(0L, sink.frames.last().encodedFrameIndex)
    }

    private fun request(): AudioEncoderRequest = AudioEncoderRequest(
        source = AudioCaptureSource.MicrophoneAudio,
        channelCount = 1,
        bitrateMode = AudioBitrateMode.ConstantBitrate,
    )
}

private class RecordingEncodedSink(
    private val throwOnFrame: Boolean = false,
) : EncodedAudioSink {
    val frames = mutableListOf<Frame>()
    val errors = mutableListOf<AudioEncoderError>()
    var discontinuities = 0
    var format: EncodedAudioFormat? = null

    override fun onOutputFormatChanged(format: EncodedAudioFormat) {
        this.format = format
    }

    override fun onEncodedFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        encodedFrameIndex: Long,
    ) {
        if (throwOnFrame) error("sink failed")
        frames += Frame(buffer, sizeBytes, firstFramePosition, captureTimeNs, encodedFrameIndex)
    }

    override fun onAudioDiscontinuity(expectedFramePosition: Long, actualFramePosition: Long) {
        discontinuities += 1
    }

    override fun onEncoderError(error: AudioEncoderError) {
        errors += error
    }
}

private data class Frame(
    val buffer: ByteBuffer,
    val sizeBytes: Int,
    val firstFramePosition: Long,
    val captureTimeNs: Long,
    val encodedFrameIndex: Long,
)

private class FakeOpusBackend : OpusAudioEncoderBackend {
    private val output = ByteBuffer.allocateDirect(1275)
    private var snapshot = AudioEncoderSnapshot(
        state = AudioEncoderState.Prepared,
        source = AudioCaptureSource.MicrophoneAudio,
        codec = AudioCodec.Opus,
        sampleRateHz = 48_000,
        channelCount = 1,
        frameDurationUs = 5_000,
        samplesPerFrame = 240,
        bitrateBps = 64_000,
        bitrateMode = AudioBitrateMode.ConstantBitrate,
        complexity = 5,
        lookaheadSamples = 120,
    )
    var submitCalls = 0
    var bitrateUpdates = 0
    var forceDiscontinuityOnce = false
    private var encodedIndex = 0L

    override fun create(request: AudioEncoderRequest): OpusBackendCreateResult = OpusBackendCreateResult(
        error = AudioEncoderError.None,
        handle = 1L,
        outputBuffer = output,
        snapshot = snapshot.copy(
            source = request.source,
            channelCount = request.channelCount,
            bitrateBps = request.bitrateBps,
        ),
    )

    override fun start(handle: Long): AudioEncoderError {
        encodedIndex = 0L
        snapshot = snapshot.copy(
            pcmChunksReceived = 0,
            pcmFramesReceived = 0,
            encodedFrames = 0,
            encodedBytes = 0,
            partialFrameSamples = 0,
            tailFramesDropped = 0,
        )
        return AudioEncoderError.None
    }

    override fun submitPcm(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ): OpusBackendSubmitResult {
        submitCalls += 1
        if (forceDiscontinuityOnce) {
            forceDiscontinuityOnce = false
            snapshot = snapshot.copy(pcmDiscontinuities = snapshot.pcmDiscontinuities + 1)
            return OpusBackendSubmitResult(
                error = AudioEncoderError.PcmDiscontinuity,
                status = OpusBackendSubmitStatus.Discontinuity,
                expectedFramePosition = firstFramePosition - 1,
                actualFramePosition = firstFramePosition,
            )
        }
        val frameBytes = 480
        return if (sizeBytes >= frameBytes) {
            val index = encodedIndex++
            snapshot = snapshot.copy(
                pcmChunksReceived = snapshot.pcmChunksReceived + 1,
                pcmFramesReceived = snapshot.pcmFramesReceived + 240,
                encodedFrames = snapshot.encodedFrames + 1,
                encodedBytes = snapshot.encodedBytes + 8,
                directFastPathFrames = snapshot.directFastPathFrames + 1,
                partialFrameSamples = 0,
                lastEncodedFramePosition = firstFramePosition,
                lastCaptureTimeNs = captureTimeNs,
            )
            OpusBackendSubmitResult(
                error = AudioEncoderError.None,
                status = OpusBackendSubmitStatus.EncodedFrameReady,
                consumedBytes = frameBytes,
                packetSize = 8,
                firstFramePosition = firstFramePosition,
                captureTimeNs = captureTimeNs,
                timestampQuality = timestampQuality,
                encodedFrameIndex = index,
                directFastPath = true,
            )
        } else {
            snapshot = snapshot.copy(
                pcmChunksReceived = snapshot.pcmChunksReceived + 1,
                pcmFramesReceived = snapshot.pcmFramesReceived + (sizeBytes / 2),
                partialFrameSamples = sizeBytes / 2,
            )
            OpusBackendSubmitResult(
                error = AudioEncoderError.None,
                status = OpusBackendSubmitStatus.NeedMoreInput,
                consumedBytes = sizeBytes,
            )
        }
    }

    override fun updateBitrate(handle: Long, bitrateBps: Int): AudioEncoderError {
        bitrateUpdates += 1
        snapshot = snapshot.copy(bitrateBps = bitrateBps)
        return AudioEncoderError.None
    }

    override fun stop(handle: Long): OpusBackendStopResult {
        val tail = snapshot.partialFrameSamples.toLong()
        snapshot = snapshot.copy(tailFramesDropped = tail, partialFrameSamples = 0)
        return OpusBackendStopResult(error = AudioEncoderError.None, tailFramesDropped = tail)
    }

    override fun snapshot(handle: Long, state: AudioEncoderState): AudioEncoderSnapshot = snapshot.copy(state = state)

    override fun destroy(handle: Long): AudioEncoderError = AudioEncoderError.None
}

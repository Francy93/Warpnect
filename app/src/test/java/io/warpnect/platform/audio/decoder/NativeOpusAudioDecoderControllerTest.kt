package io.warpnect.platform.audio.decoder

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.AudioDecoderConfig
import io.warpnect.audio.decoder.AudioDecoderError
import io.warpnect.audio.decoder.AudioDecoderSnapshot
import io.warpnect.audio.decoder.AudioDecoderState
import io.warpnect.audio.decoder.DecodedAudioFormat
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.decoder.DecodedPcmAudioSink
import io.warpnect.audio.decoder.EncodedAudioFrameMetadata
import io.warpnect.audio.decoder.MissingAudioFrameMetadata
import io.warpnect.audio.encoder.AudioCodec
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeOpusAudioDecoderControllerTest {
    @Test
    fun prepareStartAndDecodeStaySynchronous() {
        val backend = FakeOpusDecoderBackend()
        val controller = NativeOpusAudioDecoderController(backend)
        val sink = RecordingDecodedSink()

        assertEquals(AudioDecoderError.None, controller.prepare(config(), sink).error)
        assertEquals(AudioDecoderError.None, controller.start().error)

        val encoded = ByteBuffer.allocateDirect(32)
        val result = controller.decode(
            buffer = encoded,
            offset = 0,
            sizeBytes = 12,
            metadata = metadata(firstFramePosition = 960, captureTimeUs = 12_345),
        )

        assertEquals(AudioDecoderError.None, result.error)
        assertEquals(1, sink.frames.size)
        assertSame(backend.output, sink.frames.single().buffer)
        assertEquals(960L, sink.frames.single().firstFramePosition)
        assertEquals(12_345L, sink.frames.single().captureTimeUs)
        assertEquals(DecodedAudioFrameKind.Normal, sink.frames.single().frameKind)
        assertEquals(1L, controller.snapshot().framesDecoded)
    }

    @Test
    fun configMismatchRequestsReconfigurationWithoutDecoding() {
        val backend = FakeOpusDecoderBackend()
        val controller = NativeOpusAudioDecoderController(backend)
        val sink = RecordingDecodedSink()
        controller.prepare(config(configGeneration = 1), sink)
        controller.start()

        val result = controller.decode(
            buffer = ByteBuffer.allocateDirect(16),
            offset = 0,
            sizeBytes = 8,
            metadata = metadata(configGeneration = 2),
        )

        assertEquals(AudioDecoderError.ReconfigurationRequired, result.error)
        assertEquals(0, backend.decodeCalls)
        assertEquals(0, sink.frames.size)
        assertEquals(AudioDecoderState.Running, controller.snapshot().state)
    }

    @Test
    fun nonDirectInputIsRejectedBeforeNativeDecode() {
        val backend = FakeOpusDecoderBackend()
        val controller = NativeOpusAudioDecoderController(backend)
        val sink = RecordingDecodedSink()
        controller.prepare(config(), sink)
        controller.start()

        val result = controller.decode(
            buffer = ByteBuffer.allocate(16),
            offset = 0,
            sizeBytes = 8,
            metadata = metadata(),
        )

        assertEquals(AudioDecoderError.NonDirectBuffer, result.error)
        assertEquals(0, backend.decodeCalls)
        assertEquals(listOf(AudioDecoderError.NonDirectBuffer), sink.errors)
        assertEquals(AudioDecoderState.Error, controller.snapshot().state)
    }

    @Test
    fun invalidRangeIsRejectedBeforeNativeDecode() {
        val backend = FakeOpusDecoderBackend()
        val controller = NativeOpusAudioDecoderController(backend)
        val sink = RecordingDecodedSink()
        controller.prepare(config(), sink)
        controller.start()

        val result = controller.decode(
            buffer = ByteBuffer.allocateDirect(16),
            offset = 12,
            sizeBytes = 8,
            metadata = metadata(),
        )

        assertEquals(AudioDecoderError.InvalidBufferRange, result.error)
        assertEquals(0, backend.decodeCalls)
    }

    @Test
    fun sinkFailureMarksOutputSinkFailureWithoutQueueingPcm() {
        val backend = FakeOpusDecoderBackend()
        val controller = NativeOpusAudioDecoderController(backend)
        val sink = RecordingDecodedSink(throwOnFrame = true)
        controller.prepare(config(), sink)
        controller.start()

        val result = controller.decode(
            buffer = ByteBuffer.allocateDirect(16),
            offset = 0,
            sizeBytes = 8,
            metadata = metadata(),
        )

        assertEquals(AudioDecoderError.OutputSinkFailure, result.error)
        assertEquals(AudioDecoderState.Error, controller.snapshot().state)
        assertEquals(1L, controller.snapshot().sinkFailures)
    }

    @Test
    fun explicitPlcProducesMarkedPcmFrame() {
        val backend = FakeOpusDecoderBackend()
        val controller = NativeOpusAudioDecoderController(backend)
        val sink = RecordingDecodedSink()
        controller.prepare(config(), sink)
        controller.start()

        val result = controller.concealMissingFrame(
            MissingAudioFrameMetadata(
                configGeneration = 1,
                firstFramePosition = 240,
                captureTimeUs = 5_000,
                timestampQuality = AudioTimestampQuality.Unavailable,
            ),
        )

        assertEquals(AudioDecoderError.None, result.error)
        assertEquals(1, backend.plcCalls)
        assertEquals(DecodedAudioFrameKind.PacketLossConcealment, sink.frames.single().frameKind)
        assertEquals(true, sink.frames.single().discontinuityBefore)
    }

    @Test
    fun plcFailureIsReported() {
        val backend = FakeOpusDecoderBackend(plcError = AudioDecoderError.PacketLossConcealmentFailed)
        val controller = NativeOpusAudioDecoderController(backend)
        val sink = RecordingDecodedSink()
        controller.prepare(config(), sink)
        controller.start()

        val result = controller.concealMissingFrame(
            MissingAudioFrameMetadata(
                configGeneration = 1,
                firstFramePosition = 240,
                captureTimeUs = 5_000,
                timestampQuality = AudioTimestampQuality.Unavailable,
            ),
        )

        assertEquals(AudioDecoderError.PacketLossConcealmentFailed, result.error)
        assertEquals(listOf(AudioDecoderError.PacketLossConcealmentFailed), sink.errors)
    }

    @Test
    fun stopRestartAndCloseAreDeterministic() {
        val backend = FakeOpusDecoderBackend()
        val controller = NativeOpusAudioDecoderController(backend)
        val sink = RecordingDecodedSink()
        controller.prepare(config(), sink)
        controller.start()
        controller.decode(ByteBuffer.allocateDirect(16), 0, 8, metadata())

        assertEquals(AudioDecoderError.None, controller.stop().error)
        assertEquals(AudioDecoderState.Prepared, controller.snapshot().state)
        assertEquals(AudioDecoderError.None, controller.start().error)
        assertEquals(0L, controller.snapshot().framesDecoded)

        controller.close()
        controller.close()
        assertEquals(AudioDecoderState.Closed, controller.snapshot().state)
        assertEquals(1, backend.destroyCalls)
    }

    private fun config(configGeneration: Long = 1): AudioDecoderConfig = AudioDecoderConfig(
        source = AudioCaptureSource.MicrophoneAudio,
        configGeneration = configGeneration,
        sampleRateHz = 48_000,
        channelCount = 1,
        frameDurationUs = 5_000,
        lookaheadSamples = 120,
    )

    private fun metadata(
        configGeneration: Long = 1,
        firstFramePosition: Long = 0,
        captureTimeUs: Long = 0,
    ): EncodedAudioFrameMetadata = EncodedAudioFrameMetadata(
        configGeneration = configGeneration,
        firstFramePosition = firstFramePosition,
        captureTimeUs = captureTimeUs,
        timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
        discontinuityBefore = false,
    )
}

private class RecordingDecodedSink(
    private val throwOnFrame: Boolean = false,
) : DecodedPcmAudioSink {
    val frames = mutableListOf<Frame>()
    val errors = mutableListOf<AudioDecoderError>()
    var format: DecodedAudioFormat? = null

    override fun onOutputFormatChanged(format: DecodedAudioFormat) {
        this.format = format
    }

    override fun onPcmFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
        frameKind: DecodedAudioFrameKind,
    ) {
        if (throwOnFrame) error("sink failed")
        frames += Frame(
            buffer,
            sizeBytes,
            frameCount,
            firstFramePosition,
            captureTimeUs,
            discontinuityBefore,
            frameKind,
        )
    }

    override fun onDecoderError(error: AudioDecoderError) {
        errors += error
    }
}

private data class Frame(
    val buffer: ByteBuffer,
    val sizeBytes: Int,
    val frameCount: Int,
    val firstFramePosition: Long,
    val captureTimeUs: Long,
    val discontinuityBefore: Boolean,
    val frameKind: DecodedAudioFrameKind,
)

private class FakeOpusDecoderBackend(
    private val decodeError: AudioDecoderError = AudioDecoderError.None,
    private val plcError: AudioDecoderError = AudioDecoderError.None,
) : OpusAudioDecoderBackend {
    val output: ByteBuffer = ByteBuffer.allocateDirect(240 * 2)
    var decodeCalls = 0
    var plcCalls = 0
    var destroyCalls = 0
    private var snapshot = AudioDecoderSnapshot(
        state = AudioDecoderState.Prepared,
        source = AudioCaptureSource.MicrophoneAudio,
        codec = AudioCodec.Opus,
        configGeneration = 1,
        sampleRateHz = 48_000,
        channelCount = 1,
        frameDurationUs = 5_000,
        samplesPerFrame = 240,
        lookaheadSamples = 120,
    )

    override fun create(config: AudioDecoderConfig): OpusDecoderBackendCreateResult {
        snapshot = snapshot.copy(
            source = config.source,
            configGeneration = config.configGeneration,
            sampleRateHz = config.sampleRateHz,
            channelCount = config.channelCount,
            frameDurationUs = config.frameDurationUs,
            samplesPerFrame = 240,
            lookaheadSamples = config.lookaheadSamples,
        )
        return OpusDecoderBackendCreateResult(
            error = AudioDecoderError.None,
            handle = 1L,
            outputBuffer = output,
            snapshot = snapshot,
        )
    }

    override fun start(handle: Long): AudioDecoderError {
        snapshot = snapshot.copy(
            packetsSubmitted = 0,
            encodedBytesSubmitted = 0,
            framesDecoded = 0,
            pcmFramesDecoded = 0,
            pcmBytesDecoded = 0,
            plcFramesGenerated = 0,
        )
        return AudioDecoderError.None
    }

    override fun decode(
        handle: Long,
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
    ): OpusDecoderBackendDecodeResult {
        decodeCalls += 1
        if (decodeError != AudioDecoderError.None) {
            return OpusDecoderBackendDecodeResult(error = decodeError)
        }
        snapshot = snapshot.copy(
            packetsSubmitted = snapshot.packetsSubmitted + 1,
            encodedBytesSubmitted = snapshot.encodedBytesSubmitted + sizeBytes,
            framesDecoded = snapshot.framesDecoded + 1,
            pcmFramesDecoded = snapshot.pcmFramesDecoded + 240,
            pcmBytesDecoded = snapshot.pcmBytesDecoded + 480,
            lastFramePosition = firstFramePosition,
            lastCaptureTimeUs = captureTimeUs,
            lastDecodedSamples = 240,
        )
        return OpusDecoderBackendDecodeResult(
            error = AudioDecoderError.None,
            frameKind = DecodedAudioFrameKind.Normal,
            pcmSizeBytes = 480,
            frameCount = 240,
            firstFramePosition = firstFramePosition,
            captureTimeUs = captureTimeUs,
            timestampQuality = timestampQuality,
            discontinuityBefore = discontinuityBefore,
        )
    }

    override fun concealMissingFrame(
        handle: Long,
        configGeneration: Long,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
    ): OpusDecoderBackendDecodeResult {
        plcCalls += 1
        if (plcError != AudioDecoderError.None) {
            return OpusDecoderBackendDecodeResult(error = plcError)
        }
        snapshot = snapshot.copy(
            framesDecoded = snapshot.framesDecoded + 1,
            pcmFramesDecoded = snapshot.pcmFramesDecoded + 240,
            pcmBytesDecoded = snapshot.pcmBytesDecoded + 480,
            plcFramesGenerated = snapshot.plcFramesGenerated + 1,
            lastFramePosition = firstFramePosition,
            lastCaptureTimeUs = captureTimeUs,
            lastDecodedSamples = 240,
        )
        return OpusDecoderBackendDecodeResult(
            error = AudioDecoderError.None,
            frameKind = DecodedAudioFrameKind.PacketLossConcealment,
            pcmSizeBytes = 480,
            frameCount = 240,
            firstFramePosition = firstFramePosition,
            captureTimeUs = captureTimeUs,
            timestampQuality = timestampQuality,
            discontinuityBefore = true,
        )
    }

    override fun stop(handle: Long): AudioDecoderError = AudioDecoderError.None

    override fun snapshot(handle: Long, state: AudioDecoderState): AudioDecoderSnapshot = snapshot.copy(state = state)

    override fun destroy(handle: Long): AudioDecoderError {
        destroyCalls += 1
        return AudioDecoderError.None
    }
}

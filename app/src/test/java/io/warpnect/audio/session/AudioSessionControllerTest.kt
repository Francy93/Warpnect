package io.warpnect.audio.session

import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureResult
import io.warpnect.audio.capture.AudioCaptureSnapshot
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioCaptureState
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.audio.decoder.AudioDecoderConfig
import io.warpnect.audio.decoder.AudioDecoderController
import io.warpnect.audio.decoder.AudioDecoderError
import io.warpnect.audio.decoder.AudioDecoderResult
import io.warpnect.audio.decoder.AudioDecoderSnapshot
import io.warpnect.audio.decoder.AudioDecoderState
import io.warpnect.audio.decoder.DecodedAudioFormat
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.decoder.DecodedPcmAudioSink
import io.warpnect.audio.decoder.EncodedAudioFrameMetadata
import io.warpnect.audio.decoder.MissingAudioFrameMetadata
import io.warpnect.audio.encoder.AudioBitrateMode
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.AudioEncoderCapabilities
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.encoder.AudioEncoderResult
import io.warpnect.audio.encoder.AudioEncoderSnapshot
import io.warpnect.audio.encoder.AudioEncoderState
import io.warpnect.audio.encoder.AudioEncoderSupport
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.audio.encoder.EncodedAudioSink
import io.warpnect.audio.encoder.PcmSubmittingAudioEncoderController
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackResult
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.playback.AudioPlaybackState
import io.warpnect.audio.playback.AudioPresentationTimestampResult
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import io.warpnect.audio.transport.AudioReceiverFrameReady
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import io.warpnect.audio.transport.AudioReceiverRuntimeController
import io.warpnect.audio.transport.AudioReceiverRuntimeEvent
import io.warpnect.audio.transport.AudioReceiverRuntimeEventType
import io.warpnect.audio.transport.AudioReceiverRuntimeListener
import io.warpnect.audio.transport.AudioReceiverRuntimeResult
import io.warpnect.audio.transport.AudioReceiverRuntimeSnapshot
import io.warpnect.audio.transport.AudioReceiverRuntimeState
import io.warpnect.audio.transport.AudioReceiverStreamConfig
import io.warpnect.audio.transport.AudioTransportCloseResult
import io.warpnect.audio.transport.AudioTransportConfig
import io.warpnect.audio.transport.AudioTransportController
import io.warpnect.audio.transport.AudioTransportError
import io.warpnect.audio.transport.AudioTransportOpenResult
import io.warpnect.audio.transport.AudioTransportSnapshot
import io.warpnect.audio.transport.AudioTransportState
import io.warpnect.audio.transport.AudioTransportSubmitResult
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSessionControllerTest {
    @Test
    fun transmitterRollsBackWhenTransportOpenFails() = runBlocking {
        val capture = FakeCapture()
        val encoder = FakeEncoder()
        val transport = FakeTransport(openError = AudioTransportError.UdpOpenFailed)
        val session = DefaultAudioTransmitterSessionController(capture, encoder, transport)

        val result = session.start(transmitterConfig())

        assertEquals(AudioSessionError.TransportFailed, result.error)
        assertEquals(0, encoder.prepareCalls)
        assertEquals(0, capture.prepareCalls)
        assertEquals(AudioSessionState.Error, session.snapshot().state)
    }

    @Test
    fun transmitterDropsWouldBlockWithoutLeavingStreamingState() = runBlocking {
        val capture = FakeCapture()
        val encoder = FakeEncoder()
        val transport = FakeTransport(frameErrors = mutableListOf(AudioTransportError.WouldBlock))
        val session = DefaultAudioTransmitterSessionController(capture, encoder, transport)

        assertEquals(AudioSessionError.None, session.start(transmitterConfig()).error)
        capture.emitPcm(firstFramePosition = 0)

        assertEquals(1, transport.configSubmits)
        assertEquals(1, transport.frameSubmits)
        assertEquals(1L, session.snapshot().wouldBlockDrops)
        assertEquals(AudioSessionState.Streaming, session.snapshot().state)
    }

    @Test
    fun receiverWaitsForConfigThenPrimesPlaybackWithFirstFrame() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val decoders = mutableListOf<FakeDecoder>()
        val playbacks = mutableListOf<FakePlayback>()
        val session = DefaultAudioReceiverSessionController(
            runtime,
            decoderControllerFactory = { FakeDecoder().also(decoders::add) },
            playbackControllerFactory = { FakePlayback().also(playbacks::add) },
        )

        assertEquals(AudioSessionError.None, session.start(receiverConfig()).error)
        runtime.emitFrame(frame(position = 0))
        runtime.emitConfig(streamConfig())
        runtime.emitFrame(frame(position = 0))

        assertEquals(1L, session.snapshot().framesBeforeConfigDropped)
        assertEquals(listOf(0L), decoders.single().decodedPositions)
        assertEquals(1, playbacks.single().submitCalls)
        assertEquals(1, playbacks.single().startCalls)
        assertEquals(AudioSessionState.Streaming, session.snapshot().state)
        assertEquals(240L, session.snapshot().expectedFramePosition)
    }

    @Test
    fun receiverUsesImmediatePlcForSmallGapAndDropsLateFrames() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val decoder = FakeDecoder()
        val playback = FakePlayback()
        val session = DefaultAudioReceiverSessionController(runtime, { decoder }, { playback })

        session.start(receiverConfig())
        runtime.emitConfig(streamConfig())
        runtime.emitFrame(frame(position = 0))
        runtime.emitFrame(frame(position = 480))
        runtime.emitFrame(frame(position = 240))

        assertEquals(listOf(0L, 480L), decoder.decodedPositions)
        assertEquals(listOf(240L), decoder.plcPositions)
        assertEquals(1L, session.snapshot().gapEvents)
        assertEquals(1L, session.snapshot().plcFramesGenerated)
        assertEquals(1L, session.snapshot().lateFramesDropped)
        assertEquals(0L, session.snapshot().duplicateFramesDropped)
    }

    @Test
    fun receiverDropsDuplicateFrameWithoutDecodingTwice() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val decoder = FakeDecoder()
        val playback = FakePlayback()
        val session = DefaultAudioReceiverSessionController(runtime, { decoder }, { playback })

        session.start(receiverConfig())
        runtime.emitConfig(streamConfig())
        runtime.emitFrame(frame(position = 0))
        runtime.emitFrame(frame(position = 0))

        assertEquals(listOf(0L), decoder.decodedPositions)
        assertEquals(1L, session.snapshot().duplicateFramesDropped)
    }

    @Test
    fun receiverLargeGapRecreatesPipelineForFreshFrame() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val decoders = mutableListOf<FakeDecoder>()
        val playbacks = mutableListOf<FakePlayback>()
        val session = DefaultAudioReceiverSessionController(
            runtime,
            decoderControllerFactory = { FakeDecoder().also(decoders::add) },
            playbackControllerFactory = { FakePlayback().also(playbacks::add) },
        )

        session.start(receiverConfig(maxImmediatePlcFrames = 2))
        runtime.emitConfig(streamConfig())
        runtime.emitFrame(frame(position = 0))
        runtime.emitFrame(frame(position = 960))

        assertEquals(2, decoders.size)
        assertEquals(listOf(960L), decoders.last().decodedPositions)
        assertTrue(playbacks.first().closed)
        assertEquals(1L, session.snapshot().largeGapResets)
        assertEquals(1L, session.snapshot().gapEvents)
        assertEquals(1200L, session.snapshot().expectedFramePosition)
    }

    @Test
    fun receiverMisalignedGapTriggersFreshnessReset() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val decoders = mutableListOf<FakeDecoder>()
        val session = DefaultAudioReceiverSessionController(
            runtime,
            decoderControllerFactory = { FakeDecoder().also(decoders::add) },
            playbackControllerFactory = { FakePlayback() },
        )

        session.start(receiverConfig())
        runtime.emitConfig(streamConfig())
        runtime.emitFrame(frame(position = 0))
        runtime.emitFrame(frame(position = 241))

        assertEquals(2, decoders.size)
        assertEquals(listOf(241L), decoders.last().decodedPositions)
        assertEquals(1L, session.snapshot().misalignedGapResets)
    }

    @Test
    fun receiverReconfiguresOnNewGenerationWithoutOldPcmCrossing() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val decoders = mutableListOf<FakeDecoder>()
        val playbacks = mutableListOf<FakePlayback>()
        val session = DefaultAudioReceiverSessionController(
            runtime,
            decoderControllerFactory = { FakeDecoder().also(decoders::add) },
            playbackControllerFactory = { FakePlayback().also(playbacks::add) },
        )

        session.start(receiverConfig())
        runtime.emitConfig(streamConfig(generation = 1, channels = 1))
        runtime.emitFrame(frame(position = 0, generation = 1))
        runtime.emitConfig(streamConfig(generation = 2, channels = 2))
        runtime.emitFrame(frame(position = 0, generation = 2))

        assertEquals(2, decoders.size)
        assertEquals(2L, session.snapshot().activeConfigGeneration)
        assertEquals(2, session.snapshot().channelCount)
        assertTrue(playbacks.first().closed)
        assertEquals(listOf(0L), decoders.last().decodedPositions)
    }

    @Test
    fun receiverTreatsPlaybackRingFullAsDropWithoutQueueGrowth() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val decoder = FakeDecoder()
        val playback = FakePlayback(submitError = AudioPlaybackError.PlaybackRingFull)
        val session = DefaultAudioReceiverSessionController(runtime, { decoder }, { playback })

        session.start(receiverConfig())
        runtime.emitConfig(streamConfig())
        runtime.emitFrame(frame(position = 0))

        assertEquals(1L, session.snapshot().playbackRingFullDrops)
        assertEquals(AudioSessionState.WaitingForFirstFrame, session.snapshot().state)
        assertEquals(0, playback.acceptedSubmitCalls)
    }

    @Test
    fun receiverPlaybackStartGateHoldsPrimedAudioUntilRelease() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val playback = FakePlayback()
        val gate = ManualStartGate()
        val session = DefaultAudioReceiverSessionController(
            receiverRuntimeController = runtime,
            decoderControllerFactory = { FakeDecoder() },
            playbackControllerFactory = { playback },
            playbackStartGate = gate,
            monotonicClockNs = { 1_000_000L },
        )

        session.start(receiverConfig())
        runtime.emitConfig(streamConfig())
        runtime.emitFrame(frame(position = 0))

        assertEquals(AudioSessionState.Primed, session.snapshot().state)
        assertEquals(0, playback.startCalls)

        gate.decision = AudioPlaybackStartGateDecision.Start
        assertEquals(AudioSessionError.None, session.releasePlaybackStartGate().error)

        assertEquals(AudioSessionState.Streaming, session.snapshot().state)
        assertEquals(1, playback.startCalls)
        assertEquals(1, gate.startedCalls)
    }

    @Test
    fun receiverAlwaysReleasesReadySlotAfterDecoderFailure() = runBlocking {
        val runtime = FakeReceiverRuntime()
        val decoder = FakeDecoder(decodeError = AudioDecoderError.MalformedOpusPacket)
        val session = DefaultAudioReceiverSessionController(runtime, { decoder }, { FakePlayback() })

        session.start(receiverConfig())
        runtime.emitConfig(streamConfig())
        runtime.emitFrame(frame(position = 0, slot = 3))

        assertEquals(listOf(3), runtime.releasedSlots)
        assertEquals(AudioSessionState.Error, session.snapshot().state)
    }

    private fun transmitterConfig(): AudioTransmitterSessionConfig = AudioTransmitterSessionConfig(
        captureRequest = AudioCaptureRequest(
            source = AudioCaptureSource.MicrophoneAudio,
            channelCount = 1,
        ),
        encoderRequest = AudioEncoderRequest(
            source = AudioCaptureSource.MicrophoneAudio,
            channelCount = 1,
        ),
        transportConfig = AudioTransportConfig(
            source = AudioCaptureSource.MicrophoneAudio,
            remoteAddress = "127.0.0.1",
            remotePort = 9001,
            maxWireDatagramSize = 1200,
        ),
    )

    private fun receiverConfig(maxImmediatePlcFrames: Int = 2): AudioReceiverSessionConfig = AudioReceiverSessionConfig(
        receiverRuntimeConfig = AudioReceiverRuntimeConfig(
            source = AudioCaptureSource.MicrophoneAudio,
            localPort = 9002,
            remoteAddress = "127.0.0.1",
            remotePort = 9001,
            maxWireDatagramSize = 1200,
        ),
        maxImmediatePlcFrames = maxImmediatePlcFrames,
    )

    private fun streamConfig(generation: Long = 1, channels: Int = 1): AudioReceiverStreamConfig =
        AudioReceiverStreamConfig(
            source = AudioCaptureSource.MicrophoneAudio,
            configGeneration = generation,
            sampleRateHz = 48000,
            channelCount = channels,
            frameDurationUs = 5000,
            lookaheadSamples = 120,
        )

    private fun frame(position: Long, generation: Long = 1, slot: Int = 0): AudioReceiverFrameReady =
        AudioReceiverFrameReady(
            slotIndex = slot,
            encodedOffset = 0,
            encodedSizeBytes = 8,
            configGeneration = generation,
            firstFramePosition = position,
            captureTimeUs = position / 48L,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            discontinuityBefore = false,
        )
}

private class ManualStartGate : AudioPlaybackStartGate {
    var decision = AudioPlaybackStartGateDecision.Hold
    var startedCalls = 0

    override fun evaluate(snapshot: AudioReceiverSessionSnapshot, nowNs: Long): AudioPlaybackStartGateDecision =
        decision

    override fun onPlaybackStarted() {
        startedCalls += 1
    }
}

private class FakeCapture(
    private val startError: AudioCaptureError = AudioCaptureError.None,
) : AudioCaptureController {
    var prepareCalls = 0
    var startCalls = 0
    var stopCalls = 0
    private var sink: PcmAudioSink? = null
    private var source = AudioCaptureSource.MicrophoneAudio
    private var framesCaptured = 0L

    override fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities =
        AudioCaptureCapabilities(source = request.source, available = true)

    override suspend fun prepare(request: AudioCaptureRequest, sink: PcmAudioSink): AudioCaptureResult {
        prepareCalls += 1
        this.sink = sink
        source = request.source
        return AudioCaptureResult(AudioCaptureError.None, snapshot())
    }

    override suspend fun start(): AudioCaptureResult {
        startCalls += 1
        return AudioCaptureResult(startError, snapshot())
    }

    override suspend fun stop(): AudioCaptureResult {
        stopCalls += 1
        return AudioCaptureResult(AudioCaptureError.None, snapshot())
    }

    override fun snapshot(): AudioCaptureSnapshot = AudioCaptureSnapshot(
        state = AudioCaptureState.Running,
        source = source,
        sampleRateHz = 48000,
        channelCount = 1,
        framesCaptured = framesCaptured,
    )

    fun emitPcm(firstFramePosition: Long) {
        val pcm = ByteBuffer.allocateDirect(240 * Short.SIZE_BYTES)
        framesCaptured += 240
        sink?.onPcmChunk(
            buffer = pcm,
            offset = 0,
            sizeBytes = pcm.capacity(),
            frameCount = 240,
            firstFramePosition = firstFramePosition,
            captureTimeNs = firstFramePosition * 1_000L,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
        )
    }

    override fun close() = Unit
}

private class FakeEncoder : PcmSubmittingAudioEncoderController {
    var prepareCalls = 0
    var startCalls = 0
    var stopCalls = 0
    private var sink: EncodedAudioSink? = null
    private val encoded = ByteBuffer.allocateDirect(64)
    private var frames = 0L

    override fun queryCapabilities(request: AudioEncoderRequest): AudioEncoderCapabilities = AudioEncoderCapabilities(
        request = request,
        available = true,
        support = AudioEncoderSupport(
            codecSupported = true,
            sampleRateSupported = true,
            channelCountSupported = true,
            frameDurationSupported = true,
            bitrateSupported = true,
            complexitySupported = true,
        ),
        selectedFormat = format(request),
    )

    override fun prepare(request: AudioEncoderRequest, sink: EncodedAudioSink): AudioEncoderResult {
        prepareCalls += 1
        this.sink = sink
        sink.onOutputFormatChanged(format(request))
        return AudioEncoderResult(AudioEncoderError.None, snapshot(), format(request))
    }

    override fun start(): AudioEncoderResult {
        startCalls += 1
        return AudioEncoderResult(AudioEncoderError.None, snapshot())
    }

    override fun updateBitrate(bitrateBps: Int): AudioEncoderResult =
        AudioEncoderResult(AudioEncoderError.None, snapshot())

    override fun stop(): AudioEncoderResult {
        stopCalls += 1
        return AudioEncoderResult(AudioEncoderError.None, snapshot())
    }

    override fun submitPcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ): AudioEncoderError {
        frames += 1
        sink?.onEncodedFrame(
            buffer = encoded,
            offset = 0,
            sizeBytes = 8,
            firstFramePosition = firstFramePosition,
            captureTimeNs = captureTimeNs,
            timestampQuality = timestampQuality,
            encodedFrameIndex = frames - 1,
        )
        return AudioEncoderError.None
    }

    override fun reportInputError(error: AudioEncoderError): AudioEncoderError = error

    override fun snapshot(): AudioEncoderSnapshot = AudioEncoderSnapshot(
        state = AudioEncoderState.Running,
        source = AudioCaptureSource.MicrophoneAudio,
        codec = AudioCodec.Opus,
        sampleRateHz = 48000,
        channelCount = 1,
        frameDurationUs = 5000,
        samplesPerFrame = 240,
        encodedFrames = frames,
    )

    override fun close() = Unit

    private fun format(request: AudioEncoderRequest): EncodedAudioFormat = EncodedAudioFormat(
        codec = AudioCodec.Opus,
        source = request.source,
        sampleRateHz = request.sampleRateHz,
        channelCount = request.channelCount,
        frameDurationUs = request.frameDurationUs,
        samplesPerFrame = 240,
        bitrateBps = request.bitrateBps,
        bitrateMode = AudioBitrateMode.ConstantBitrate,
        complexity = request.complexity,
        dtxEnabled = false,
        inBandFecEnabled = false,
        lookaheadSamples = 120,
    )
}

private class FakeTransport(
    private val openError: AudioTransportError = AudioTransportError.None,
    private val frameErrors: MutableList<AudioTransportError> = mutableListOf(),
) : AudioTransportController {
    var configSubmits = 0
    var frameSubmits = 0
    var closeCalls = 0

    override fun open(config: AudioTransportConfig): AudioTransportOpenResult =
        AudioTransportOpenResult(openError, snapshot())

    override fun submitStreamConfig(format: EncodedAudioFormat): AudioTransportError {
        configSubmits += 1
        return AudioTransportError.None
    }

    override fun submitAudioFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
    ): AudioTransportError {
        frameSubmits += 1
        return if (frameErrors.isEmpty()) AudioTransportError.None else frameErrors.removeAt(0)
    }

    override fun resendCurrentConfig(): AudioTransportSubmitResult =
        AudioTransportSubmitResult(AudioTransportError.None, snapshot())

    override fun snapshot(): AudioTransportSnapshot = AudioTransportSnapshot(
        state = if (openError == AudioTransportError.None) AudioTransportState.Ready else AudioTransportState.Error,
        source = AudioCaptureSource.MicrophoneAudio,
        configsSubmitted = configSubmits.toLong(),
        framesSubmitted = frameSubmits.toLong(),
        datagramsSent = frameSubmits.toLong(),
        wouldBlockCount = frameErrors.count { it == AudioTransportError.WouldBlock }.toLong(),
        lastError = openError,
    )

    override fun closeResult(): AudioTransportCloseResult {
        closeCalls += 1
        return AudioTransportCloseResult(AudioTransportError.None, snapshot())
    }
}

private class FakeReceiverRuntime : AudioReceiverRuntimeController {
    private var listener: AudioReceiverRuntimeListener? = null
    val releasedSlots = mutableListOf<Int>()
    private val buffers = mutableMapOf<Int, ByteBuffer>()
    private var state = AudioReceiverRuntimeState.Stopped

    override fun open(config: AudioReceiverRuntimeConfig): AudioReceiverRuntimeResult {
        state = AudioReceiverRuntimeState.Stopped
        return AudioReceiverRuntimeResult(AudioTransportError.None, snapshot())
    }

    override fun start(listener: AudioReceiverRuntimeListener): AudioReceiverRuntimeResult {
        this.listener = listener
        state = AudioReceiverRuntimeState.Running
        return AudioReceiverRuntimeResult(AudioTransportError.None, snapshot())
    }

    override fun pumpOnce(timeoutUs: Long): AudioReceiverRuntimeEvent =
        AudioReceiverRuntimeEvent(AudioReceiverRuntimeEventType.Timeout)

    override fun readyBuffer(slotIndex: Int): ByteBuffer? = buffers[slotIndex]

    override fun releaseReadySlot(slotIndex: Int): AudioTransportError {
        releasedSlots += slotIndex
        buffers.remove(slotIndex)
        return AudioTransportError.None
    }

    override fun stop(): AudioReceiverRuntimeResult {
        state = AudioReceiverRuntimeState.Stopped
        return AudioReceiverRuntimeResult(AudioTransportError.None, snapshot())
    }

    override fun snapshot(): AudioReceiverRuntimeSnapshot = AudioReceiverRuntimeSnapshot(
        state = state,
        source = AudioCaptureSource.MicrophoneAudio,
    )

    override fun close() {
        state = AudioReceiverRuntimeState.Closed
    }

    fun emitConfig(config: AudioReceiverStreamConfig) {
        listener?.onStreamConfig(config)
    }

    fun emitFrame(frame: AudioReceiverFrameReady) {
        val encoded = ByteBuffer.allocateDirect(frame.encodedSizeBytes)
        buffers[frame.slotIndex] = encoded
        listener?.onAudioFrameReady(frame)
    }
}

private class FakeDecoder(
    private val decodeError: AudioDecoderError = AudioDecoderError.None,
) : AudioDecoderController {
    val decodedPositions = mutableListOf<Long>()
    val plcPositions = mutableListOf<Long>()
    var closed = false
    private var config: AudioDecoderConfig? = null
    private var sink: DecodedPcmAudioSink? = null
    private var state = AudioDecoderState.Stopped
    private val pcm = ByteBuffer.allocateDirect(960)

    override fun prepare(config: AudioDecoderConfig, sink: DecodedPcmAudioSink): AudioDecoderResult {
        this.config = config
        this.sink = sink
        state = AudioDecoderState.Prepared
        sink.onOutputFormatChanged(format(config))
        return AudioDecoderResult(AudioDecoderError.None, snapshot(), format(config))
    }

    override fun start(): AudioDecoderResult {
        state = AudioDecoderState.Running
        return AudioDecoderResult(AudioDecoderError.None, snapshot(), format(requireNotNull(config)))
    }

    override fun decode(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        metadata: EncodedAudioFrameMetadata,
    ): AudioDecoderResult {
        if (decodeError != AudioDecoderError.None) return AudioDecoderResult(decodeError, snapshot())
        decodedPositions += metadata.firstFramePosition
        emitPcm(
            firstFramePosition = metadata.firstFramePosition,
            captureTimeUs = metadata.captureTimeUs,
            timestampQuality = metadata.timestampQuality,
            discontinuityBefore = metadata.discontinuityBefore,
            frameKind = DecodedAudioFrameKind.Normal,
        )
        return AudioDecoderResult(AudioDecoderError.None, snapshot(), format(requireNotNull(config)))
    }

    override fun concealMissingFrame(metadata: MissingAudioFrameMetadata): AudioDecoderResult {
        plcPositions += metadata.firstFramePosition
        emitPcm(
            firstFramePosition = metadata.firstFramePosition,
            captureTimeUs = metadata.captureTimeUs,
            timestampQuality = metadata.timestampQuality,
            discontinuityBefore = true,
            frameKind = DecodedAudioFrameKind.PacketLossConcealment,
        )
        return AudioDecoderResult(AudioDecoderError.None, snapshot(), format(requireNotNull(config)))
    }

    override fun stop(): AudioDecoderResult {
        state = AudioDecoderState.Prepared
        return AudioDecoderResult(AudioDecoderError.None, snapshot(), format(requireNotNull(config)))
    }

    override fun snapshot(): AudioDecoderSnapshot = AudioDecoderSnapshot(
        state = state,
        source = config?.source,
        codec = AudioCodec.Opus,
        configGeneration = config?.configGeneration ?: 0L,
        sampleRateHz = config?.sampleRateHz ?: 0,
        channelCount = config?.channelCount ?: 0,
        frameDurationUs = config?.frameDurationUs ?: 0,
        samplesPerFrame = 240,
        framesDecoded = decodedPositions.size.toLong(),
        plcFramesGenerated = plcPositions.size.toLong(),
    )

    override fun close() {
        closed = true
        state = AudioDecoderState.Closed
    }

    private fun emitPcm(
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
        frameKind: DecodedAudioFrameKind,
    ) {
        val currentConfig = requireNotNull(config)
        sink?.onPcmFrame(
            buffer = pcm,
            offset = 0,
            sizeBytes = currentConfig.channelCount * 240 * Short.SIZE_BYTES,
            frameCount = 240,
            firstFramePosition = firstFramePosition,
            captureTimeUs = captureTimeUs,
            timestampQuality = timestampQuality,
            discontinuityBefore = discontinuityBefore,
            frameKind = frameKind,
        )
    }

    private fun format(config: AudioDecoderConfig): DecodedAudioFormat = DecodedAudioFormat(
        codec = AudioCodec.Opus,
        source = config.source,
        configGeneration = config.configGeneration,
        sampleRateHz = config.sampleRateHz,
        channelCount = config.channelCount,
        frameDurationUs = config.frameDurationUs,
        samplesPerFrame = 240,
        bytesPerFrame = config.channelCount * Short.SIZE_BYTES,
        lookaheadSamples = config.lookaheadSamples,
    )
}

private class FakePlayback(
    private val submitError: AudioPlaybackError = AudioPlaybackError.None,
) : AudioPlaybackController {
    var prepareCalls = 0
    var submitCalls = 0
    var acceptedSubmitCalls = 0
    var startCalls = 0
    var closed = false
    val submitted = mutableListOf<DecodedPcmMetadata>()
    private var config: AudioPlaybackConfig? = null
    private var state = AudioPlaybackState.Stopped

    override fun prepare(config: AudioPlaybackConfig): AudioPlaybackResult {
        prepareCalls += 1
        this.config = config
        state = AudioPlaybackState.Prepared
        return AudioPlaybackResult(AudioPlaybackError.None, snapshot())
    }

    override fun submitPcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackResult {
        submitCalls += 1
        if (submitError != AudioPlaybackError.None) {
            return AudioPlaybackResult(submitError, snapshot())
        }
        acceptedSubmitCalls += 1
        submitted += metadata
        return AudioPlaybackResult(AudioPlaybackError.None, snapshot())
    }

    override fun start(): AudioPlaybackResult {
        startCalls += 1
        return if (acceptedSubmitCalls == 0) {
            AudioPlaybackResult(AudioPlaybackError.PlaybackNotPrimed, snapshot())
        } else {
            state = AudioPlaybackState.Running
            AudioPlaybackResult(AudioPlaybackError.None, snapshot())
        }
    }

    override fun stop(): AudioPlaybackResult {
        state = AudioPlaybackState.Prepared
        acceptedSubmitCalls = 0
        return AudioPlaybackResult(AudioPlaybackError.None, snapshot())
    }

    override fun snapshot(): AudioPlaybackSnapshot = AudioPlaybackSnapshot(
        state = state,
        source = config?.source,
        configGeneration = config?.configGeneration ?: 0L,
        requestedSampleRateHz = config?.sampleRateHz ?: 0,
        actualSampleRateHz = config?.sampleRateHz ?: 0,
        requestedChannelCount = config?.channelCount ?: 0,
        actualChannelCount = config?.channelCount ?: 0,
        frameDurationUs = config?.frameDurationUs ?: 0,
        framesPerCodecFrame = config?.framesPerCodecFrame ?: 0,
        pcmFramesSubmitted = acceptedSubmitCalls * 240L,
        ringOccupancyFrames = acceptedSubmitCalls * 240,
    )

    override fun queryPresentationTimestamp(): AudioPresentationTimestampResult =
        AudioPresentationTimestampResult(AudioPlaybackError.PresentationTimestampUnavailable)

    override fun querySourcePresentationAnchor(): AudioSourcePresentationAnchorResult =
        AudioSourcePresentationAnchorResult(AudioPlaybackError.PresentationTimestampUnavailable)

    override fun close() {
        closed = true
        state = AudioPlaybackState.Closed
    }
}

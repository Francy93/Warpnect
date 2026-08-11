package io.warpnect.audio.session

import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.AudioDecoderController
import io.warpnect.audio.decoder.AudioDecoderError
import io.warpnect.audio.decoder.DecodedAudioFormat
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.decoder.DecodedPcmAudioSink
import io.warpnect.audio.decoder.EncodedAudioFrameMetadata
import io.warpnect.audio.decoder.MissingAudioFrameMetadata
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.DecodedPcmMetadata
import io.warpnect.audio.transport.AudioReceiverFrameReady
import io.warpnect.audio.transport.AudioReceiverRuntimeController
import io.warpnect.audio.transport.AudioReceiverRuntimeListener
import io.warpnect.audio.transport.AudioReceiverStreamConfig
import io.warpnect.audio.transport.AudioTransportError
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking

interface AudioReceiverSessionController : AutoCloseable {
    suspend fun start(config: AudioReceiverSessionConfig): AudioSessionControlResult

    suspend fun stop(): AudioSessionControlResult

    fun releasePlaybackStartGate(): AudioSessionControlResult

    fun snapshot(): AudioReceiverSessionSnapshot

    override fun close()
}

class DefaultAudioReceiverSessionController(
    private val receiverRuntimeController: AudioReceiverRuntimeController,
    private val decoderControllerFactory: () -> AudioDecoderController,
    private val playbackControllerFactory: () -> AudioPlaybackController,
    private val playbackStartGate: AudioPlaybackStartGate? = null,
    private val monotonicClockNs: () -> Long = System::nanoTime,
) : AudioReceiverSessionController {
    private val lock = Any()

    private var state = AudioSessionState.Idle
    private var lastConfig: AudioReceiverSessionConfig? = null
    private var activeStreamConfig: AudioReceiverStreamConfig? = null
    private var decoderController: AudioDecoderController? = null
    private var playbackController: AudioPlaybackController? = null
    private var playbackSink: SessionDecodedPcmSink? = null
    private var expectedFirstFramePosition: Long? = null
    private var lastError = AudioSessionFailure()

    private var framesBeforeConfigDropped = 0L
    private var framesReceived = 0L
    private var framesDecoded = 0L
    private var lateFramesDropped = 0L
    private var duplicateFramesDropped = 0L
    private var gapEvents = 0L
    private var plcFramesGenerated = 0L
    private var largeGapResets = 0L
    private var misalignedGapResets = 0L
    private var playbackRingFullDrops = 0L
    private var generationMismatchFramesDropped = 0L
    private var lastReceivedFramePosition = 0L
    private var lastCaptureTimeUs = 0L

    override suspend fun start(config: AudioReceiverSessionConfig): AudioSessionControlResult {
        return synchronized(lock) {
            if (state == AudioSessionState.Closed) {
                return@synchronized remember(sessionFailure(AudioSessionError.Closed))
            }
            if (state != AudioSessionState.Idle && state != AudioSessionState.Error) {
                return@synchronized remember(sessionFailure(AudioSessionError.AlreadyRunning))
            }
            if (!config.isValid()) {
                return@synchronized remember(sessionFailure(AudioSessionError.InvalidConfiguration))
            }

            resetRuntimeCountersLocked()
            state = AudioSessionState.Starting
            lastConfig = config

            val open = receiverRuntimeController.open(config.receiverRuntimeConfig)
            if (!open.isSuccess) {
                state = AudioSessionState.Error
                return@synchronized remember(
                    AudioSessionFailure(
                        source = AudioSessionErrorSource.ReceiverRuntime,
                        error = AudioSessionError.ReceiverFailed,
                        transportError = open.error,
                    ),
                )
            }
            val start = receiverRuntimeController.start(RuntimeListener())
            if (!start.isSuccess) {
                receiverRuntimeController.stop()
                state = AudioSessionState.Error
                return@synchronized remember(
                    AudioSessionFailure(
                        source = AudioSessionErrorSource.ReceiverRuntime,
                        error = AudioSessionError.ReceiverFailed,
                        transportError = start.error,
                    ),
                )
            }
            state = AudioSessionState.WaitingForConfig
            lastError = AudioSessionFailure()
            AudioSessionControlResult.Success
        }
    }

    override suspend fun stop(): AudioSessionControlResult = synchronized(lock) {
        if (state == AudioSessionState.Closed) {
            return@synchronized remember(sessionFailure(AudioSessionError.Closed))
        }
        if (state == AudioSessionState.Idle) {
            return@synchronized AudioSessionControlResult.Success
        }
        state = AudioSessionState.Stopping
        receiverRuntimeController.stop()
        releasePipelineLocked()
        activeStreamConfig = null
        expectedFirstFramePosition = null
        state = AudioSessionState.Idle
        lastError = AudioSessionFailure()
        AudioSessionControlResult.Success
    }

    override fun releasePlaybackStartGate(): AudioSessionControlResult = synchronized(lock) {
        if (state == AudioSessionState.Closed) {
            return@synchronized remember(sessionFailure(AudioSessionError.Closed))
        }
        if (state != AudioSessionState.Primed && state != AudioSessionState.Streaming) {
            return@synchronized remember(sessionFailure(AudioSessionError.NotRunning))
        }
        if (state == AudioSessionState.Streaming) {
            return@synchronized AudioSessionControlResult.Success
        }
        startPlaybackAfterPrimeLocked(ignoreGate = true)
        if (state == AudioSessionState.Streaming) {
            AudioSessionControlResult.Success
        } else {
            AudioSessionControlResult(lastError.error, lastError)
        }
    }

    override fun snapshot(): AudioReceiverSessionSnapshot = synchronized(lock) {
        snapshotLocked()
    }

    private fun snapshotLocked(): AudioReceiverSessionSnapshot {
        val streamConfig = activeStreamConfig
        val receiverConfig = lastConfig
        return AudioReceiverSessionSnapshot(
            state = state,
            source = receiverConfig?.receiverRuntimeConfig?.source,
            activeConfigGeneration = streamConfig?.configGeneration ?: 0L,
            sampleRateHz = streamConfig?.sampleRateHz ?: 0,
            channelCount = streamConfig?.channelCount ?: 0,
            frameDurationUs = streamConfig?.frameDurationUs ?: 0,
            samplesPerFrame = streamConfig?.samplesPerFrame() ?: 0,
            playbackRingCapacityCodecFrames = receiverConfig?.playbackRingCapacityCodecFrames ?: 0,
            playbackStartThresholdCodecFrames = receiverConfig?.playbackStartThresholdCodecFrames ?: 0,
            framesBeforeConfigDropped = framesBeforeConfigDropped,
            framesReceived = framesReceived,
            framesDecoded = framesDecoded,
            lateFramesDropped = lateFramesDropped,
            duplicateFramesDropped = duplicateFramesDropped,
            gapEvents = gapEvents,
            plcFramesGenerated = plcFramesGenerated,
            largeGapResets = largeGapResets,
            misalignedGapResets = misalignedGapResets,
            playbackRingFullDrops = playbackRingFullDrops,
            receiver = receiverRuntimeController.snapshot(),
            decoder = decoderController?.snapshot(),
            playback = playbackController?.snapshot(),
            lastReceivedFramePosition = lastReceivedFramePosition,
            expectedFramePosition = expectedFirstFramePosition ?: 0L,
            lastCaptureTimeUs = lastCaptureTimeUs,
            lastError = lastError,
        )
    }

    override fun close() {
        runBlocking {
            stop()
        }
        receiverRuntimeController.close()
        synchronized(lock) {
            releasePipelineLocked()
            state = AudioSessionState.Closed
        }
    }

    private fun onStreamConfig(config: AudioReceiverStreamConfig) = synchronized(lock) {
        if (state == AudioSessionState.Closed || state == AudioSessionState.Idle) return@synchronized
        val active = activeStreamConfig
        if (active != null && active.isSameStreamFormat(config)) {
            return@synchronized
        }
        preparePipelineLocked(config)
    }

    private fun onAudioFrameReady(frame: AudioReceiverFrameReady) = synchronized(lock) {
        if (state == AudioSessionState.Closed || state == AudioSessionState.Idle) {
            receiverRuntimeController.releaseReadySlot(frame.slotIndex)
            return@synchronized
        }
        try {
            handleAudioFrameLocked(frame)
        } finally {
            receiverRuntimeController.releaseReadySlot(frame.slotIndex)
        }
    }

    private fun handleAudioFrameLocked(frame: AudioReceiverFrameReady) {
        val streamConfig = activeStreamConfig
        if (streamConfig == null || decoderController == null || playbackController == null) {
            framesBeforeConfigDropped += 1
            return
        }
        if (frame.configGeneration != streamConfig.configGeneration) {
            generationMismatchFramesDropped += 1
            return
        }

        framesReceived += 1
        lastReceivedFramePosition = frame.firstFramePosition
        lastCaptureTimeUs = frame.captureTimeUs
        val samplesPerFrame = streamConfig.samplesPerFrame().toLong()
        val expected = expectedFirstFramePosition
        when {
            expected == null -> {
                if (decodeCurrentFrameLocked(frame, streamConfig)) {
                    expectedFirstFramePosition = frame.firstFramePosition + samplesPerFrame
                    startPlaybackAfterPrimeLocked()
                }
            }
            frame.firstFramePosition == expected -> {
                if (decodeCurrentFrameLocked(frame, streamConfig)) {
                    expectedFirstFramePosition = expected + samplesPerFrame
                    startPlaybackAfterPrimeLocked()
                }
            }
            frame.firstFramePosition < expected -> {
                if (frame.firstFramePosition + samplesPerFrame == expected) {
                    duplicateFramesDropped += 1
                } else {
                    lateFramesDropped += 1
                }
            }
            else -> handleGapLocked(frame, streamConfig, expected, samplesPerFrame)
        }
    }

    private fun handleGapLocked(
        frame: AudioReceiverFrameReady,
        streamConfig: AudioReceiverStreamConfig,
        expected: Long,
        samplesPerFrame: Long,
    ) {
        val gapSamples = frame.firstFramePosition - expected
        if (gapSamples % samplesPerFrame != 0L) {
            misalignedGapResets += 1
            resetForFreshFrameLocked(frame, streamConfig)
            return
        }
        val missingFrames = gapSamples / samplesPerFrame
        gapEvents += 1
        val maxPlcFrames = lastConfig?.maxImmediatePlcFrames
            ?: AudioReceiverSessionConfig.DEFAULT_MAX_IMMEDIATE_PLC_FRAMES
        if (missingFrames <= maxPlcFrames.toLong()) {
            var nextMissingPosition = expected
            repeat(missingFrames.toInt()) {
                val concealed = concealMissingFrameLocked(
                    streamConfig = streamConfig,
                    firstFramePosition = nextMissingPosition,
                    captureTimeUs = frame.captureTimeUs,
                    timestampQuality = frame.timestampQuality,
                )
                if (!concealed) return
                nextMissingPosition += samplesPerFrame
            }
            if (decodeCurrentFrameLocked(frame, streamConfig)) {
                expectedFirstFramePosition = frame.firstFramePosition + samplesPerFrame
                startPlaybackAfterPrimeLocked()
            }
        } else {
            largeGapResets += 1
            resetForFreshFrameLocked(frame, streamConfig)
        }
    }

    private fun resetForFreshFrameLocked(frame: AudioReceiverFrameReady, streamConfig: AudioReceiverStreamConfig) {
        preparePipelineLocked(streamConfig)
        if (decodeCurrentFrameLocked(frame, streamConfig)) {
            expectedFirstFramePosition =
                frame.firstFramePosition + streamConfig.samplesPerFrame().toLong()
            startPlaybackAfterPrimeLocked()
        }
    }

    private fun preparePipelineLocked(config: AudioReceiverStreamConfig) {
        state = AudioSessionState.PreparingPipeline
        releasePipelineLocked()

        val decoder = decoderControllerFactory()
        val playback = playbackControllerFactory()
        val sink = SessionDecodedPcmSink(playback, config.configGeneration)
        decoderController = decoder
        playbackController = playback
        playbackSink = sink

        val decoderPrepare = decoder.prepare(config.toDecoderConfig(), sink)
        if (decoderPrepare.error != AudioDecoderError.None) {
            failPipelineLocked(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Decoder,
                    error = AudioSessionError.DecoderFailed,
                    decoderError = decoderPrepare.error,
                ),
            )
            return
        }
        val decoderStart = decoder.start()
        if (decoderStart.error != AudioDecoderError.None) {
            failPipelineLocked(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Decoder,
                    error = AudioSessionError.DecoderFailed,
                    decoderError = decoderStart.error,
                ),
            )
            return
        }
        val receiverConfig = lastConfig
            ?: return failPipelineLocked(sessionFailure(AudioSessionError.InvalidConfiguration))
        val playbackPrepare = playback.prepare(
            config.toPlaybackConfig(
                ringCapacityCodecFrames = receiverConfig.playbackRingCapacityCodecFrames,
                startThresholdCodecFrames = receiverConfig.playbackStartThresholdCodecFrames,
                requestedBufferBursts = receiverConfig.playbackRequestedBufferBursts,
            ),
        )
        if (playbackPrepare.error != AudioPlaybackError.None) {
            failPipelineLocked(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Playback,
                    error = AudioSessionError.PlaybackFailed,
                    playbackError = playbackPrepare.error,
                ),
            )
            return
        }

        activeStreamConfig = config
        expectedFirstFramePosition = null
        state = AudioSessionState.WaitingForFirstFrame
        lastError = AudioSessionFailure()
    }

    private fun decodeCurrentFrameLocked(
        frame: AudioReceiverFrameReady,
        streamConfig: AudioReceiverStreamConfig,
    ): Boolean {
        val decoder = decoderController ?: return false
        val buffer = receiverRuntimeController.readyBuffer(frame.slotIndex)
        if (buffer == null) {
            failPipelineLocked(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.ReceiverRuntime,
                    error = AudioSessionError.ReceiverFailed,
                    transportError = AudioTransportError.InvalidHandle,
                ),
            )
            return false
        }
        playbackSink?.clearLastSubmitError()
        val result = decoder.decode(
            buffer = buffer,
            offset = frame.encodedOffset,
            sizeBytes = frame.encodedSizeBytes,
            metadata = EncodedAudioFrameMetadata(
                configGeneration = streamConfig.configGeneration,
                firstFramePosition = frame.firstFramePosition,
                captureTimeUs = frame.captureTimeUs,
                timestampQuality = frame.timestampQuality,
                discontinuityBefore = frame.discontinuityBefore,
            ),
        )
        if (result.error != AudioDecoderError.None) {
            failPipelineLocked(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Decoder,
                    error = AudioSessionError.DecoderFailed,
                    decoderError = result.error,
                ),
            )
            return false
        }
        if (!handlePlaybackSubmitErrorLocked()) return false
        framesDecoded += 1
        return true
    }

    private fun concealMissingFrameLocked(
        streamConfig: AudioReceiverStreamConfig,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
    ): Boolean {
        val decoder = decoderController ?: return false
        playbackSink?.clearLastSubmitError()
        val result = decoder.concealMissingFrame(
            MissingAudioFrameMetadata(
                configGeneration = streamConfig.configGeneration,
                firstFramePosition = firstFramePosition,
                captureTimeUs = captureTimeUs,
                timestampQuality = timestampQuality,
            ),
        )
        if (result.error != AudioDecoderError.None) {
            failPipelineLocked(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Decoder,
                    error = AudioSessionError.DecoderFailed,
                    decoderError = result.error,
                ),
            )
            return false
        }
        if (!handlePlaybackSubmitErrorLocked()) return false
        plcFramesGenerated += 1
        return true
    }

    private fun handlePlaybackSubmitErrorLocked(): Boolean {
        val playbackError = playbackSink?.lastSubmitError ?: AudioPlaybackError.None
        when (playbackError) {
            AudioPlaybackError.None -> return true
            AudioPlaybackError.PlaybackRingFull -> {
                playbackRingFullDrops += 1
                return true
            }
            else -> failPipelineLocked(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Playback,
                    error = AudioSessionError.PlaybackFailed,
                    playbackError = playbackError,
                ),
            )
        }
        return false
    }

    private fun startPlaybackAfterPrimeLocked(ignoreGate: Boolean = false) {
        if (state != AudioSessionState.WaitingForFirstFrame && state != AudioSessionState.Primed) {
            return
        }
        state = AudioSessionState.Primed
        val gate = playbackStartGate
        if (!ignoreGate && gate != null) {
            val decision = gate.evaluate(snapshotLocked(), monotonicClockNs())
            if (decision == AudioPlaybackStartGateDecision.Hold) {
                return
            }
        }
        val result = playbackController?.start() ?: return
        when (result.error) {
            AudioPlaybackError.None,
            AudioPlaybackError.AlreadyRunning,
            -> {
                state = AudioSessionState.Streaming
                gate?.onPlaybackStarted()
            }
            AudioPlaybackError.PlaybackNotPrimed -> {
                state = AudioSessionState.WaitingForFirstFrame
            }
            else -> failPipelineLocked(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Playback,
                    error = AudioSessionError.PlaybackFailed,
                    playbackError = result.error,
                ),
            )
        }
    }

    private fun onRuntimeError(error: AudioTransportError) = synchronized(lock) {
        if (error == AudioTransportError.Timeout || error == AudioTransportError.WouldBlock) return@synchronized
        receiverRuntimeController.stop()
        releasePipelineLocked()
        remember(
            AudioSessionFailure(
                source = AudioSessionErrorSource.ReceiverRuntime,
                error = AudioSessionError.ReceiverFailed,
                transportError = error,
            ),
        )
    }

    private fun failPipelineLocked(failure: AudioSessionFailure) {
        releasePipelineLocked()
        remember(failure)
    }

    private fun releasePipelineLocked() {
        playbackSink = null
        playbackStartGate?.onPlaybackReset()
        playbackController?.runCatching { stop() }
        playbackController?.close()
        playbackController = null
        decoderController?.runCatching { stop() }
        decoderController?.close()
        decoderController = null
    }

    private fun resetRuntimeCountersLocked() {
        activeStreamConfig = null
        expectedFirstFramePosition = null
        framesBeforeConfigDropped = 0L
        framesReceived = 0L
        framesDecoded = 0L
        lateFramesDropped = 0L
        duplicateFramesDropped = 0L
        gapEvents = 0L
        plcFramesGenerated = 0L
        largeGapResets = 0L
        misalignedGapResets = 0L
        playbackRingFullDrops = 0L
        generationMismatchFramesDropped = 0L
        lastReceivedFramePosition = 0L
        lastCaptureTimeUs = 0L
        lastError = AudioSessionFailure()
    }

    private fun remember(failure: AudioSessionFailure): AudioSessionControlResult {
        lastError = failure
        if (failure.error != AudioSessionError.None && state != AudioSessionState.Closed) {
            state = AudioSessionState.Error
        }
        return AudioSessionControlResult(failure.error, failure)
    }

    private fun sessionFailure(error: AudioSessionError): AudioSessionFailure = AudioSessionFailure(
        source = AudioSessionErrorSource.Session,
        error = error,
    )

    private fun AudioReceiverSessionConfig.isValid(): Boolean = playbackRingCapacityCodecFrames > 0 &&
        playbackStartThresholdCodecFrames > 0 &&
        playbackStartThresholdCodecFrames <= playbackRingCapacityCodecFrames &&
        playbackRequestedBufferBursts > 0 &&
        maxImmediatePlcFrames >= 0

    private fun AudioReceiverStreamConfig.isSameStreamFormat(other: AudioReceiverStreamConfig): Boolean =
        source == other.source &&
            codec == other.codec &&
            configGeneration == other.configGeneration &&
            sampleRateHz == other.sampleRateHz &&
            channelCount == other.channelCount &&
            frameDurationUs == other.frameDurationUs &&
            lookaheadSamples == other.lookaheadSamples

    private inner class RuntimeListener : AudioReceiverRuntimeListener {
        override fun onStreamConfig(config: AudioReceiverStreamConfig) {
            this@DefaultAudioReceiverSessionController.onStreamConfig(config)
        }

        override fun onAudioFrameReady(frame: AudioReceiverFrameReady) {
            this@DefaultAudioReceiverSessionController.onAudioFrameReady(frame)
        }

        override fun onRuntimeError(error: AudioTransportError) {
            this@DefaultAudioReceiverSessionController.onRuntimeError(error)
        }
    }
}

private class SessionDecodedPcmSink(
    private val playbackController: AudioPlaybackController,
    private val configGeneration: Long,
) : DecodedPcmAudioSink {
    var lastSubmitError: AudioPlaybackError = AudioPlaybackError.None
        private set

    override fun onOutputFormatChanged(format: DecodedAudioFormat) = Unit

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
        val result = playbackController.submitPcm(
            buffer = buffer,
            offset = offset,
            sizeBytes = sizeBytes,
            frameCount = frameCount,
            metadata = DecodedPcmMetadata(
                configGeneration = configGeneration,
                firstFramePosition = firstFramePosition,
                captureTimeUs = captureTimeUs,
                timestampQuality = timestampQuality,
                discontinuityBefore = discontinuityBefore,
                frameKind = frameKind,
            ),
        )
        lastSubmitError = result.error
    }

    fun clearLastSubmitError() {
        lastSubmitError = AudioPlaybackError.None
    }

    override fun onDecoderError(error: AudioDecoderError) = Unit
}

package io.warpnect.platform.video.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.warpnect.telemetry.VideoDecoderTelemetry
import io.warpnect.video.decoder.AvailableInputSlotTracker
import io.warpnect.video.decoder.DecodedVideoFrame
import io.warpnect.video.decoder.DecodedVideoOutputAction
import io.warpnect.video.decoder.DecodedVideoOutputRelease
import io.warpnect.video.decoder.DecodedVideoOutputReleasePlanner
import io.warpnect.video.decoder.DecodedVideoSink
import io.warpnect.video.decoder.VideoDecoderCapabilities
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderControlResult
import io.warpnect.video.decoder.VideoDecoderController
import io.warpnect.video.decoder.VideoDecoderControllerCore
import io.warpnect.video.decoder.VideoDecoderError
import io.warpnect.video.decoder.VideoDecoderFrameRenderedEvent
import io.warpnect.video.decoder.VideoDecoderInputResult
import io.warpnect.video.decoder.VideoDecoderInputSource
import io.warpnect.video.decoder.VideoDecoderPrepareResult
import io.warpnect.video.decoder.VideoDecoderSnapshot
import io.warpnect.video.decoder.VideoDecoderStartResult
import io.warpnect.video.decoder.VideoDecoderState
import io.warpnect.video.decoder.VideoDecoderStopResult
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidMediaCodecVideoDecoder(
    private val discovery: VideoDecoderDiscovery = AndroidVideoDecoderDiscovery(),
    private val clockUs: () -> Long = VideoDecoderClock::monotonicUs,
    private val drainTimeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS,
    private val telemetry: VideoDecoderTelemetry? = null,
) : VideoDecoderController {
    private val codecThread = HandlerThread(THREAD_NAME).apply { start() }
    private val codecHandler = Handler(codecThread.looper)
    private val core = VideoDecoderControllerCore(clockUs)
    private val inputSlots = AvailableInputSlotTracker()

    @Volatile
    private var latestSnapshot = core.snapshot()

    private var codec: MediaCodec? = null
    private var inputSource: VideoDecoderInputSource? = null
    private var outputSink: DecodedVideoSink? = null
    private var pendingStop: CompletableDeferred<VideoDecoderStopResult>? = null
    private var drainTimeoutTask: Runnable? = null
    private var codecStarted = false
    private var closed = false
    private var eosRequested = false

    override fun queryCapabilities(config: VideoDecoderConfig): VideoDecoderCapabilities = discovery.query(config)

    override suspend fun prepare(
        config: VideoDecoderConfig,
        outputSurface: Surface,
        inputSource: VideoDecoderInputSource,
        outputSink: DecodedVideoSink,
    ): VideoDecoderPrepareResult {
        if (closed) {
            return VideoDecoderPrepareResult(VideoDecoderError.Closed, null, latestSnapshot)
        }
        return runOnCodecThread {
            prepareOnCodecThread(config, outputSurface, inputSource, outputSink)
        }
    }

    override suspend fun start(): VideoDecoderStartResult {
        if (closed) {
            return VideoDecoderStartResult(VideoDecoderError.Closed, latestSnapshot)
        }
        return runOnCodecThread {
            startOnCodecThread()
        }
    }

    override fun notifyInputAvailable() {
        if (closed) {
            return
        }
        codecHandler.post {
            retryHeldInputSlots()
        }
    }

    override suspend fun signalEndOfStream(): VideoDecoderControlResult {
        if (closed) {
            return VideoDecoderControlResult(VideoDecoderError.Closed, latestSnapshot)
        }
        return runOnCodecThread {
            signalEndOfStreamOnCodecThread()
        }
    }

    override suspend fun stop(): VideoDecoderStopResult {
        if (closed) {
            return VideoDecoderStopResult(VideoDecoderError.None, latestSnapshot)
        }
        val existingStop = pendingStop
        if (existingStop != null) {
            return existingStop.await()
        }

        val stopCompletion = CompletableDeferred<VideoDecoderStopResult>()
        codecHandler.post {
            stopOnCodecThread(stopCompletion)
        }
        return stopCompletion.await()
    }

    override fun snapshot(): VideoDecoderSnapshot = latestSnapshot

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        codecHandler.post {
            drainTimeoutTask?.let(codecHandler::removeCallbacks)
            drainTimeoutTask = null
            val cleanupError = releaseCodecResources(stopCodec = true)
            core.completeStop(cleanupError)
            publishSnapshot()
            pendingStop?.complete(VideoDecoderStopResult(cleanupError, latestSnapshot))
            pendingStop = null
            codecThread.quitSafely()
        }
    }

    private fun prepareOnCodecThread(
        config: VideoDecoderConfig,
        outputSurface: Surface,
        source: VideoDecoderInputSource,
        sink: DecodedVideoSink,
    ): VideoDecoderPrepareResult {
        val beginError = core.beginPrepare(config)
        publishSnapshot()
        if (beginError != VideoDecoderError.None) {
            return VideoDecoderPrepareResult(beginError, null, latestSnapshot)
        }
        if (!outputSurface.isValid) {
            return prepareFailure(VideoDecoderError.InvalidTargetSurface, null)
        }

        val capabilities = discovery.query(config)
        if (!capabilities.isSupported) {
            core.completePrepare(capabilities.error, capabilities)
            publishSnapshot()
            return VideoDecoderPrepareResult(capabilities.error, capabilities, latestSnapshot)
        }

        val codecName = capabilities.selectedCodec?.codecName
            ?: return prepareFailure(VideoDecoderError.CodecCreationFailed, capabilities)
        val lowLatencyFeatureSupported = capabilities.selectedCodec.lowLatencyFeatureSupported == true
        val configuredCodec = try {
            MediaCodec.createByCodecName(codecName)
        } catch (_: Exception) {
            return prepareFailure(VideoDecoderError.CodecCreationFailed, capabilities)
        }

        try {
            configuredCodec.setCallback(callback, codecHandler)
            configuredCodec.setOnFrameRenderedListener(frameRenderedListener, codecHandler)
            configuredCodec.configure(
                AndroidVideoDecoderFormatFactory.create(config, lowLatencyFeatureSupported),
                outputSurface,
                null,
                0,
            )
        } catch (_: Exception) {
            runCatching { configuredCodec.release() }
            return prepareFailure(VideoDecoderError.CodecConfigurationFailed, capabilities)
        }

        codec = configuredCodec
        inputSource = source
        outputSink = sink
        eosRequested = false
        inputSlots.clear()
        core.completePrepare(
            error = VideoDecoderError.None,
            capabilities = capabilities,
            lowLatencyRequested = AndroidVideoDecoderFormatFactory
                .plan(config, lowLatencyFeatureSupported)
                .lowLatencyRequested,
        )
        publishSnapshot()
        return VideoDecoderPrepareResult(VideoDecoderError.None, capabilities, latestSnapshot)
    }

    private fun prepareFailure(
        error: VideoDecoderError,
        capabilities: VideoDecoderCapabilities?,
    ): VideoDecoderPrepareResult {
        releaseCodecResources(stopCodec = false)
        core.completePrepare(error, capabilities)
        publishSnapshot()
        return VideoDecoderPrepareResult(error, capabilities, latestSnapshot)
    }

    private fun startOnCodecThread(): VideoDecoderStartResult {
        val beginError = core.beginStart()
        if (beginError != VideoDecoderError.None) {
            publishSnapshot()
            return VideoDecoderStartResult(beginError, latestSnapshot)
        }
        return try {
            codec?.start() ?: return startFailure(VideoDecoderError.NotPrepared)
            codecStarted = true
            core.completeStart(VideoDecoderError.None)
            publishSnapshot()
            VideoDecoderStartResult(VideoDecoderError.None, latestSnapshot)
        } catch (_: Exception) {
            startFailure(VideoDecoderError.CodecStartFailed)
        }
    }

    private fun startFailure(error: VideoDecoderError): VideoDecoderStartResult {
        core.completeStart(error)
        publishSnapshot()
        return VideoDecoderStartResult(error, latestSnapshot)
    }

    private fun signalEndOfStreamOnCodecThread(): VideoDecoderControlResult {
        val drainError = core.beginDrain()
        publishSnapshot()
        if (drainError != VideoDecoderError.None) {
            return VideoDecoderControlResult(drainError, latestSnapshot)
        }
        eosRequested = true
        retryHeldInputSlots(forceEos = true)
        startDrainTimeout()
        return VideoDecoderControlResult(VideoDecoderError.None, latestSnapshot)
    }

    private fun stopOnCodecThread(completion: CompletableDeferred<VideoDecoderStopResult>) {
        if (latestSnapshot.state == VideoDecoderState.Stopped) {
            core.completeStop(VideoDecoderError.None)
            publishSnapshot()
            completion.complete(VideoDecoderStopResult(VideoDecoderError.None, latestSnapshot))
            return
        }
        if (pendingStop != null) {
            pendingStop?.invokeOnCompletion {
                completion.complete(VideoDecoderStopResult(latestSnapshot.lastError, latestSnapshot))
            }
            return
        }
        pendingStop = completion
        val wasRunning = latestSnapshot.state == VideoDecoderState.Running ||
            latestSnapshot.state == VideoDecoderState.Draining
        core.beginStop()
        publishSnapshot()
        if (!wasRunning) {
            finishStop(VideoDecoderError.None)
            return
        }
        eosRequested = true
        retryHeldInputSlots(forceEos = true)
        startDrainTimeout()
    }

    private fun startDrainTimeout() {
        if (drainTimeoutTask != null) {
            return
        }
        val timeout = Runnable {
            finishStop(VideoDecoderError.DrainTimeout)
        }
        drainTimeoutTask = timeout
        codecHandler.postDelayed(timeout, drainTimeoutMs)
    }

    private fun finishStop(error: VideoDecoderError) {
        drainTimeoutTask?.let(codecHandler::removeCallbacks)
        drainTimeoutTask = null
        val cleanupError = releaseCodecResources(stopCodec = true)
        val finalError = error.withCleanup(cleanupError)
        core.completeStop(finalError)
        publishSnapshot()
        pendingStop?.complete(
            VideoDecoderStopResult(
                error = if (finalError == VideoDecoderError.NotRunning) VideoDecoderError.None else finalError,
                snapshot = latestSnapshot,
            ),
        )
        pendingStop = null
    }

    private fun releaseCodecResources(stopCodec: Boolean): VideoDecoderError {
        val ownedCodec = codec
        val shouldStopCodec = stopCodec && codecStarted
        codec = null
        inputSource = null
        outputSink = null
        codecStarted = false
        eosRequested = false
        inputSlots.clear()
        var cleanupError = VideoDecoderError.None
        if (ownedCodec != null) {
            runCatching { ownedCodec.setCallback(null) }
            runCatching { ownedCodec.setOnFrameRenderedListener(null, null) }
            if (shouldStopCodec) {
                runCatching { ownedCodec.stop() }
                    .onFailure { cleanupError = cleanupError.withCleanup(VideoDecoderError.CodecStopFailed) }
            }
            runCatching { ownedCodec.release() }
                .onFailure { cleanupError = cleanupError.withCleanup(VideoDecoderError.CodecReleaseFailed) }
        }
        return cleanupError
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            handleInputBuffer(codec, index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            handleOutputBuffer(codec, index, info)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            handleOutputFormat(format)
        }

        override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
            telemetry?.errors?.increment()
            core.fail(
                error = VideoDecoderError.CodecRuntimeError,
                diagnosticInfo = exception.diagnosticInfo,
                recoverable = exception.isRecoverable,
                transient = exception.isTransient,
            )
            publishSnapshot()
            runCatching { outputSink?.onDecoderError(VideoDecoderError.CodecRuntimeError) }
            finishStop(VideoDecoderError.CodecRuntimeError)
        }
    }

    private val frameRenderedListener = MediaCodec.OnFrameRenderedListener { _, presentationTimeUs, nanoTime ->
        val event = VideoDecoderFrameRenderedEvent(presentationTimeUs, nanoTime)
        core.recordFrameRendered(event)
        telemetry?.renderNotifications?.increment()
        publishSnapshot()
        runCatching { outputSink?.onFrameRendered(event) }
    }

    private fun handleInputBuffer(codec: MediaCodec, index: Int) {
        if (latestSnapshot.state != VideoDecoderState.Running &&
            latestSnapshot.state != VideoDecoderState.Draining
        ) {
            inputSlots.retain(index)
            return
        }
        fillInputSlot(codec, index, forceEos = eosRequested)
    }

    private fun retryHeldInputSlots(forceEos: Boolean = false) {
        val currentCodec = codec ?: return
        val held = inputSlots.drain()
        held.forEach { index ->
            fillInputSlot(currentCodec, index, forceEos = forceEos)
        }
    }

    private fun fillInputSlot(codec: MediaCodec, index: Int, forceEos: Boolean) {
        val buffer = try {
            codec.getInputBuffer(index)
        } catch (_: Exception) {
            null
        }
        if (buffer == null) {
            failAndStop(VideoDecoderError.InputBufferUnavailable)
            return
        }
        buffer.clear()
        val result = if (forceEos) {
            VideoDecoderInputResult.EndOfStream
        } else {
            try {
                inputSource?.fillInput(buffer, buffer.capacity()) ?: VideoDecoderInputResult.NoData
            } catch (_: Throwable) {
                VideoDecoderInputResult.Failure(VideoDecoderError.InvalidInputResult)
            }
        }
        when (result) {
            VideoDecoderInputResult.NoData -> {
                if (!inputSlots.retain(index)) {
                    failAndStop(VideoDecoderError.InputBufferUnavailable)
                    return
                }
                core.recordNoData()
                publishSnapshot()
            }
            is VideoDecoderInputResult.AccessUnit -> queueAccessUnit(codec, index, result, buffer.capacity())
            VideoDecoderInputResult.EndOfStream -> queueEndOfStream(codec, index)
            is VideoDecoderInputResult.Failure -> failAndStop(result.error)
        }
    }

    private fun queueAccessUnit(
        codec: MediaCodec,
        index: Int,
        result: VideoDecoderInputResult.AccessUnit,
        capacity: Int,
    ) {
        val validation = core.validateAccessUnit(result, capacity)
        if (validation != VideoDecoderError.None) {
            failAndStop(validation)
            return
        }
        try {
            codec.queueInputBuffer(index, 0, result.size, result.presentationTimeUs, 0)
            telemetry?.accessUnits?.increment()
            core.recordInputQueued(result.size, result.presentationTimeUs)
            publishSnapshot()
        } catch (_: Exception) {
            failAndStop(VideoDecoderError.InputBufferUnavailable)
        }
    }

    private fun queueEndOfStream(codec: MediaCodec, index: Int) {
        try {
            core.beginDrain()
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            eosRequested = false
            publishSnapshot()
            startDrainTimeout()
        } catch (_: Exception) {
            failAndStop(VideoDecoderError.EndOfStreamFailed)
        }
    }

    private fun handleOutputFormat(format: MediaFormat) {
        telemetry?.outputFormatChanges?.increment()
        val outputFormat = try {
            VideoDecoderOutputFormatExtractor.extract(format)
        } catch (_: Exception) {
            failAndStop(VideoDecoderError.InvalidConfiguration)
            return
        }
        core.recordOutputFormat()
        publishSnapshot()
        try {
            outputSink?.onOutputFormatChanged(outputFormat)
        } catch (_: Throwable) {
            failAndStop(VideoDecoderError.OutputSinkFailure)
        }
    }

    private fun handleOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        if (eos) {
            runCatching { codec.releaseOutputBuffer(index, false) }
                .onFailure {
                    failAndStop(VideoDecoderError.OutputReleaseFailed)
                    return
                }
            finishStop(VideoDecoderError.None)
            return
        }

        val frame = DecodedVideoFrame(
            presentationTimeUs = info.presentationTimeUs,
            flags = info.flags,
            outputFormatGeneration = latestSnapshot.outputFormatChanges,
            sourceFrameId = null,
        )
        val action = try {
            outputSink?.onFrameAvailable(frame) ?: DecodedVideoOutputAction.RenderNow
        } catch (_: Throwable) {
            releaseOutput(codec, index, DecodedVideoOutputAction.Drop, info.presentationTimeUs)
            failAndStop(VideoDecoderError.OutputSinkFailure)
            return
        }
        telemetry?.outputFrames?.increment()
        releaseOutput(codec, index, action, info.presentationTimeUs)
    }

    private fun releaseOutput(
        codec: MediaCodec,
        index: Int,
        action: DecodedVideoOutputAction,
        presentationTimeUs: Long,
    ) {
        try {
            when (val release = DecodedVideoOutputReleasePlanner.plan(action)) {
                is DecodedVideoOutputRelease.BooleanRelease -> codec.releaseOutputBuffer(index, release.render)
                is DecodedVideoOutputRelease.ScheduledRelease ->
                    codec.releaseOutputBuffer(index, release.timestampNs)
            }
            core.recordOutput(action, presentationTimeUs)
            when (action) {
                DecodedVideoOutputAction.Drop -> telemetry?.droppedByPolicy?.increment()
                DecodedVideoOutputAction.RenderNow,
                is DecodedVideoOutputAction.RenderAt,
                -> telemetry?.releasedToSurface?.increment()
            }
            publishSnapshot()
        } catch (_: Exception) {
            failAndStop(VideoDecoderError.OutputReleaseFailed)
        }
    }

    private fun failAndStop(error: VideoDecoderError) {
        telemetry?.errors?.increment()
        core.fail(error)
        publishSnapshot()
        runCatching { outputSink?.onDecoderError(error) }
        finishStop(error)
    }

    private fun publishSnapshot() {
        latestSnapshot = core.snapshot()
    }

    private fun VideoDecoderError.withCleanup(cleanupError: VideoDecoderError): VideoDecoderError =
        if (this == VideoDecoderError.None && cleanupError != VideoDecoderError.None) {
            cleanupError
        } else {
            this
        }

    private suspend fun <T> runOnCodecThread(block: () -> T): T {
        return suspendCancellableCoroutine { continuation ->
            codecHandler.post {
                if (continuation.isActive) {
                    continuation.resume(block())
                }
            }
        }
    }

    private companion object {
        const val THREAD_NAME = "WarpnectVideoDecoder"
        const val DEFAULT_DRAIN_TIMEOUT_MS = 2_000L
    }
}

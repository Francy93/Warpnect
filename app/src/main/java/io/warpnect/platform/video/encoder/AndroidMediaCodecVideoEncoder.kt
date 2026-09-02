package io.warpnect.platform.video.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.warpnect.telemetry.VideoEncoderTelemetry
import io.warpnect.video.encoder.EncodedVideoSink
import io.warpnect.video.encoder.VideoEncoderCapabilities
import io.warpnect.video.encoder.VideoEncoderControlResult
import io.warpnect.video.encoder.VideoEncoderController
import io.warpnect.video.encoder.VideoEncoderControllerCore
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderPrepareResult
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.encoder.VideoEncoderSnapshot
import io.warpnect.video.encoder.VideoEncoderStartResult
import io.warpnect.video.encoder.VideoEncoderState
import io.warpnect.video.encoder.VideoEncoderStopResult
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidMediaCodecVideoEncoder(
    private val discovery: VideoEncoderDiscovery,
    private val clockUs: () -> Long = VideoEncoderClock::monotonicUs,
    private val drainTimeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS,
    private val telemetry: VideoEncoderTelemetry? = null,
    private val frameDebugObserver: VideoEncoderFrameDebugObserver = VideoEncoderFrameDebugObserver.None,
) : VideoEncoderController {
    private val codecThread = HandlerThread(THREAD_NAME).apply { start() }
    private val codecHandler = Handler(codecThread.looper)
    private val core = VideoEncoderControllerCore(clockUs)

    @Volatile
    private var latestSnapshot = core.snapshot()

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var sink: EncodedVideoSink? = null
    private var currentCapabilities: VideoEncoderCapabilities? = null
    private var pendingStop: CompletableDeferred<VideoEncoderStopResult>? = null
    private var drainTimeoutTask: Runnable? = null
    private var codecStarted = false
    private var closed = false
    private var firstAccessUnitObserved = false

    override suspend fun queryCapabilities(request: VideoEncoderRequest): VideoEncoderCapabilities =
        discovery.query(request)

    override suspend fun prepare(request: VideoEncoderRequest, sink: EncodedVideoSink): VideoEncoderPrepareResult {
        if (closed) {
            return VideoEncoderPrepareResult(VideoEncoderError.NotPrepared, null, null, latestSnapshot)
        }
        return runOnCodecThread {
            prepareOnCodecThread(request, sink)
        }
    }

    override suspend fun start(): VideoEncoderStartResult {
        if (closed) {
            return VideoEncoderStartResult(VideoEncoderError.NotPrepared, latestSnapshot)
        }
        return runOnCodecThread {
            startOnCodecThread()
        }
    }

    override suspend fun requestKeyFrame(): VideoEncoderControlResult {
        if (closed) {
            return VideoEncoderControlResult(VideoEncoderError.NotRunning, latestSnapshot)
        }
        return runOnCodecThread {
            requestKeyFrameOnCodecThread()
        }
    }

    override suspend fun updateBitrate(bitrateBps: Int): VideoEncoderControlResult {
        if (closed) {
            return VideoEncoderControlResult(VideoEncoderError.NotRunning, latestSnapshot)
        }
        return runOnCodecThread {
            updateBitrateOnCodecThread(bitrateBps)
        }
    }

    override suspend fun stop(): VideoEncoderStopResult {
        if (closed) {
            return VideoEncoderStopResult(VideoEncoderError.None, latestSnapshot)
        }
        val existingStop = pendingStop
        if (existingStop != null) {
            return existingStop.await()
        }

        val stopCompletion = CompletableDeferred<VideoEncoderStopResult>()
        codecHandler.post {
            stopOnCodecThread(stopCompletion)
        }
        return stopCompletion.await()
    }

    override fun snapshot(): VideoEncoderSnapshot = latestSnapshot

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
            pendingStop?.complete(VideoEncoderStopResult(cleanupError, latestSnapshot))
            pendingStop = null
            codecThread.quitSafely()
        }
    }

    private fun prepareOnCodecThread(
        request: VideoEncoderRequest,
        outputSink: EncodedVideoSink,
    ): VideoEncoderPrepareResult {
        val beginError = core.beginPrepare(request)
        publishSnapshot()
        if (beginError != VideoEncoderError.None) {
            return VideoEncoderPrepareResult(beginError, null, null, latestSnapshot)
        }
        firstAccessUnitObserved = false

        val capabilities = discovery.query(request)
        if (!capabilities.isSupported) {
            core.completePrepare(capabilities.error, capabilities)
            publishSnapshot()
            return VideoEncoderPrepareResult(capabilities.error, null, capabilities, latestSnapshot)
        }

        val codecName = capabilities.selectedCodec?.codecName
            ?: return prepareFailure(VideoEncoderError.CodecCreationFailed, capabilities)

        val configuredCodec = try {
            MediaCodec.createByCodecName(codecName)
        } catch (_: Exception) {
            return prepareFailure(VideoEncoderError.CodecCreationFailed, capabilities)
        }

        try {
            configuredCodec.setCallback(callback, codecHandler)
            configuredCodec.configure(
                AndroidVideoEncoderFormatFactory.create(request),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
        } catch (_: Exception) {
            runCatching { configuredCodec.release() }
            return prepareFailure(VideoEncoderError.CodecConfigurationFailed, capabilities)
        }

        val surface = try {
            configuredCodec.createInputSurface()
        } catch (_: Exception) {
            runCatching { configuredCodec.release() }
            return prepareFailure(VideoEncoderError.InputSurfaceCreationFailed, capabilities)
        }

        codec = configuredCodec
        inputSurface = surface
        sink = outputSink
        currentCapabilities = capabilities
        core.completePrepare(VideoEncoderError.None, capabilities)
        publishSnapshot()
        return VideoEncoderPrepareResult(VideoEncoderError.None, surface, capabilities, latestSnapshot)
    }

    private fun prepareFailure(
        error: VideoEncoderError,
        capabilities: VideoEncoderCapabilities?,
    ): VideoEncoderPrepareResult {
        releaseCodecResources(stopCodec = false)
        core.completePrepare(error, capabilities)
        publishSnapshot()
        return VideoEncoderPrepareResult(error, null, capabilities, latestSnapshot)
    }

    private fun startOnCodecThread(): VideoEncoderStartResult {
        val beginError = core.beginStart()
        if (beginError != VideoEncoderError.None) {
            publishSnapshot()
            return VideoEncoderStartResult(beginError, latestSnapshot)
        }
        return try {
            codec?.start() ?: return startFailure(VideoEncoderError.NotPrepared)
            codecStarted = true
            core.completeStart(VideoEncoderError.None)
            publishSnapshot()
            VideoEncoderStartResult(VideoEncoderError.None, latestSnapshot)
        } catch (_: Exception) {
            startFailure(VideoEncoderError.CodecStartFailed)
        }
    }

    private fun startFailure(error: VideoEncoderError): VideoEncoderStartResult {
        core.completeStart(error)
        publishSnapshot()
        return VideoEncoderStartResult(error, latestSnapshot)
    }

    private fun requestKeyFrameOnCodecThread(): VideoEncoderControlResult {
        val stateError = core.canRequestKeyFrame()
        if (stateError != VideoEncoderError.None) {
            publishSnapshot()
            return VideoEncoderControlResult(stateError, latestSnapshot)
        }
        return try {
            val parameters = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(parameters) ?: return controlFailure(VideoEncoderError.NotRunning)
            publishSnapshot()
            VideoEncoderControlResult(VideoEncoderError.None, latestSnapshot)
        } catch (_: Exception) {
            controlFailure(VideoEncoderError.KeyFrameRequestFailed)
        }
    }

    private fun updateBitrateOnCodecThread(bitrateBps: Int): VideoEncoderControlResult {
        val support = currentCapabilities?.support
        val validation = core.canUpdateBitrate(
            bitrateBps = bitrateBps,
            minBitrateBps = support?.minBitrateBps,
            maxBitrateBps = support?.maxBitrateBps,
        )
        if (validation != VideoEncoderError.None) {
            publishSnapshot()
            return VideoEncoderControlResult(validation, latestSnapshot)
        }
        return try {
            val parameters = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps)
            }
            codec?.setParameters(parameters) ?: return controlFailure(VideoEncoderError.NotRunning)
            core.recordBitrate(bitrateBps)
            publishSnapshot()
            VideoEncoderControlResult(VideoEncoderError.None, latestSnapshot)
        } catch (_: Exception) {
            controlFailure(VideoEncoderError.BitrateUpdateFailed)
        }
    }

    private fun controlFailure(error: VideoEncoderError): VideoEncoderControlResult {
        core.fail(error)
        publishSnapshot()
        return VideoEncoderControlResult(error, latestSnapshot)
    }

    private fun stopOnCodecThread(completion: CompletableDeferred<VideoEncoderStopResult>) {
        if (latestSnapshot.state == VideoEncoderState.Stopped) {
            core.completeStop(VideoEncoderError.None)
            publishSnapshot()
            completion.complete(VideoEncoderStopResult(VideoEncoderError.None, latestSnapshot))
            return
        }
        if (pendingStop != null) {
            pendingStop?.invokeOnCompletion {
                completion.complete(VideoEncoderStopResult(latestSnapshot.lastError, latestSnapshot))
            }
            return
        }
        pendingStop = completion
        val wasRunning = latestSnapshot.state == VideoEncoderState.Running
        core.beginStop()
        publishSnapshot()
        if (!wasRunning) {
            finishStop(VideoEncoderError.None)
            return
        }
        try {
            codec?.signalEndOfInputStream() ?: run {
                finishStop(VideoEncoderError.NotRunning)
                return
            }
        } catch (_: Exception) {
            finishStop(VideoEncoderError.EndOfStreamFailed)
            return
        }
        val timeout = Runnable {
            finishStop(VideoEncoderError.DrainTimeout)
        }
        drainTimeoutTask = timeout
        codecHandler.postDelayed(timeout, drainTimeoutMs)
    }

    private fun finishStop(error: VideoEncoderError) {
        drainTimeoutTask?.let(codecHandler::removeCallbacks)
        drainTimeoutTask = null
        val cleanupError = releaseCodecResources(stopCodec = true)
        val finalError = error.withCleanup(cleanupError)
        core.completeStop(finalError)
        publishSnapshot()
        pendingStop?.complete(
            VideoEncoderStopResult(
                error = if (finalError == VideoEncoderError.NotRunning) VideoEncoderError.None else finalError,
                snapshot = latestSnapshot,
            ),
        )
        pendingStop = null
    }

    private fun releaseCodecResources(stopCodec: Boolean): VideoEncoderError {
        val ownedCodec = codec
        val ownedSurface = inputSurface
        val shouldStopCodec = stopCodec && codecStarted
        codec = null
        inputSurface = null
        sink = null
        currentCapabilities = null
        codecStarted = false
        var cleanupError = VideoEncoderError.None
        if (ownedCodec != null) {
            runCatching { ownedCodec.setCallback(null) }
            if (shouldStopCodec) {
                runCatching { ownedCodec.stop() }
                    .onFailure { cleanupError = cleanupError.withCleanup(VideoEncoderError.CodecStopFailed) }
            }
            runCatching { ownedCodec.release() }
                .onFailure { cleanupError = cleanupError.withCleanup(VideoEncoderError.CodecReleaseFailed) }
        }
        runCatching { ownedSurface?.release() }
            .onFailure { cleanupError = cleanupError.withCleanup(VideoEncoderError.CodecReleaseFailed) }
        return cleanupError
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            handleOutputBuffer(codec, index, info)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            handleOutputFormat(format)
        }

        override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
            telemetry?.errors?.increment()
            core.fail(
                error = VideoEncoderError.CodecRuntimeError,
                diagnosticInfo = exception.diagnosticInfo,
                recoverable = exception.isRecoverable,
                transient = exception.isTransient,
            )
            publishSnapshot()
            runCatching { sink?.onEncoderError(VideoEncoderError.CodecRuntimeError) }
            finishStop(VideoEncoderError.CodecRuntimeError)
        }
    }

    private fun handleOutputFormat(format: MediaFormat) {
        telemetry?.outputFormatChanges?.increment()
        val outputFormat = try {
            VideoEncoderOutputFormatExtractor.extract(format)
        } catch (_: Exception) {
            core.fail(VideoEncoderError.OutputFormatInvalid)
            publishSnapshot()
            sink?.onEncoderError(VideoEncoderError.OutputFormatInvalid)
            return
        }
        val formatError = core.recordOutputFormat(outputFormat)
        publishSnapshot()
        try {
            sink?.onOutputFormatChanged(outputFormat)
        } catch (_: Throwable) {
            core.fail(VideoEncoderError.SinkFailure)
            publishSnapshot()
            finishStop(VideoEncoderError.SinkFailure)
            return
        }
        if (formatError != VideoEncoderError.None) {
            runCatching { sink?.onEncoderError(formatError) }
        }
    }

    private fun handleOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        var release = true
        try {
            val outputBuffer = codec.getOutputBuffer(index)
            if (outputBuffer == null) {
                core.fail(VideoEncoderError.OutputBufferUnavailable)
                publishSnapshot()
                sink?.onEncoderError(VideoEncoderError.OutputBufferUnavailable)
                return
            }
            if (info.size > 0) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    core.recordCodecConfig(info.size)
                } else {
                    val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    sink?.onAccessUnit(
                        buffer = outputBuffer,
                        offset = info.offset,
                        size = info.size,
                        presentationTimeUs = info.presentationTimeUs,
                        flags = info.flags,
                    )
                    telemetry?.accessUnits?.increment()
                    telemetry?.bytes?.add(info.size.toULong())
                    telemetry?.accessUnitSize?.record(info.size.toULong())
                    if (keyFrame) telemetry?.keyframes?.increment()
                    core.recordAccessUnit(info.size, info.presentationTimeUs, keyFrame)
                    if (!firstAccessUnitObserved) {
                        firstAccessUnitObserved = true
                        runCatching { frameDebugObserver.onFirstAccessUnitEncoded() }
                    }
                }
                publishSnapshot()
            }
        } catch (_: Throwable) {
            core.fail(VideoEncoderError.SinkFailure)
            publishSnapshot()
            runCatching { sink?.onEncoderError(VideoEncoderError.SinkFailure) }
            release = true
            finishAfterRelease(codec, index, release, VideoEncoderError.SinkFailure)
            return
        }

        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        finishAfterRelease(
            codec = codec,
            index = index,
            shouldRelease = release,
            stopError = if (eos) VideoEncoderError.None else null,
        )
    }

    private fun finishAfterRelease(
        codec: MediaCodec,
        index: Int,
        shouldRelease: Boolean,
        stopError: VideoEncoderError?,
    ) {
        if (shouldRelease) {
            runCatching { codec.releaseOutputBuffer(index, false) }
        }
        if (stopError != null) {
            finishStop(stopError)
        }
    }

    private fun publishSnapshot() {
        latestSnapshot = core.snapshot()
    }

    private fun VideoEncoderError.withCleanup(cleanupError: VideoEncoderError): VideoEncoderError =
        if (this == VideoEncoderError.None && cleanupError != VideoEncoderError.None) {
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
        const val THREAD_NAME = "WarpnectVideoEncoder"
        const val DEFAULT_DRAIN_TIMEOUT_MS = 2_000L
    }
}

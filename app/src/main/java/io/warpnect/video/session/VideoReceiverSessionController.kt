package io.warpnect.video.session

import io.warpnect.video.decoder.VideoDecoderController
import io.warpnect.video.render.VideoRenderController
import io.warpnect.video.render.VideoRenderTarget
import io.warpnect.video.render.VideoRenderTargetListener
import io.warpnect.video.transport.VideoReceiverAccessUnitReady
import io.warpnect.video.transport.VideoReceiverRuntimeController
import io.warpnect.video.transport.VideoReceiverRuntimeListener
import io.warpnect.video.transport.VideoReceiverStreamConfig
import io.warpnect.video.transport.VideoResyncReason
import io.warpnect.video.transport.VideoTransportError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

interface VideoReceiverSessionController : VideoRenderTargetListener, AutoCloseable {
    suspend fun start(config: VideoReceiverSessionConfig): VideoSessionControlResult

    suspend fun stop(): VideoSessionControlResult

    fun snapshot(): VideoReceiverSessionSnapshot

    override fun close()
}

class DefaultVideoReceiverSessionController(
    private val receiverRuntimeController: VideoReceiverRuntimeController,
    private val decoderController: VideoDecoderController,
    private val renderController: VideoRenderController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : VideoReceiverSessionController {
    private val lock = Any()
    private val core = VideoReceiverSessionCore()
    private var currentConfig: VideoReceiverSessionConfig? = null
    private var latestStreamConfig: VideoReceiverStreamConfig? = null
    private var currentRenderTarget: VideoRenderTarget? = null
    private var lastError = VideoSessionFailure()
    private var framesDelivered = 0L
    private var nonKeyframesDroppedAwaitingKeyframe = 0L
    private var discontinuities = 0L
    private var surfaceRecreatedRequiresKeyFrame = false

    override suspend fun start(config: VideoReceiverSessionConfig): VideoSessionControlResult {
        synchronized(lock) {
            if (core.state == VideoSessionState.Closed) {
                return rememberLocked(sessionFailure(VideoSessionError.Closed))
            }
            if (core.state != VideoSessionState.Idle && core.state != VideoSessionState.Error) {
                return rememberLocked(sessionFailure(VideoSessionError.AlreadyRunning))
            }
            if (config.performanceConfig.validate() != VideoTransportError.None) {
                return rememberLocked(sessionFailure(VideoSessionError.InvalidConfiguration))
            }
            currentConfig = config
            currentRenderTarget = renderController.currentTarget()
            core.start(surfaceAvailable = currentRenderTarget != null, configAvailable = latestStreamConfig != null)
        }

        val open = receiverRuntimeController.open(config.receiverRuntimeConfig)
        if (!open.isSuccess) {
            synchronized(lock) {
                core.onError()
                return rememberLocked(
                    VideoSessionFailure(
                        source = VideoSessionErrorSource.Receiver,
                        error = VideoSessionError.ReceiverFailed,
                        transportError = open.error,
                    ),
                )
            }
        }

        val start = receiverRuntimeController.start(runtimeListener)
        if (!start.isSuccess) {
            synchronized(lock) {
                core.onError()
                return rememberLocked(
                    VideoSessionFailure(
                        source = VideoSessionErrorSource.Receiver,
                        error = VideoSessionError.ReceiverFailed,
                        transportError = start.error,
                    ),
                )
            }
        }
        prepareDecoderIfReady()
        scheduleConfigResyncIfNeeded(config)
        return VideoSessionControlResult.Success
    }

    override fun onRenderTargetAvailable(target: VideoRenderTarget) {
        synchronized(lock) {
            currentRenderTarget = target
            core.onSurfaceAvailable()
        }
        scope.launch { prepareDecoderIfReady() }
    }

    override fun onRenderTargetChanged(target: VideoRenderTarget) {
        synchronized(lock) {
            currentRenderTarget = target
            core.onSurfaceAvailable()
        }
        scope.launch { prepareDecoderIfReady() }
    }

    override fun onRenderTargetDestroyed(surfaceGeneration: Long) {
        val shouldStopDecoder = synchronized(lock) {
            if (currentRenderTarget?.surfaceGeneration == surfaceGeneration) {
                currentRenderTarget = null
            }
            surfaceRecreatedRequiresKeyFrame = true
            core.onSurfaceDestroyed()
            true
        }
        receiverRuntimeController.setAwaitingKeyFrame(true)
        if (shouldStopDecoder) {
            scope.launch {
                decoderController.stop()
            }
        }
    }

    override suspend fun stop(): VideoSessionControlResult {
        synchronized(lock) {
            if (core.state == VideoSessionState.Closed) {
                return rememberLocked(sessionFailure(VideoSessionError.Closed))
            }
            if (core.state == VideoSessionState.Idle) {
                return VideoSessionControlResult.Success
            }
            core.state
        }
        receiverRuntimeController.stop()
        decoderController.stop()
        synchronized(lock) {
            core.stop()
            lastError = VideoSessionFailure()
        }
        return VideoSessionControlResult.Success
    }

    override fun snapshot(): VideoReceiverSessionSnapshot {
        val state = synchronized(lock) { core.state }
        val streamConfig = synchronized(lock) { latestStreamConfig }
        return VideoReceiverSessionSnapshot(
            state = state,
            activeConfigGeneration = streamConfig?.configGeneration ?: 0L,
            videoWidth = streamConfig?.width,
            videoHeight = streamConfig?.height,
            receiver = receiverRuntimeController.snapshot(),
            decoder = decoderController.snapshot(),
            renderer = renderController.snapshot(),
            framesDelivered = framesDelivered,
            nonKeyframesDroppedAwaitingKeyframe = nonKeyframesDroppedAwaitingKeyframe,
            discontinuities = discontinuities,
            lastPresentationTimeUs = receiverRuntimeController.snapshot().lastPresentationTimeUs,
            lastFrameId = receiverRuntimeController.snapshot().lastFrameId,
            lastError = lastError,
        )
    }

    override fun close() {
        runBlocking {
            stop()
        }
        receiverRuntimeController.close()
        decoderController.close()
        renderController.close()
        synchronized(lock) {
            core.close()
        }
    }

    private suspend fun prepareDecoderIfReady() {
        val config: VideoReceiverSessionConfig
        val streamConfig: VideoReceiverStreamConfig
        val target: VideoRenderTarget
        synchronized(lock) {
            config = currentConfig ?: return
            streamConfig = latestStreamConfig ?: return
            target = currentRenderTarget ?: return
            if (core.state != VideoSessionState.PreparingDecoder) {
                return
            }
        }

        decoderController.stop()
        val geometry = renderController.setVideoGeometry(streamConfig.width, streamConfig.height)
        if (!geometry.isSuccess) {
            synchronized(lock) {
                core.onError()
                rememberLocked(
                    VideoSessionFailure(
                        source = VideoSessionErrorSource.Renderer,
                        error = VideoSessionError.RendererFailed,
                    ),
                )
            }
            return
        }
        val activateError = receiverRuntimeController.activateConfigGeneration(streamConfig.configGeneration)
        if (activateError != VideoTransportError.None) {
            synchronized(lock) {
                core.onError()
                rememberLocked(
                    VideoSessionFailure(
                        source = VideoSessionErrorSource.Receiver,
                        error = VideoSessionError.ReceiverFailed,
                        transportError = activateError,
                    ),
                )
            }
            return
        }
        receiverRuntimeController.setAwaitingKeyFrame(true)
        val prepare = decoderController.prepare(
            config = config.toDecoderConfig(streamConfig),
            outputSurface = target.surface,
            inputSource = receiverRuntimeController.inputSource,
            outputSink = renderController.decodedVideoSink,
        )
        if (!prepare.isSuccess) {
            synchronized(lock) {
                core.onError()
                rememberLocked(
                    VideoSessionFailure(
                        source = VideoSessionErrorSource.Decoder,
                        error = VideoSessionError.DecoderFailed,
                        decoderError = prepare.error,
                    ),
                )
            }
            return
        }
        val start = decoderController.start()
        synchronized(lock) {
            if (start.isSuccess) {
                core.onDecoderPrepared()
                lastError = VideoSessionFailure()
                val reason = if (surfaceRecreatedRequiresKeyFrame) {
                    surfaceRecreatedRequiresKeyFrame = false
                    VideoResyncReason.SurfaceRecreated
                } else {
                    VideoResyncReason.DecoderRestart
                }
                requestResyncLocked(reason)
                scheduleKeyFrameResyncIfNeeded(config)
            } else {
                core.onError()
                rememberLocked(
                    VideoSessionFailure(
                        source = VideoSessionErrorSource.Decoder,
                        error = VideoSessionError.DecoderFailed,
                        decoderError = start.error,
                    ),
                )
            }
        }
    }

    private val runtimeListener = object : VideoReceiverRuntimeListener {
        override fun onStreamConfig(config: VideoReceiverStreamConfig) {
            synchronized(lock) {
                latestStreamConfig = config
                core.onStreamConfigReady()
            }
            scope.launch { prepareDecoderIfReady() }
        }

        override fun onAccessUnitReady(accessUnit: VideoReceiverAccessUnitReady) {
            val shouldNotify = synchronized(lock) {
                val before = core.state
                core.onAccessUnitReady(accessUnit.keyframe)
                if (before == VideoSessionState.WaitingForKeyFrame && !accessUnit.keyframe) {
                    nonKeyframesDroppedAwaitingKeyframe += 1
                }
                if (core.state == VideoSessionState.Streaming || core.state == VideoSessionState.WaitingForKeyFrame) {
                    framesDelivered += if (accessUnit.keyframe || before == VideoSessionState.Streaming) 1 else 0
                    true
                } else {
                    false
                }
            }
            if (shouldNotify) {
                decoderController.notifyInputAvailable()
            }
        }

        override fun onDiscontinuity(error: VideoTransportError) {
            discontinuities += 1
            receiverRuntimeController.setAwaitingKeyFrame(true)
            synchronized(lock) {
                core.onDiscontinuity()
                requestResyncLocked(VideoResyncReason.Discontinuity)
                lastError = VideoSessionFailure(
                    source = VideoSessionErrorSource.Receiver,
                    error = VideoSessionError.KeyFrameRequired,
                    transportError = error,
                )
            }
            currentConfig?.let(::scheduleKeyFrameResyncIfNeeded)
        }

        override fun onRuntimeError(error: VideoTransportError) {
            synchronized(lock) {
                core.onError()
                lastError = VideoSessionFailure(
                    source = VideoSessionErrorSource.Receiver,
                    error = VideoSessionError.ReceiverFailed,
                    transportError = error,
                )
            }
        }
    }

    private fun rememberLocked(failure: VideoSessionFailure): VideoSessionControlResult {
        lastError = failure
        return VideoSessionControlResult(failure.error, failure)
    }

    private fun sessionFailure(error: VideoSessionError): VideoSessionFailure = VideoSessionFailure(
        source = VideoSessionErrorSource.Session,
        error = error,
    )

    private fun scheduleConfigResyncIfNeeded(config: VideoReceiverSessionConfig) {
        scope.launch {
            delay(millisForUs(config.performanceConfig.startupConfigRequestDelayUs))
            synchronized(lock) {
                if (core.state == VideoSessionState.WaitingForConfig) {
                    requestResyncLocked(VideoResyncReason.NeedConfiguration)
                }
            }
        }
    }

    private fun scheduleKeyFrameResyncIfNeeded(config: VideoReceiverSessionConfig) {
        scope.launch {
            delay(millisForUs(config.performanceConfig.keyFrameWaitRequestDelayUs))
            synchronized(lock) {
                if (core.state == VideoSessionState.WaitingForKeyFrame) {
                    requestResyncLocked(VideoResyncReason.NeedKeyFrame)
                }
            }
        }
    }

    private fun requestResyncLocked(reason: VideoResyncReason) {
        val generation = latestStreamConfig?.configGeneration ?: 0L
        val error = receiverRuntimeController.requestResync(reason, generation)
        if (error != VideoTransportError.None) {
            lastError = VideoSessionFailure(
                source = VideoSessionErrorSource.Receiver,
                error = VideoSessionError.ReceiverFailed,
                transportError = error,
            )
        }
    }

    private fun millisForUs(valueUs: Long): Long = if (valueUs <= 0L) {
        0L
    } else {
        (valueUs + MICROS_PER_MILLI - 1L) / MICROS_PER_MILLI
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}

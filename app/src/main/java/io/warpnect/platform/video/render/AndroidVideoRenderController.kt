package io.warpnect.platform.video.render

import android.view.SurfaceHolder
import android.view.SurfaceView
import io.warpnect.video.decoder.DecodedVideoFrame
import io.warpnect.video.decoder.DecodedVideoOutputAction
import io.warpnect.video.decoder.DecodedVideoSink
import io.warpnect.video.decoder.VideoDecoderFrameRenderedEvent
import io.warpnect.video.decoder.VideoDecoderOutputFormat
import io.warpnect.video.render.ImmediateLowLatencyVideoRenderPolicy
import io.warpnect.video.render.SystemVideoRenderClock
import io.warpnect.video.render.VideoFrameRateHintPlanner
import io.warpnect.video.render.VideoRenderClock
import io.warpnect.video.render.VideoRenderControlResult
import io.warpnect.video.render.VideoRenderController
import io.warpnect.video.render.VideoRenderControllerCore
import io.warpnect.video.render.VideoRenderDecision
import io.warpnect.video.render.VideoRenderError
import io.warpnect.video.render.VideoRenderPolicy
import io.warpnect.video.render.VideoRenderSnapshot
import io.warpnect.video.render.VideoRenderTarget
import io.warpnect.video.render.VideoRenderTargetListener
import java.util.concurrent.atomic.AtomicReference

class AndroidVideoRenderController(
    private val clock: VideoRenderClock = SystemVideoRenderClock,
    private val targetListener: VideoRenderTargetListener = object : VideoRenderTargetListener {},
    private val debugObserver: VideoRenderDebugObserver = VideoRenderDebugObserver.None,
) : VideoRenderController,
    SurfaceHolder.Callback {
    private val core = VideoRenderControllerCore()
    private val policyRef = AtomicReference<VideoRenderPolicy>(ImmediateLowLatencyVideoRenderPolicy)

    @Volatile
    private var target: VideoRenderTarget? = null

    @Volatile
    private var attachedView: SurfaceView? = null
    private var observedSurfaceGeneration = -1L

    @Volatile
    private var decoderBoundSurfaceGeneration = -1L
    private var observedRenderedSurfaceGeneration = -1L

    override val decodedVideoSink: DecodedVideoSink = object : DecodedVideoSink {
        override fun onFrameAvailable(frame: DecodedVideoFrame): DecodedVideoOutputAction = decideOutput(frame)

        override fun onOutputFormatChanged(format: VideoDecoderOutputFormat) {
            val width = format.width
            val height = format.height
            if (width != null && height != null) {
                setVideoGeometry(width, height)
            }
        }

        override fun onFrameRendered(event: VideoDecoderFrameRenderedEvent) {
            val generation = target?.surfaceGeneration ?: return
            if (decoderBoundSurfaceGeneration != generation || observedRenderedSurfaceGeneration == generation) {
                return
            }
            observedRenderedSurfaceGeneration = generation
            runCatching { debugObserver.onRemoteFrameRendered(generation) }
        }
    }

    fun attach(surfaceView: SurfaceView) {
        if (core.snapshot().state == io.warpnect.video.render.VideoRenderState.Closed) {
            return
        }
        val previous = attachedView
        if (previous === surfaceView) {
            return
        }
        previous?.holder?.removeCallback(this)
        attachedView = surfaceView
        surfaceView.holder.addCallback(this)
        val surfaceWasValid = surfaceView.holder.surface.isValid
        runCatching { debugObserver.onControllerAttached(surfaceWasValid) }
        // Compose may attach the controller after Android has already created the SurfaceView surface.
        // SurfaceHolder does not replay that lifecycle callback on every device, so publish it here.
        if (surfaceWasValid) {
            surfaceCreated(surfaceView.holder)
        }
        surfaceView.requestLayout()
    }

    /** Called by the Activity-owned view bridge when Compose removes the current render target. */
    fun detach(surfaceView: SurfaceView) {
        if (attachedView !== surfaceView) {
            return
        }
        attachedView = null
        surfaceView.holder.removeCallback(this)
        val generation = core.snapshot().surfaceGeneration
        if (target != null) {
            target = null
            decoderBoundSurfaceGeneration = -1L
            core.surfaceDestroyed()
            targetListener.onRenderTargetDestroyed(generation)
            runCatching { debugObserver.onRenderTargetDestroyed(generation) }
        }
    }

    override fun setVideoGeometry(width: Int, height: Int): VideoRenderControlResult {
        val error = core.setVideoGeometry(width, height)
        attachedView?.requestLayout()
        return VideoRenderControlResult(error, core.snapshot())
    }

    override fun setPreferredFrameRate(frameRateHz: Float?): VideoRenderControlResult {
        val error = core.setPreferredFrameRate(frameRateHz)
        if (error == VideoRenderError.None) {
            applyCurrentFrameRateHint()
        }
        return VideoRenderControlResult(error, core.snapshot())
    }

    override fun setRenderPolicy(policy: VideoRenderPolicy): VideoRenderControlResult {
        if (core.snapshot().state == io.warpnect.video.render.VideoRenderState.Closed) {
            return VideoRenderControlResult(VideoRenderError.Closed, core.snapshot())
        }
        policyRef.set(policy)
        return VideoRenderControlResult(VideoRenderError.None, core.snapshot())
    }

    override fun currentTarget(): VideoRenderTarget? = target

    /** Local lifecycle breadcrumb after the active decoder binds the current render target. */
    internal fun onDecoderPreparedForSurfaceGeneration(surfaceGeneration: Long) {
        if (target?.surfaceGeneration != surfaceGeneration) return
        decoderBoundSurfaceGeneration = surfaceGeneration
        runCatching { debugObserver.onDecoderPrepared(surfaceGeneration) }
    }

    override fun snapshot(): VideoRenderSnapshot = core.snapshot()

    override fun close() {
        clearFrameRateHintIfNeeded()
        attachedView?.holder?.removeCallback(this)
        attachedView = null
        target = null
        core.close()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        if (!surface.isValid) {
            target = null
            core.recordError(VideoRenderError.SurfaceInvalid)
            return
        }
        if (target?.surface === surface && core.snapshot().surfaceAvailable) {
            return
        }
        val frame = holder.surfaceFrame
        val generation = core.surfaceCreated(frame.width(), frame.height())
        val renderTarget = VideoRenderTarget(
            surface = surface,
            surfaceGeneration = generation,
            width = frame.width(),
            height = frame.height(),
        )
        target = renderTarget
        decoderBoundSurfaceGeneration = -1L
        applyCurrentFrameRateHint()
        targetListener.onRenderTargetAvailable(renderTarget)
        emitRenderTargetAvailable()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val surface = holder.surface
        if (!surface.isValid) {
            target = null
            core.recordError(VideoRenderError.SurfaceInvalid)
            return
        }
        core.surfaceChanged(width, height)
        val renderTarget = VideoRenderTarget(
            surface = surface,
            surfaceGeneration = core.snapshot().surfaceGeneration,
            width = width,
            height = height,
        )
        target = renderTarget
        applyCurrentFrameRateHint()
        targetListener.onRenderTargetChanged(renderTarget)
        emitRenderTargetAvailable()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val generation = core.snapshot().surfaceGeneration
        target = null
        decoderBoundSurfaceGeneration = -1L
        core.surfaceDestroyed()
        targetListener.onRenderTargetDestroyed(generation)
        runCatching { debugObserver.onRenderTargetDestroyed(generation) }
    }

    private fun decideOutput(frame: DecodedVideoFrame): DecodedVideoOutputAction {
        if (!core.canRender()) {
            core.recordDecision(VideoRenderDecision.Drop, frame.presentationTimeUs)
            return DecodedVideoOutputAction.Drop
        }
        val decision = try {
            policyRef.get().decide(frame, clock.nowNs())
        } catch (_: Throwable) {
            core.recordError(VideoRenderError.RenderPolicyFailure)
            core.recordDecision(VideoRenderDecision.Drop, frame.presentationTimeUs)
            return DecodedVideoOutputAction.Drop
        }
        if (decision is VideoRenderDecision.RenderAtLocalTime && decision.timestampNs < 0L) {
            core.recordError(VideoRenderError.InvalidRenderTimestamp)
            core.recordDecision(VideoRenderDecision.Drop, frame.presentationTimeUs)
            return DecodedVideoOutputAction.Drop
        }
        core.recordDecision(decision, frame.presentationTimeUs)
        return when (decision) {
            VideoRenderDecision.RenderImmediately -> DecodedVideoOutputAction.RenderNow
            VideoRenderDecision.Drop -> DecodedVideoOutputAction.Drop
            is VideoRenderDecision.RenderAtLocalTime -> DecodedVideoOutputAction.RenderAt(decision.timestampNs)
        }
    }

    private fun emitRenderTargetAvailable() {
        val generation = target?.surfaceGeneration ?: return
        if (observedSurfaceGeneration == generation) return
        observedSurfaceGeneration = generation
        runCatching { debugObserver.onRenderTargetAvailable(generation) }
    }

    private fun applyCurrentFrameRateHint() {
        val currentTarget = target
        if (currentTarget == null || !currentTarget.surface.isValid || !AndroidFrameRateHintApplier.isSupported) {
            core.markFrameRateHintApplied(applied = false)
            return
        }
        val plan = VideoFrameRateHintPlanner.plan(
            preferredFrameRateHz = core.snapshot().preferredFrameRateHz,
            supportsChangeStrategy = AndroidFrameRateHintApplier.supportsChangeStrategy,
        )
        val applied = AndroidFrameRateHintApplier.apply(currentTarget.surface, plan)
        core.markFrameRateHintApplied(
            applied = applied,
            error = if (applied) VideoRenderError.None else VideoRenderError.FrameRateHintFailed,
        )
    }

    private fun clearFrameRateHintIfNeeded() {
        val currentTarget = target ?: return
        if (!currentTarget.surface.isValid || !AndroidFrameRateHintApplier.isSupported) {
            return
        }
        val plan = VideoFrameRateHintPlanner.plan(
            preferredFrameRateHz = null,
            supportsChangeStrategy = AndroidFrameRateHintApplier.supportsChangeStrategy,
        )
        AndroidFrameRateHintApplier.apply(currentTarget.surface, plan)
    }
}

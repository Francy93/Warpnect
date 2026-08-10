package io.warpnect.video.render

import java.util.concurrent.atomic.AtomicReference

internal class VideoRenderControllerCore {
    private val snapshotRef = AtomicReference(VideoRenderSnapshot())

    fun snapshot(): VideoRenderSnapshot = snapshotRef.get()

    fun setVideoGeometry(width: Int, height: Int): VideoRenderError {
        if (width <= 0 || height <= 0) {
            recordError(VideoRenderError.InvalidVideoGeometry)
            return VideoRenderError.InvalidVideoGeometry
        }
        return updateIfOpen(VideoRenderError.Closed) {
            it.copy(
                videoWidth = width,
                videoHeight = height,
                lastError = VideoRenderError.None,
            )
        }
    }

    fun setPreferredFrameRate(frameRateHz: Float?): VideoRenderError {
        val validation = VideoFrameRateHintPlanner.validate(frameRateHz)
        if (validation != VideoRenderError.None) {
            recordError(validation)
            return validation
        }
        return updateIfOpen(VideoRenderError.Closed) {
            it.copy(
                preferredFrameRateHz = frameRateHz,
                frameRateHintApplied = false,
                lastError = VideoRenderError.None,
            )
        }
    }

    fun markFrameRateHintApplied(applied: Boolean, error: VideoRenderError = VideoRenderError.None) {
        snapshotRef.updateAndGet {
            it.copy(
                frameRateHintApplied = applied,
                frameRateHintFailures = it.frameRateHintFailures +
                    if (error == VideoRenderError.FrameRateHintFailed) 1 else 0,
                lastError = if (error == VideoRenderError.None) it.lastError else error,
            )
        }
    }

    fun surfaceCreated(width: Int, height: Int): Long {
        var generation = 0L
        snapshotRef.updateAndGet {
            if (it.state == VideoRenderState.Closed) {
                generation = it.surfaceGeneration
                it
            } else {
                generation = nextGeneration(it.surfaceGeneration)
                it.copy(
                    state = VideoRenderState.SurfaceAvailable,
                    surfaceAvailable = true,
                    surfaceGeneration = generation,
                    surfaceWidth = width.coerceAtLeast(0),
                    surfaceHeight = height.coerceAtLeast(0),
                    surfaceCreateCount = it.surfaceCreateCount + 1,
                    lastError = VideoRenderError.None,
                )
            }
        }
        return generation
    }

    fun surfaceChanged(width: Int, height: Int) {
        snapshotRef.updateAndGet {
            if (it.state == VideoRenderState.Closed) {
                it
            } else {
                it.copy(
                    state = VideoRenderState.SurfaceAvailable,
                    surfaceAvailable = true,
                    surfaceWidth = width.coerceAtLeast(0),
                    surfaceHeight = height.coerceAtLeast(0),
                    lastError = VideoRenderError.None,
                )
            }
        }
    }

    fun surfaceDestroyed() {
        snapshotRef.updateAndGet {
            if (it.state == VideoRenderState.Closed) {
                it
            } else {
                it.copy(
                    state = VideoRenderState.SurfaceDestroyed,
                    surfaceAvailable = false,
                    surfaceWidth = null,
                    surfaceHeight = null,
                    surfaceDestroyCount = it.surfaceDestroyCount + 1,
                    lastError = VideoRenderError.SurfaceDestroyed,
                )
            }
        }
    }

    fun canRender(): Boolean {
        val snapshot = snapshot()
        return snapshot.surfaceAvailable &&
            snapshot.state != VideoRenderState.Closed &&
            snapshot.state != VideoRenderState.SurfaceDestroyed
    }

    fun recordDecision(decision: VideoRenderDecision, presentationTimeUs: Long) {
        snapshotRef.updateAndGet {
            it.copy(
                state = if (it.surfaceAvailable) VideoRenderState.Rendering else it.state,
                framesReceived = it.framesReceived + 1,
                renderNowDecisions = it.renderNowDecisions +
                    if (decision == VideoRenderDecision.RenderImmediately) 1 else 0,
                scheduledRenderDecisions = it.scheduledRenderDecisions +
                    if (decision is VideoRenderDecision.RenderAtLocalTime) 1 else 0,
                dropDecisions = it.dropDecisions + if (decision == VideoRenderDecision.Drop) 1 else 0,
                lastFramePtsUs = presentationTimeUs,
                lastScheduledTimestampNs = if (decision is VideoRenderDecision.RenderAtLocalTime) {
                    decision.timestampNs
                } else {
                    it.lastScheduledTimestampNs
                },
                lastError = VideoRenderError.None,
            )
        }
    }

    fun recordError(error: VideoRenderError) {
        snapshotRef.updateAndGet {
            it.copy(
                renderPolicyFailures = it.renderPolicyFailures +
                    if (error == VideoRenderError.RenderPolicyFailure) 1 else 0,
                lastError = error,
            )
        }
    }

    fun close() {
        snapshotRef.updateAndGet {
            it.copy(
                state = VideoRenderState.Closed,
                surfaceAvailable = false,
                surfaceWidth = null,
                surfaceHeight = null,
                frameRateHintApplied = false,
                lastError = VideoRenderError.Closed,
            )
        }
    }

    private fun updateIfOpen(closedError: VideoRenderError, block: (VideoRenderSnapshot) -> VideoRenderSnapshot) =
        if (snapshot().state == VideoRenderState.Closed) {
            closedError
        } else {
            snapshotRef.updateAndGet(block)
            VideoRenderError.None
        }

    private fun nextGeneration(previous: Long): Long = if (previous == Long.MAX_VALUE) 1L else previous + 1L
}

package io.warpnect.video.session

internal class VideoReceiverSessionCore {
    var state: VideoSessionState = VideoSessionState.Idle
        private set

    var hasSurface: Boolean = false
        private set

    var hasConfig: Boolean = false
        private set

    fun start(surfaceAvailable: Boolean, configAvailable: Boolean): VideoSessionState {
        hasSurface = surfaceAvailable
        hasConfig = configAvailable
        state = nextPrerequisiteState()
        return state
    }

    fun onSurfaceAvailable(): VideoSessionState {
        hasSurface = true
        state = nextPrerequisiteState()
        return state
    }

    fun onSurfaceDestroyed(): VideoSessionState {
        hasSurface = false
        state = VideoSessionState.WaitingForSurface
        return state
    }

    fun onStreamConfigReady(): VideoSessionState {
        hasConfig = true
        state = nextPrerequisiteState()
        return state
    }

    fun onDecoderPrepared(): VideoSessionState {
        state = VideoSessionState.WaitingForKeyFrame
        return state
    }

    fun onAccessUnitReady(keyframe: Boolean): VideoSessionState {
        if (state == VideoSessionState.WaitingForKeyFrame && keyframe) {
            state = VideoSessionState.Streaming
        }
        return state
    }

    fun onDiscontinuity(): VideoSessionState {
        state = if (hasSurface && hasConfig) {
            VideoSessionState.WaitingForKeyFrame
        } else {
            nextPrerequisiteState()
        }
        return state
    }

    fun onError(): VideoSessionState {
        state = VideoSessionState.Error
        return state
    }

    fun stop(): VideoSessionState {
        state = VideoSessionState.Idle
        return state
    }

    fun close(): VideoSessionState {
        state = VideoSessionState.Closed
        return state
    }

    private fun nextPrerequisiteState(): VideoSessionState = when {
        !hasSurface -> VideoSessionState.WaitingForSurface
        !hasConfig -> VideoSessionState.WaitingForConfig
        else -> VideoSessionState.PreparingDecoder
    }
}

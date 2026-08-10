package io.warpnect.video.render

data class VideoRenderSnapshot(
    val state: VideoRenderState = VideoRenderState.WaitingForSurface,
    val surfaceAvailable: Boolean = false,
    val surfaceGeneration: Long = 0,
    val surfaceWidth: Int? = null,
    val surfaceHeight: Int? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val preferredFrameRateHz: Float? = null,
    val frameRateHintApplied: Boolean = false,
    val framesReceived: Long = 0,
    val renderNowDecisions: Long = 0,
    val scheduledRenderDecisions: Long = 0,
    val dropDecisions: Long = 0,
    val lastFramePtsUs: Long? = null,
    val lastScheduledTimestampNs: Long? = null,
    val surfaceCreateCount: Long = 0,
    val surfaceDestroyCount: Long = 0,
    val frameRateHintFailures: Long = 0,
    val renderPolicyFailures: Long = 0,
    val lastError: VideoRenderError = VideoRenderError.None,
)

package io.warpnect.platform.video.encoder

/**
 * DEBUG-only, one-shot observation of a real encoder access unit. The callback carries no media
 * data and must never influence the encoder path.
 */
fun interface VideoEncoderFrameDebugObserver {
    fun onFirstAccessUnitEncoded()

    companion object {
        val None = VideoEncoderFrameDebugObserver {}
    }
}

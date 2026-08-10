package io.warpnect.video.transport

enum class VideoResyncReason {
    Unknown,
    NeedConfiguration,
    NeedKeyFrame,
    Discontinuity,
    DecoderRestart,
    SurfaceRecreated,
    ReceiverOverflow,
    ;

    companion object {
        fun fromNativeCode(code: Int): VideoResyncReason = entries.getOrElse(code) { Unknown }
    }
}

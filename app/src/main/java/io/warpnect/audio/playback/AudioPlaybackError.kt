package io.warpnect.audio.playback

enum class AudioPlaybackError {
    None,
    InvalidConfiguration,
    UnsupportedSampleRate,
    UnsupportedChannelCount,
    UnsupportedPcmFormat,
    OboeDependencyUnavailable,
    StreamOpenFailed,
    StreamStartFailed,
    StreamStopFailed,
    StreamDisconnected,
    RequestedFormatMismatch,
    RequestedSampleRateMismatch,
    RequestedChannelMismatch,
    ExclusiveModeUnavailable,
    LowLatencyModeUnavailable,
    NonDirectBuffer,
    InvalidBufferRange,
    InvalidFrameCount,
    ConfigGenerationMismatch,
    PlaybackRingFull,
    PlaybackNotPrimed,
    PresentationTimestampUnavailable,
    AlreadyPrepared,
    AlreadyRunning,
    NotPrepared,
    NotRunning,
    Closed,
    ;

    companion object {
        fun fromNativeCode(code: Int): AudioPlaybackError = entries.getOrElse(code) { StreamOpenFailed }
    }
}

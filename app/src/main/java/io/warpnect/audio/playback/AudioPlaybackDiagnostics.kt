package io.warpnect.audio.playback

enum class AudioPlaybackPerformanceMode {
    Unknown,
    None,
    PowerSaving,
    LowLatency,
}

enum class AudioPlaybackSharingMode {
    Unknown,
    Exclusive,
    Shared,
}

enum class AudioPlaybackApi {
    Unknown,
    OpenSLES,
    AAudio,
}

enum class AudioPlaybackPcmFormat {
    Unknown,
    Pcm16,
}

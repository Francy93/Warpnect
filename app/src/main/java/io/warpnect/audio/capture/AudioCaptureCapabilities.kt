package io.warpnect.audio.capture

data class AudioCaptureCapabilities(
    val source: AudioCaptureSource,
    val available: Boolean,
    val supportedSampleRatesHz: List<Int> = emptyList(),
    val selectedSampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val encoding: AudioPcmEncoding = AudioPcmEncoding.Pcm16,
    val privilegedBackendAvailable: Boolean = false,
    val unprocessedMicrophoneSupported: Boolean = false,
    val requestedBufferSizeBytes: Int = 0,
    val actualBufferSizeFrames: Int = 0,
    val timestampSupport: AudioTimestampQuality = AudioTimestampQuality.Unavailable,
    val lastError: AudioCaptureError = AudioCaptureError.None,
)

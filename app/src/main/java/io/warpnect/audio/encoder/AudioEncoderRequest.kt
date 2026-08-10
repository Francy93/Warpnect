package io.warpnect.audio.encoder

import io.warpnect.audio.capture.AudioCaptureSource

data class AudioEncoderRequest(
    val codec: AudioCodec = AudioCodec.Opus,
    val source: AudioCaptureSource,
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val channelCount: Int,
    val frameDurationUs: Int = DEFAULT_FRAME_DURATION_US,
    val bitrateBps: Int = defaultAudioBitrateBps(source, channelCount),
    val bitrateMode: AudioBitrateMode = AudioBitrateMode.ConstantBitrate,
    val complexity: Int = DEFAULT_COMPLEXITY,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_FRAME_DURATION_US = 5_000
        const val DEFAULT_COMPLEXITY = 5
        const val SYSTEM_AUDIO_STEREO_BITRATE_BPS = 128_000
        const val MICROPHONE_MONO_BITRATE_BPS = 64_000
        const val MIN_BITRATE_BPS = 500
        const val MAX_BITRATE_BPS = 512_000
    }
}

fun defaultAudioBitrateBps(source: AudioCaptureSource, channelCount: Int): Int =
    if (source == AudioCaptureSource.SystemAudio && channelCount == 2) {
        AudioEncoderRequest.SYSTEM_AUDIO_STEREO_BITRATE_BPS
    } else {
        AudioEncoderRequest.MICROPHONE_MONO_BITRATE_BPS
    }

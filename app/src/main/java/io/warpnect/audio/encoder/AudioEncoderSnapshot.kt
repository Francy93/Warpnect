package io.warpnect.audio.encoder

import io.warpnect.audio.capture.AudioCaptureSource

data class AudioEncoderSnapshot(
    val state: AudioEncoderState = AudioEncoderState.Stopped,
    val source: AudioCaptureSource? = null,
    val codec: AudioCodec = AudioCodec.Unknown,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val frameDurationUs: Int = 0,
    val samplesPerFrame: Int = 0,
    val bitrateBps: Int = 0,
    val bitrateMode: AudioBitrateMode = AudioBitrateMode.ConstantBitrate,
    val complexity: Int = 0,
    val lookaheadSamples: Int = 0,
    val pcmChunksReceived: Long = 0,
    val pcmFramesReceived: Long = 0,
    val encodedFrames: Long = 0,
    val encodedBytes: Long = 0,
    val directFastPathFrames: Long = 0,
    val assemblerFrames: Long = 0,
    val partialFrameSamples: Int = 0,
    val pcmDiscontinuities: Long = 0,
    val pcmFramesSkipped: Long = 0,
    val tailFramesDropped: Long = 0,
    val lastInputFramePosition: Long = 0,
    val lastEncodedFramePosition: Long = 0,
    val lastCaptureTimeNs: Long = 0,
    val lastNativeError: Int = 0,
    val lastError: AudioEncoderError = AudioEncoderError.None,
)

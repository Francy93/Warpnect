package io.warpnect.audio.decoder

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.encoder.AudioCodec

data class AudioDecoderSnapshot(
    val state: AudioDecoderState = AudioDecoderState.Stopped,
    val source: AudioCaptureSource? = null,
    val codec: AudioCodec = AudioCodec.Unknown,
    val configGeneration: Long = 0,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val frameDurationUs: Int = 0,
    val samplesPerFrame: Int = 0,
    val lookaheadSamples: Int = 0,
    val packetsSubmitted: Long = 0,
    val encodedBytesSubmitted: Long = 0,
    val framesDecoded: Long = 0,
    val pcmFramesDecoded: Long = 0,
    val pcmBytesDecoded: Long = 0,
    val plcFramesGenerated: Long = 0,
    val malformedPackets: Long = 0,
    val durationMismatches: Long = 0,
    val decodeFailures: Long = 0,
    val sinkFailures: Long = 0,
    val lastFramePosition: Long = 0,
    val lastCaptureTimeUs: Long = 0,
    val lastDecodedSamples: Int = 0,
    val lastNativeError: Int = 0,
    val lastError: AudioDecoderError = AudioDecoderError.None,
)

package io.warpnect.audio.session

import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSnapshot
import io.warpnect.audio.decoder.AudioDecoderConfig
import io.warpnect.audio.decoder.AudioDecoderError
import io.warpnect.audio.decoder.AudioDecoderSnapshot
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.encoder.AudioEncoderSnapshot
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import io.warpnect.audio.transport.AudioReceiverRuntimeSnapshot
import io.warpnect.audio.transport.AudioReceiverStreamConfig
import io.warpnect.audio.transport.AudioTransportConfig
import io.warpnect.audio.transport.AudioTransportError
import io.warpnect.audio.transport.AudioTransportSnapshot

enum class AudioSessionState {
    Idle,
    Starting,
    WaitingForConfig,
    PreparingPipeline,
    WaitingForFirstFrame,
    Primed,
    Streaming,
    Stopping,
    Error,
    Closed,
}

enum class AudioSessionErrorSource {
    None,
    Capture,
    Encoder,
    Transport,
    ReceiverRuntime,
    Decoder,
    Playback,
    Session,
}

enum class AudioSessionError {
    None,
    InvalidConfiguration,
    AlreadyRunning,
    NotRunning,
    Closed,
    CaptureFailed,
    EncoderFailed,
    TransportFailed,
    ReceiverFailed,
    DecoderFailed,
    PlaybackFailed,
    StreamConfigRequired,
    AudioTimelineDiscontinuity,
}

data class AudioSessionFailure(
    val source: AudioSessionErrorSource = AudioSessionErrorSource.None,
    val error: AudioSessionError = AudioSessionError.None,
    val captureError: AudioCaptureError? = null,
    val encoderError: AudioEncoderError? = null,
    val transportError: AudioTransportError? = null,
    val decoderError: AudioDecoderError? = null,
    val playbackError: AudioPlaybackError? = null,
)

data class AudioSessionControlResult(
    val error: AudioSessionError,
    val failure: AudioSessionFailure,
) {
    val isSuccess: Boolean
        get() = error == AudioSessionError.None

    companion object {
        val Success = AudioSessionControlResult(
            error = AudioSessionError.None,
            failure = AudioSessionFailure(),
        )
    }
}

data class AudioTransmitterSessionConfig(
    val captureRequest: AudioCaptureRequest,
    val encoderRequest: AudioEncoderRequest,
    val transportConfig: AudioTransportConfig,
)

data class AudioReceiverSessionConfig(
    val receiverRuntimeConfig: AudioReceiverRuntimeConfig,
    val playbackRingCapacityCodecFrames: Int = 4,
    val playbackStartThresholdCodecFrames: Int = 1,
    val playbackRequestedBufferBursts: Int = 2,
    val maxImmediatePlcFrames: Int = DEFAULT_MAX_IMMEDIATE_PLC_FRAMES,
) {
    companion object {
        const val DEFAULT_MAX_IMMEDIATE_PLC_FRAMES = 2
    }
}

data class AudioTransmitterSessionSnapshot(
    val state: AudioSessionState = AudioSessionState.Idle,
    val source: io.warpnect.audio.capture.AudioCaptureSource? = null,
    val remoteAddress: String? = null,
    val remotePort: Int? = null,
    val capture: AudioCaptureSnapshot? = null,
    val encoder: AudioEncoderSnapshot? = null,
    val transport: AudioTransportSnapshot? = null,
    val pcmFramesCaptured: Long = 0,
    val opusFramesEncoded: Long = 0,
    val audioFramesSubmitted: Long = 0,
    val datagramsSent: Long = 0,
    val wouldBlockDrops: Long = 0,
    val sendFailureDrops: Long = 0,
    val lastFramePosition: Long = 0,
    val lastCaptureTimeNs: Long = 0,
    val lastError: AudioSessionFailure = AudioSessionFailure(),
)

data class AudioReceiverSessionSnapshot(
    val state: AudioSessionState = AudioSessionState.Idle,
    val source: io.warpnect.audio.capture.AudioCaptureSource? = null,
    val activeConfigGeneration: Long = 0,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val frameDurationUs: Int = 0,
    val samplesPerFrame: Int = 0,
    val framesBeforeConfigDropped: Long = 0,
    val framesReceived: Long = 0,
    val framesDecoded: Long = 0,
    val lateFramesDropped: Long = 0,
    val duplicateFramesDropped: Long = 0,
    val gapEvents: Long = 0,
    val plcFramesGenerated: Long = 0,
    val largeGapResets: Long = 0,
    val misalignedGapResets: Long = 0,
    val playbackRingFullDrops: Long = 0,
    val receiver: AudioReceiverRuntimeSnapshot? = null,
    val decoder: AudioDecoderSnapshot? = null,
    val playback: AudioPlaybackSnapshot? = null,
    val lastReceivedFramePosition: Long = 0,
    val expectedFramePosition: Long = 0,
    val lastCaptureTimeUs: Long = 0,
    val lastError: AudioSessionFailure = AudioSessionFailure(),
)

internal fun AudioReceiverStreamConfig.toDecoderConfig(): AudioDecoderConfig = AudioDecoderConfig(
    source = source,
    codec = AudioCodec.Opus,
    configGeneration = configGeneration,
    sampleRateHz = sampleRateHz,
    channelCount = channelCount,
    frameDurationUs = frameDurationUs,
    lookaheadSamples = lookaheadSamples,
)

internal fun AudioReceiverStreamConfig.toPlaybackConfig(
    ringCapacityCodecFrames: Int,
    startThresholdCodecFrames: Int,
    requestedBufferBursts: Int,
): AudioPlaybackConfig = AudioPlaybackConfig(
    source = source,
    configGeneration = configGeneration,
    sampleRateHz = sampleRateHz,
    channelCount = channelCount,
    frameDurationUs = frameDurationUs,
    framesPerCodecFrame = samplesPerFrame(),
    ringCapacityCodecFrames = ringCapacityCodecFrames,
    startThresholdCodecFrames = startThresholdCodecFrames,
    requestedBufferBursts = requestedBufferBursts,
)

internal fun AudioReceiverStreamConfig.samplesPerFrame(): Int =
    ((sampleRateHz.toLong() * frameDurationUs.toLong()) / MICROS_PER_SECOND).toInt()

private const val MICROS_PER_SECOND = 1_000_000L

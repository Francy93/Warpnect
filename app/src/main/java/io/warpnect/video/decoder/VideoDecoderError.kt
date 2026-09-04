package io.warpnect.video.decoder

enum class VideoDecoderError {
    None,

    UnsupportedCodec,
    HardwareDecoderUnavailable,
    HardwareClassificationUnavailable,
    LegacyQualificationRequired,
    LegacyQualificationFailed,
    LegacyQualificationInconclusive,

    UnsupportedDimensions,
    UnsupportedFrameRate,
    InvalidConfiguration,
    InvalidConfigGeneration,
    MissingCodecSpecificData,

    InvalidTargetSurface,

    CodecCreationFailed,
    CodecConfigurationFailed,
    CodecStartFailed,

    InputBufferUnavailable,
    InputBufferTooSmall,
    InvalidInputResult,
    ReconfigurationRequired,

    OutputReleaseFailed,
    OutputSinkFailure,

    EndOfStreamFailed,
    DrainTimeout,

    CodecStopFailed,
    CodecReleaseFailed,
    CodecRuntimeError,

    AlreadyPrepared,
    AlreadyRunning,
    NotPrepared,
    NotRunning,
    Closed,
}

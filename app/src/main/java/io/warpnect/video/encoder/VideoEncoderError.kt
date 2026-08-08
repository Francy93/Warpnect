package io.warpnect.video.encoder

enum class VideoEncoderError {
    None,

    UnsupportedCodec,
    HardwareEncoderUnavailable,
    HardwareClassificationUnavailable,

    UnsupportedDimensions,
    UnsupportedFrameRate,
    UnsupportedBitrate,
    UnsupportedBitrateMode,
    SurfaceInputUnsupported,
    InvalidRequest,

    CodecCreationFailed,
    CodecConfigurationFailed,
    InputSurfaceCreationFailed,
    CodecStartFailed,

    UnexpectedOutputReordering,
    OutputBufferUnavailable,
    OutputFormatInvalid,
    CodecSpecificDataInvalid,

    AlreadyPrepared,
    AlreadyRunning,
    NotPrepared,
    NotRunning,

    KeyFrameRequestFailed,
    BitrateUpdateFailed,
    ReconfigurationRequired,

    EndOfStreamFailed,
    DrainTimeout,
    CodecStopFailed,
    CodecReleaseFailed,

    CodecRuntimeError,
    SinkFailure,
}

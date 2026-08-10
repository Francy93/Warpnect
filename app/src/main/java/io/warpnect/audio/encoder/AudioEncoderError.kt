package io.warpnect.audio.encoder

enum class AudioEncoderError(
    val code: Int,
) {
    None(code = 0),
    UnsupportedCodec(code = 1),
    UnsupportedSampleRate(code = 2),
    UnsupportedChannelCount(code = 3),
    UnsupportedFrameDuration(code = 4),
    InvalidBitrate(code = 5),
    InvalidComplexity(code = 6),
    InvalidPcmRange(code = 7),
    NonDirectPcmBuffer(code = 8),
    DependencyUnavailable(code = 9),
    EncoderCreateFailed(code = 10),
    EncoderConfigureFailed(code = 11),
    EncoderEncodeFailed(code = 12),
    EncoderControlFailed(code = 13),
    PcmDiscontinuity(code = 14),
    OutputSinkFailure(code = 15),
    AlreadyPrepared(code = 16),
    AlreadyRunning(code = 17),
    NotPrepared(code = 18),
    NotRunning(code = 19),
    Closed(code = 20),
}

fun audioEncoderErrorFromCode(code: Int): AudioEncoderError =
    AudioEncoderError.entries.firstOrNull { it.code == code } ?: AudioEncoderError.EncoderEncodeFailed

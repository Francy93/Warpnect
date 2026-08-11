package io.warpnect.audio.decoder

enum class AudioDecoderError {
    None,
    UnsupportedCodec,
    UnsupportedSampleRate,
    UnsupportedChannelCount,
    UnsupportedFrameDuration,
    InvalidConfigGeneration,
    ReconfigurationRequired,
    NonDirectBuffer,
    InvalidBufferRange,
    EncodedPacketEmpty,
    EncodedPacketTooLarge,
    MalformedOpusPacket,
    UnexpectedPacketDuration,
    UnexpectedDecodedFrameSize,
    DecoderCreateFailed,
    DecoderDecodeFailed,
    DecoderControlFailed,
    InvalidMissingFrameMetadata,
    PacketLossConcealmentFailed,
    OutputSinkFailure,
    AlreadyPrepared,
    AlreadyRunning,
    NotPrepared,
    NotRunning,
    Closed,
    ;

    companion object {
        fun fromNativeCode(code: Int): AudioDecoderError = entries.getOrElse(code) { DecoderDecodeFailed }
    }
}

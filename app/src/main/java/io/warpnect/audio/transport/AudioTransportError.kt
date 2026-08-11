package io.warpnect.audio.transport

enum class AudioTransportError {
    None,
    UnsupportedAudioVersion,
    UnsupportedAudioMessageType,
    UnsupportedAudioCodec,
    InvalidAudioFlags,
    InvalidTimestampQuality,
    InvalidConfigGeneration,
    InvalidSampleRate,
    InvalidChannelCount,
    InvalidFrameDuration,
    InvalidLookahead,
    AudioConfigRequired,
    InvalidFramePosition,
    InvalidCaptureTimestamp,
    EncodedPacketEmpty,
    MalformedAudioPayload,
    PayloadTooLarge,
    NonDirectBuffer,
    InvalidBufferRange,
    InvalidDatagramBudget,
    TooManyFragments,
    PacketEncodeFailed,
    UdpOpenFailed,
    UdpBindFailed,
    UdpSendFailed,
    WouldBlock,
    PartialEmission,
    Closed,
    InvalidHandle,
    ;

    companion object {
        fun fromNativeCode(code: Int): AudioTransportError = entries.getOrElse(code) { InvalidHandle }
    }
}

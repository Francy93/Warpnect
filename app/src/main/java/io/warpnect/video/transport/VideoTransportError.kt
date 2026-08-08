package io.warpnect.video.transport

enum class VideoTransportError {
    None,

    UnsupportedVideoVersion,
    UnsupportedVideoMessageType,
    UnsupportedVideoCodec,
    InvalidVideoFlags,

    InvalidConfigGeneration,
    InvalidFrameId,
    InvalidPresentationTimestamp,

    InvalidDimensions,
    InvalidCsdCount,
    MalformedCsd,
    MalformedVideoPayload,

    AccessUnitEmpty,
    VideoConfigRequired,

    PayloadTooLarge,
    TooManyFragments,
    InvalidDatagramBudget,

    NonDirectBuffer,
    InvalidBufferRange,

    PacketEncodeFailed,
    UdpOpenFailed,
    UdpBindFailed,
    UdpSendFailed,
    WouldBlock,
    PartialEmission,

    RetransmissionCacheFailed,
    NackDecodeFailed,
    RetransmissionFailed,

    FecConfigurationInvalid,
    FecEncodingFailed,

    Closed,
    InvalidHandle,
    ;

    companion object {
        fun fromNativeCode(code: Int): VideoTransportError = entries.getOrElse(code) { InvalidHandle }
    }
}

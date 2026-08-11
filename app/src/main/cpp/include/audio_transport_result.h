#ifndef WARPNECT_SCL_AUDIO_TRANSPORT_RESULT_H_
#define WARPNECT_SCL_AUDIO_TRANSPORT_RESULT_H_

#include <cstddef>
#include <cstdint>
#include <string_view>

namespace warpnect::scl {

enum class AudioTransportError : std::uint8_t {
    None = 0,
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
};

[[nodiscard]] constexpr std::string_view
audio_transport_error_name(AudioTransportError error) noexcept {
    switch (error) {
    case AudioTransportError::None:
        return "None";
    case AudioTransportError::UnsupportedAudioVersion:
        return "UnsupportedAudioVersion";
    case AudioTransportError::UnsupportedAudioMessageType:
        return "UnsupportedAudioMessageType";
    case AudioTransportError::UnsupportedAudioCodec:
        return "UnsupportedAudioCodec";
    case AudioTransportError::InvalidAudioFlags:
        return "InvalidAudioFlags";
    case AudioTransportError::InvalidTimestampQuality:
        return "InvalidTimestampQuality";
    case AudioTransportError::InvalidConfigGeneration:
        return "InvalidConfigGeneration";
    case AudioTransportError::InvalidSampleRate:
        return "InvalidSampleRate";
    case AudioTransportError::InvalidChannelCount:
        return "InvalidChannelCount";
    case AudioTransportError::InvalidFrameDuration:
        return "InvalidFrameDuration";
    case AudioTransportError::InvalidLookahead:
        return "InvalidLookahead";
    case AudioTransportError::AudioConfigRequired:
        return "AudioConfigRequired";
    case AudioTransportError::InvalidFramePosition:
        return "InvalidFramePosition";
    case AudioTransportError::InvalidCaptureTimestamp:
        return "InvalidCaptureTimestamp";
    case AudioTransportError::EncodedPacketEmpty:
        return "EncodedPacketEmpty";
    case AudioTransportError::MalformedAudioPayload:
        return "MalformedAudioPayload";
    case AudioTransportError::PayloadTooLarge:
        return "PayloadTooLarge";
    case AudioTransportError::NonDirectBuffer:
        return "NonDirectBuffer";
    case AudioTransportError::InvalidBufferRange:
        return "InvalidBufferRange";
    case AudioTransportError::InvalidDatagramBudget:
        return "InvalidDatagramBudget";
    case AudioTransportError::TooManyFragments:
        return "TooManyFragments";
    case AudioTransportError::PacketEncodeFailed:
        return "PacketEncodeFailed";
    case AudioTransportError::UdpOpenFailed:
        return "UdpOpenFailed";
    case AudioTransportError::UdpBindFailed:
        return "UdpBindFailed";
    case AudioTransportError::UdpSendFailed:
        return "UdpSendFailed";
    case AudioTransportError::WouldBlock:
        return "WouldBlock";
    case AudioTransportError::PartialEmission:
        return "PartialEmission";
    case AudioTransportError::Closed:
        return "Closed";
    case AudioTransportError::InvalidHandle:
        return "InvalidHandle";
    }

    return "UnknownAudioTransportError";
}

struct [[nodiscard]] AudioTransportStatus final {
    AudioTransportError error = AudioTransportError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == AudioTransportError::None;
    }
};

struct [[nodiscard]] AudioTransportSizeResult final {
    AudioTransportError error = AudioTransportError::None;
    std::size_t size = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == AudioTransportError::None;
    }
};

struct [[nodiscard]] AudioTransportEncodeResult final {
    AudioTransportError error = AudioTransportError::None;
    std::size_t bytes_written = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == AudioTransportError::None;
    }
};

struct [[nodiscard]] PacketizedAudioResult final {
    AudioTransportError error = AudioTransportError::None;
    std::uint16_t datagrams_emitted = 0;
    std::size_t bytes_emitted = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == AudioTransportError::None;
    }
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_AUDIO_TRANSPORT_RESULT_H_

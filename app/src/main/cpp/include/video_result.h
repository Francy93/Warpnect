#ifndef WARPNECT_SCL_VIDEO_RESULT_H_
#define WARPNECT_SCL_VIDEO_RESULT_H_

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace warpnect::scl {

enum class VideoError : std::uint8_t {
    None = 0,
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
    ReassemblyWindowFull,
    ReadyWindowFull,
    ReassemblyTimeout,
    Discontinuity,
    NoData,
    InputBufferTooSmall,
    Closed,
    InvalidHandle,
};

[[nodiscard]] constexpr std::string_view video_error_name(VideoError error) noexcept {
    switch (error) {
    case VideoError::None:
        return "None";
    case VideoError::UnsupportedVideoVersion:
        return "UnsupportedVideoVersion";
    case VideoError::UnsupportedVideoMessageType:
        return "UnsupportedVideoMessageType";
    case VideoError::UnsupportedVideoCodec:
        return "UnsupportedVideoCodec";
    case VideoError::InvalidVideoFlags:
        return "InvalidVideoFlags";
    case VideoError::InvalidConfigGeneration:
        return "InvalidConfigGeneration";
    case VideoError::InvalidFrameId:
        return "InvalidFrameId";
    case VideoError::InvalidPresentationTimestamp:
        return "InvalidPresentationTimestamp";
    case VideoError::InvalidDimensions:
        return "InvalidDimensions";
    case VideoError::InvalidCsdCount:
        return "InvalidCsdCount";
    case VideoError::MalformedCsd:
        return "MalformedCsd";
    case VideoError::MalformedVideoPayload:
        return "MalformedVideoPayload";
    case VideoError::AccessUnitEmpty:
        return "AccessUnitEmpty";
    case VideoError::VideoConfigRequired:
        return "VideoConfigRequired";
    case VideoError::PayloadTooLarge:
        return "PayloadTooLarge";
    case VideoError::TooManyFragments:
        return "TooManyFragments";
    case VideoError::InvalidDatagramBudget:
        return "InvalidDatagramBudget";
    case VideoError::NonDirectBuffer:
        return "NonDirectBuffer";
    case VideoError::InvalidBufferRange:
        return "InvalidBufferRange";
    case VideoError::PacketEncodeFailed:
        return "PacketEncodeFailed";
    case VideoError::UdpOpenFailed:
        return "UdpOpenFailed";
    case VideoError::UdpBindFailed:
        return "UdpBindFailed";
    case VideoError::UdpSendFailed:
        return "UdpSendFailed";
    case VideoError::WouldBlock:
        return "WouldBlock";
    case VideoError::PartialEmission:
        return "PartialEmission";
    case VideoError::RetransmissionCacheFailed:
        return "RetransmissionCacheFailed";
    case VideoError::NackDecodeFailed:
        return "NackDecodeFailed";
    case VideoError::RetransmissionFailed:
        return "RetransmissionFailed";
    case VideoError::FecConfigurationInvalid:
        return "FecConfigurationInvalid";
    case VideoError::FecEncodingFailed:
        return "FecEncodingFailed";
    case VideoError::ReassemblyWindowFull:
        return "ReassemblyWindowFull";
    case VideoError::ReadyWindowFull:
        return "ReadyWindowFull";
    case VideoError::ReassemblyTimeout:
        return "ReassemblyTimeout";
    case VideoError::Discontinuity:
        return "Discontinuity";
    case VideoError::NoData:
        return "NoData";
    case VideoError::InputBufferTooSmall:
        return "InputBufferTooSmall";
    case VideoError::Closed:
        return "Closed";
    case VideoError::InvalidHandle:
        return "InvalidHandle";
    }

    return "UnknownVideoError";
}

struct [[nodiscard]] VideoStatus final {
    VideoError error = VideoError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == VideoError::None;
    }
};

struct [[nodiscard]] VideoSizeResult final {
    VideoError error = VideoError::None;
    std::size_t size = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == VideoError::None;
    }
};

struct [[nodiscard]] VideoEncodeResult final {
    VideoError error = VideoError::None;
    std::size_t bytes_written = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == VideoError::None;
    }
};

struct [[nodiscard]] PacketizedVideoResult final {
    VideoError error = VideoError::None;
    std::uint16_t datagrams_emitted = 0;
    std::size_t bytes_emitted = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == VideoError::None;
    }
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_VIDEO_RESULT_H_

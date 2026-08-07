#ifndef WARPNECT_SCL_PACKET_RESULT_H_
#define WARPNECT_SCL_PACKET_RESULT_H_

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

#include "protocol.h"

namespace warpnect::scl {

enum class PacketError : std::uint8_t {
    None = 0,
    BufferTooSmall,
    OutputBufferTooSmall,
    UnsupportedProtocolVersion,
    ReservedPayloadType,
    UnsupportedPayloadType,
    InvalidSliceCount,
    InvalidSliceIndex,
    SizeOverflow,
};

[[nodiscard]] constexpr std::string_view packet_error_name(PacketError error) noexcept {
    switch (error) {
    case PacketError::None:
        return "None";
    case PacketError::BufferTooSmall:
        return "BufferTooSmall";
    case PacketError::OutputBufferTooSmall:
        return "OutputBufferTooSmall";
    case PacketError::UnsupportedProtocolVersion:
        return "UnsupportedProtocolVersion";
    case PacketError::ReservedPayloadType:
        return "ReservedPayloadType";
    case PacketError::UnsupportedPayloadType:
        return "UnsupportedPayloadType";
    case PacketError::InvalidSliceCount:
        return "InvalidSliceCount";
    case PacketError::InvalidSliceIndex:
        return "InvalidSliceIndex";
    case PacketError::SizeOverflow:
        return "SizeOverflow";
    }

    return "UnknownPacketError";
}

struct [[nodiscard]] PacketStatus final {
    PacketError error = PacketError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == PacketError::None;
    }
};

struct [[nodiscard]] PacketEncodeResult final {
    PacketError error = PacketError::None;
    std::size_t bytes_written = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == PacketError::None;
    }
};

struct [[nodiscard]] PacketDecodeResult final {
    PacketError error = PacketError::None;
    PacketHeader header{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == PacketError::None;
    }
};

struct PacketView final {
    PacketHeader header{};
    std::span<const std::byte> payload{};
};

struct [[nodiscard]] PacketViewResult final {
    PacketError error = PacketError::None;
    PacketView packet{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == PacketError::None;
    }
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_PACKET_RESULT_H_

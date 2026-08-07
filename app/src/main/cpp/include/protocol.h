#ifndef WARPNECT_SCL_PROTOCOL_H_
#define WARPNECT_SCL_PROTOCOL_H_

#include <array>
#include <cstddef>
#include <cstdint>

namespace warpnect::scl {

inline constexpr std::uint16_t kSclProtocolVersion = 1;

inline constexpr std::size_t kPacketHeaderWireSize = 21;

inline constexpr std::size_t kPacketHeaderProtocolVersionOffset = 0;
inline constexpr std::size_t kPacketHeaderFlagsOffset = 2;
inline constexpr std::size_t kPacketHeaderSequenceNumberOffset = 4;
inline constexpr std::size_t kPacketHeaderTimestampUsOffset = 8;
inline constexpr std::size_t kPacketHeaderPayloadTypeOffset = 16;
inline constexpr std::size_t kPacketHeaderSliceIndexOffset = 17;
inline constexpr std::size_t kPacketHeaderTotalSlicesOffset = 19;

inline constexpr std::size_t kPacketHeaderProtocolVersionSize = 2;
inline constexpr std::size_t kPacketHeaderFlagsSize = 2;
inline constexpr std::size_t kPacketHeaderSequenceNumberSize = 4;
inline constexpr std::size_t kPacketHeaderTimestampUsSize = 8;
inline constexpr std::size_t kPacketHeaderPayloadTypeSize = 1;
inline constexpr std::size_t kPacketHeaderSliceIndexSize = 2;
inline constexpr std::size_t kPacketHeaderTotalSlicesSize = 2;

enum class PayloadType : std::uint8_t {
    Unknown = 0,
    Video = 1,
    SystemAudio = 2,
    MicrophoneAudio = 3,
    Input = 4,
    Telemetry = 5,
    SessionControl = 6,
    Handshake = 7,
};

using PacketHeaderWireBytes = std::array<std::byte, kPacketHeaderWireSize>;

struct PacketHeader final {
    std::uint16_t protocol_version = kSclProtocolVersion;
    std::uint16_t flags = 0;
    std::uint32_t sequence_number = 0;
    std::uint64_t timestamp_us = 0;
    PayloadType payload_type = PayloadType::Unknown;
    std::uint16_t slice_index = 0;
    std::uint16_t total_slices = 1;

    constexpr bool operator==(const PacketHeader&) const = default;
};

[[nodiscard]] constexpr bool payload_type_is_defined(PayloadType payload_type) noexcept {
    switch (payload_type) {
    case PayloadType::Unknown:
    case PayloadType::Video:
    case PayloadType::SystemAudio:
    case PayloadType::MicrophoneAudio:
    case PayloadType::Input:
    case PayloadType::Telemetry:
    case PayloadType::SessionControl:
    case PayloadType::Handshake:
        return true;
    }

    return false;
}

[[nodiscard]] constexpr bool payload_type_is_valid(PayloadType payload_type) noexcept {
    return payload_type_is_defined(payload_type) && payload_type != PayloadType::Unknown;
}

static_assert(kPacketHeaderProtocolVersionOffset == 0);
static_assert(kPacketHeaderFlagsOffset == 2);
static_assert(kPacketHeaderSequenceNumberOffset == 4);
static_assert(kPacketHeaderTimestampUsOffset == 8);
static_assert(kPacketHeaderPayloadTypeOffset == 16);
static_assert(kPacketHeaderSliceIndexOffset == 17);
static_assert(kPacketHeaderTotalSlicesOffset == 19);
static_assert(kPacketHeaderTotalSlicesOffset + kPacketHeaderTotalSlicesSize ==
              kPacketHeaderWireSize);

static_assert(static_cast<std::uint8_t>(PayloadType::Unknown) == 0);
static_assert(static_cast<std::uint8_t>(PayloadType::Video) == 1);
static_assert(static_cast<std::uint8_t>(PayloadType::SystemAudio) == 2);
static_assert(static_cast<std::uint8_t>(PayloadType::MicrophoneAudio) == 3);
static_assert(static_cast<std::uint8_t>(PayloadType::Input) == 4);
static_assert(static_cast<std::uint8_t>(PayloadType::Telemetry) == 5);
static_assert(static_cast<std::uint8_t>(PayloadType::SessionControl) == 6);
static_assert(static_cast<std::uint8_t>(PayloadType::Handshake) == 7);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_PROTOCOL_H_

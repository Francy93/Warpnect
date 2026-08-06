#ifndef WARPNECT_SCL_PROTOCOL_H_
#define WARPNECT_SCL_PROTOCOL_H_

#include <cstdint>

namespace warpnect::scl {

inline constexpr std::uint16_t kSclProtocolVersion = 1;

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

#pragma pack(push, 1)
struct PacketHeader final {
    std::uint16_t protocol_version;
    std::uint16_t flags;
    std::uint32_t sequence_number;
    std::uint64_t timestamp_us;
    PayloadType payload_type;
    std::uint16_t slice_index;
    std::uint16_t total_slices;
};
#pragma pack(pop)

static_assert(sizeof(PacketHeader) == 21, "SCL packet header must remain packed.");

}  // namespace warpnect::scl

#endif  // WARPNECT_SCL_PROTOCOL_H_

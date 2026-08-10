#ifndef WARPNECT_SCL_RECOVERY_CONTROL_H_
#define WARPNECT_SCL_RECOVERY_CONTROL_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "protocol.h"
#include "recovery_result.h"
#include "sequence_number.h"

namespace warpnect::scl {

inline constexpr std::uint8_t kNackControlVersion = 1;
inline constexpr std::size_t kNackPayloadWireSize = 16;

inline constexpr std::size_t kNackPayloadControlTypeOffset = 0;
inline constexpr std::size_t kNackPayloadControlVersionOffset = 1;
inline constexpr std::size_t kNackPayloadTargetPayloadTypeOffset = 2;
inline constexpr std::size_t kNackPayloadReservedOffset = 3;
inline constexpr std::size_t kNackPayloadBaseSequenceNumberOffset = 4;
inline constexpr std::size_t kNackPayloadMissingBitmapOffset = 8;

enum class SessionControlType : std::uint8_t {
    Unknown = 0,
    Nack = 1,
    FecParity = 2,
    ClockSyncRequest = 3,
    ClockSyncResponse = 4,
    VideoResyncRequest = 5,
};

struct NackRequest final {
    PayloadType target_payload_type = PayloadType::Unknown;
    std::uint32_t base_sequence_number = 0;
    std::uint64_t missing_bitmap = 0;

    constexpr bool operator==(const NackRequest&) const = default;
};

struct [[nodiscard]] NackDecodeResult final {
    RecoveryError error = RecoveryError::None;
    NackRequest request{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

struct [[nodiscard]] NackSequenceResult final {
    RecoveryError error = RecoveryError::None;
    std::uint32_t sequence_number = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

class NackSequenceCursor final {
  public:
    constexpr NackSequenceCursor() noexcept = default;
    explicit constexpr NackSequenceCursor(const NackRequest& request) noexcept
        : request_(request) {}

    [[nodiscard]] bool has_next() const noexcept;
    [[nodiscard]] NackSequenceResult next() noexcept;

  private:
    NackRequest request_{};
    std::uint8_t next_bit_ = 0;
};

[[nodiscard]] RecoveryStatus validate_nack_request(const NackRequest& request) noexcept;

[[nodiscard]] RecoveryStatus encode_nack(const NackRequest& request,
                                         std::span<std::byte> output) noexcept;

[[nodiscard]] NackDecodeResult decode_nack(std::span<const std::byte> input) noexcept;

static_assert(kNackPayloadControlTypeOffset == 0);
static_assert(kNackPayloadControlVersionOffset == 1);
static_assert(kNackPayloadTargetPayloadTypeOffset == 2);
static_assert(kNackPayloadReservedOffset == 3);
static_assert(kNackPayloadBaseSequenceNumberOffset == 4);
static_assert(kNackPayloadMissingBitmapOffset == 8);
static_assert(kNackPayloadMissingBitmapOffset + 8 == kNackPayloadWireSize);
static_assert(static_cast<std::uint8_t>(SessionControlType::Unknown) == 0);
static_assert(static_cast<std::uint8_t>(SessionControlType::Nack) == 1);
static_assert(static_cast<std::uint8_t>(SessionControlType::FecParity) == 2);
static_assert(static_cast<std::uint8_t>(SessionControlType::ClockSyncRequest) == 3);
static_assert(static_cast<std::uint8_t>(SessionControlType::ClockSyncResponse) == 4);
static_assert(static_cast<std::uint8_t>(SessionControlType::VideoResyncRequest) == 5);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_RECOVERY_CONTROL_H_

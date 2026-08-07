#ifndef WARPNECT_SCL_CLOCK_SYNC_CONTROL_H_
#define WARPNECT_SCL_CLOCK_SYNC_CONTROL_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "recovery_control.h"
#include "timing_result.h"

namespace warpnect::scl {

inline constexpr std::uint8_t kClockSyncControlVersion = 1;

inline constexpr std::size_t kClockSyncRequestWireSize = 16;
inline constexpr std::size_t kClockSyncResponseWireSize = 32;

inline constexpr std::size_t kClockSyncControlTypeOffset = 0;
inline constexpr std::size_t kClockSyncControlVersionOffset = 1;
inline constexpr std::size_t kClockSyncReservedOffset = 2;
inline constexpr std::size_t kClockSyncExchangeIdOffset = 4;
inline constexpr std::size_t kClockSyncT0Offset = 8;
inline constexpr std::size_t kClockSyncT1Offset = 16;
inline constexpr std::size_t kClockSyncT2Offset = 24;

struct ClockSyncRequest final {
    std::uint32_t exchange_id = 0;
    std::uint64_t t0_us = 0;

    constexpr bool operator==(const ClockSyncRequest&) const = default;
};

struct ClockSyncResponse final {
    std::uint32_t exchange_id = 0;
    std::uint64_t t0_us = 0;
    std::uint64_t t1_us = 0;
    std::uint64_t t2_us = 0;

    constexpr bool operator==(const ClockSyncResponse&) const = default;
};

struct [[nodiscard]] ClockSyncRequestDecodeResult final {
    TimingError error = TimingError::None;
    ClockSyncRequest request{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == TimingError::None;
    }
};

struct [[nodiscard]] ClockSyncResponseDecodeResult final {
    TimingError error = TimingError::None;
    ClockSyncResponse response{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == TimingError::None;
    }
};

[[nodiscard]] TimingStatus encode_clock_sync_request(const ClockSyncRequest& request,
                                                     std::span<std::byte> output) noexcept;

[[nodiscard]] ClockSyncRequestDecodeResult
decode_clock_sync_request(std::span<const std::byte> input) noexcept;

[[nodiscard]] TimingStatus encode_clock_sync_response(const ClockSyncResponse& response,
                                                      std::span<std::byte> output) noexcept;

[[nodiscard]] ClockSyncResponseDecodeResult
decode_clock_sync_response(std::span<const std::byte> input) noexcept;

static_assert(kClockSyncControlTypeOffset == 0);
static_assert(kClockSyncControlVersionOffset == 1);
static_assert(kClockSyncReservedOffset == 2);
static_assert(kClockSyncExchangeIdOffset == 4);
static_assert(kClockSyncT0Offset == 8);
static_assert(kClockSyncT1Offset == 16);
static_assert(kClockSyncT2Offset == 24);
static_assert(kClockSyncT0Offset + 8 == kClockSyncRequestWireSize);
static_assert(kClockSyncT2Offset + 8 == kClockSyncResponseWireSize);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_CLOCK_SYNC_CONTROL_H_

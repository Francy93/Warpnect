#include "clock_sync_control.h"

#include "internal/byte_order.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr TimingStatus status(TimingError error) noexcept {
    return TimingStatus{.error = error};
}

[[nodiscard]] constexpr ClockSyncRequestDecodeResult request_error(TimingError error) noexcept {
    return ClockSyncRequestDecodeResult{.error = error};
}

[[nodiscard]] constexpr ClockSyncResponseDecodeResult response_error(TimingError error) noexcept {
    return ClockSyncResponseDecodeResult{.error = error};
}

[[nodiscard]] TimingError validate_common(std::span<const std::byte> input,
                                          std::size_t expected_size,
                                          SessionControlType expected_type) noexcept {
    if (input.size() != expected_size) {
        return TimingError::InvalidControlPayload;
    }

    const auto control_type = static_cast<SessionControlType>(
        static_cast<std::uint8_t>(input[kClockSyncControlTypeOffset]));
    if (control_type != expected_type) {
        return TimingError::UnsupportedControlType;
    }

    const auto control_version = static_cast<std::uint8_t>(input[kClockSyncControlVersionOffset]);
    if (control_version != kClockSyncControlVersion) {
        return TimingError::UnsupportedControlVersion;
    }

    std::uint16_t reserved = 0;
    if (!internal::read_u16_be(input, kClockSyncReservedOffset, reserved)) {
        return TimingError::InvalidControlPayload;
    }
    if (reserved != 0) {
        return TimingError::ReservedFieldNonZero;
    }

    return TimingError::None;
}

} // namespace

TimingStatus encode_clock_sync_request(const ClockSyncRequest& request,
                                       std::span<std::byte> output) noexcept {
    if (output.size() < kClockSyncRequestWireSize) {
        return status(TimingError::OutputBufferTooSmall);
    }

    output[kClockSyncControlTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(SessionControlType::ClockSyncRequest));
    output[kClockSyncControlVersionOffset] = static_cast<std::byte>(kClockSyncControlVersion);
    const bool wrote =
        internal::write_u16_be(0, output, kClockSyncReservedOffset) &&
        internal::write_u32_be(request.exchange_id, output, kClockSyncExchangeIdOffset) &&
        internal::write_u64_be(request.t0_us, output, kClockSyncT0Offset);
    return status(wrote ? TimingError::None : TimingError::OutputBufferTooSmall);
}

ClockSyncRequestDecodeResult decode_clock_sync_request(std::span<const std::byte> input) noexcept {
    const TimingError common =
        validate_common(input, kClockSyncRequestWireSize, SessionControlType::ClockSyncRequest);
    if (common != TimingError::None) {
        return request_error(common);
    }

    ClockSyncRequest request{};
    const bool read =
        internal::read_u32_be(input, kClockSyncExchangeIdOffset, request.exchange_id) &&
        internal::read_u64_be(input, kClockSyncT0Offset, request.t0_us);
    if (!read) {
        return request_error(TimingError::InvalidControlPayload);
    }

    return ClockSyncRequestDecodeResult{.request = request};
}

TimingStatus encode_clock_sync_response(const ClockSyncResponse& response,
                                        std::span<std::byte> output) noexcept {
    if (response.t2_us < response.t1_us) {
        return status(TimingError::InvalidTimestampOrder);
    }

    if (output.size() < kClockSyncResponseWireSize) {
        return status(TimingError::OutputBufferTooSmall);
    }

    output[kClockSyncControlTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(SessionControlType::ClockSyncResponse));
    output[kClockSyncControlVersionOffset] = static_cast<std::byte>(kClockSyncControlVersion);
    const bool wrote =
        internal::write_u16_be(0, output, kClockSyncReservedOffset) &&
        internal::write_u32_be(response.exchange_id, output, kClockSyncExchangeIdOffset) &&
        internal::write_u64_be(response.t0_us, output, kClockSyncT0Offset) &&
        internal::write_u64_be(response.t1_us, output, kClockSyncT1Offset) &&
        internal::write_u64_be(response.t2_us, output, kClockSyncT2Offset);
    return status(wrote ? TimingError::None : TimingError::OutputBufferTooSmall);
}

ClockSyncResponseDecodeResult
decode_clock_sync_response(std::span<const std::byte> input) noexcept {
    const TimingError common =
        validate_common(input, kClockSyncResponseWireSize, SessionControlType::ClockSyncResponse);
    if (common != TimingError::None) {
        return response_error(common);
    }

    ClockSyncResponse response{};
    const bool read =
        internal::read_u32_be(input, kClockSyncExchangeIdOffset, response.exchange_id) &&
        internal::read_u64_be(input, kClockSyncT0Offset, response.t0_us) &&
        internal::read_u64_be(input, kClockSyncT1Offset, response.t1_us) &&
        internal::read_u64_be(input, kClockSyncT2Offset, response.t2_us);
    if (!read) {
        return response_error(TimingError::InvalidControlPayload);
    }

    if (response.t2_us < response.t1_us) {
        return response_error(TimingError::InvalidTimestampOrder);
    }

    return ClockSyncResponseDecodeResult{.response = response};
}

} // namespace warpnect::scl

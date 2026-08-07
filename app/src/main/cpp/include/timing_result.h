#ifndef WARPNECT_SCL_TIMING_RESULT_H_
#define WARPNECT_SCL_TIMING_RESULT_H_

#include <cstdint>
#include <string_view>

namespace warpnect::scl {

enum class TimingError : std::uint8_t {
    None = 0,
    InvalidControlPayload,
    OutputBufferTooSmall,
    UnsupportedControlType,
    UnsupportedControlVersion,
    ReservedFieldNonZero,
    InvalidTimestampOrder,
    InvalidConfiguration,
    TrackerFull,
    DuplicateExchange,
    UnknownExchange,
    ExchangeTimestampMismatch,
    InvalidSample,
    RttTooLarge,
    WindowStorageTooSmall,
    InsufficientSamples,
    ClockModelUnavailable,
    DriftLimitExceeded,
    StaleModel,
    NonFiniteModel,
    ConversionOutOfRange,
};

[[nodiscard]] constexpr std::string_view timing_error_name(TimingError error) noexcept {
    switch (error) {
    case TimingError::None:
        return "None";
    case TimingError::InvalidControlPayload:
        return "InvalidControlPayload";
    case TimingError::OutputBufferTooSmall:
        return "OutputBufferTooSmall";
    case TimingError::UnsupportedControlType:
        return "UnsupportedControlType";
    case TimingError::UnsupportedControlVersion:
        return "UnsupportedControlVersion";
    case TimingError::ReservedFieldNonZero:
        return "ReservedFieldNonZero";
    case TimingError::InvalidTimestampOrder:
        return "InvalidTimestampOrder";
    case TimingError::InvalidConfiguration:
        return "InvalidConfiguration";
    case TimingError::TrackerFull:
        return "TrackerFull";
    case TimingError::DuplicateExchange:
        return "DuplicateExchange";
    case TimingError::UnknownExchange:
        return "UnknownExchange";
    case TimingError::ExchangeTimestampMismatch:
        return "ExchangeTimestampMismatch";
    case TimingError::InvalidSample:
        return "InvalidSample";
    case TimingError::RttTooLarge:
        return "RttTooLarge";
    case TimingError::WindowStorageTooSmall:
        return "WindowStorageTooSmall";
    case TimingError::InsufficientSamples:
        return "InsufficientSamples";
    case TimingError::ClockModelUnavailable:
        return "ClockModelUnavailable";
    case TimingError::DriftLimitExceeded:
        return "DriftLimitExceeded";
    case TimingError::StaleModel:
        return "StaleModel";
    case TimingError::NonFiniteModel:
        return "NonFiniteModel";
    case TimingError::ConversionOutOfRange:
        return "ConversionOutOfRange";
    }

    return "UnknownTimingError";
}

struct [[nodiscard]] TimingStatus final {
    TimingError error = TimingError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == TimingError::None;
    }
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_TIMING_RESULT_H_

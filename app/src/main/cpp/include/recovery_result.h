#ifndef WARPNECT_SCL_RECOVERY_RESULT_H_
#define WARPNECT_SCL_RECOVERY_RESULT_H_

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

#include "protocol.h"

namespace warpnect::scl {

enum class RecoveryError : std::uint8_t {
    None = 0,
    InvalidConfiguration,
    InvalidWindowCapacity,
    WindowCapacityExceeded,
    AmbiguousSequenceDistance,
    SequenceNotForward,
    OutputBufferTooSmall,
    InvalidNackPayload,
    UnsupportedControlType,
    UnsupportedControlVersion,
    InvalidTargetPayloadType,
    EmptyNackBitmap,
    CacheStorageTooSmall,
    CacheDatagramTooLarge,
    NotCached,
    ConflictingCachedDatagram,
    SizeOverflow,
    NoMoreSequences,
};

[[nodiscard]] constexpr std::string_view recovery_error_name(RecoveryError error) noexcept {
    switch (error) {
    case RecoveryError::None:
        return "None";
    case RecoveryError::InvalidConfiguration:
        return "InvalidConfiguration";
    case RecoveryError::InvalidWindowCapacity:
        return "InvalidWindowCapacity";
    case RecoveryError::WindowCapacityExceeded:
        return "WindowCapacityExceeded";
    case RecoveryError::AmbiguousSequenceDistance:
        return "AmbiguousSequenceDistance";
    case RecoveryError::SequenceNotForward:
        return "SequenceNotForward";
    case RecoveryError::OutputBufferTooSmall:
        return "OutputBufferTooSmall";
    case RecoveryError::InvalidNackPayload:
        return "InvalidNackPayload";
    case RecoveryError::UnsupportedControlType:
        return "UnsupportedControlType";
    case RecoveryError::UnsupportedControlVersion:
        return "UnsupportedControlVersion";
    case RecoveryError::InvalidTargetPayloadType:
        return "InvalidTargetPayloadType";
    case RecoveryError::EmptyNackBitmap:
        return "EmptyNackBitmap";
    case RecoveryError::CacheStorageTooSmall:
        return "CacheStorageTooSmall";
    case RecoveryError::CacheDatagramTooLarge:
        return "CacheDatagramTooLarge";
    case RecoveryError::NotCached:
        return "NotCached";
    case RecoveryError::ConflictingCachedDatagram:
        return "ConflictingCachedDatagram";
    case RecoveryError::SizeOverflow:
        return "SizeOverflow";
    case RecoveryError::NoMoreSequences:
        return "NoMoreSequences";
    }

    return "UnknownRecoveryError";
}

struct [[nodiscard]] RecoveryStatus final {
    RecoveryError error = RecoveryError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

struct [[nodiscard]] RecoverySizeResult final {
    RecoveryError error = RecoveryError::None;
    std::size_t size = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

enum class SequenceOrder : std::uint8_t {
    Equal = 0,
    Newer,
    Older,
    Ambiguous,
};

struct [[nodiscard]] SequenceOrderResult final {
    RecoveryError error = RecoveryError::None;
    SequenceOrder order = SequenceOrder::Equal;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

struct [[nodiscard]] SequenceBoolResult final {
    RecoveryError error = RecoveryError::None;
    bool value = false;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

struct [[nodiscard]] SequenceDistanceResult final {
    RecoveryError error = RecoveryError::None;
    std::uint32_t distance = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

enum class LossObservationKind : std::uint8_t {
    None = 0,
    FirstPacket,
    InOrder,
    GapDetected,
    RecoveredMissing,
    Duplicate,
    TooOld,
};

enum class LossSlotState : std::uint8_t {
    Empty = 0,
    Received,
    Missing,
};

struct [[nodiscard]] LossObservationResult final {
    RecoveryError error = RecoveryError::None;
    LossObservationKind kind = LossObservationKind::None;
    std::uint32_t sequence_number = 0;
    std::size_t missing_created = 0;
    std::size_t missing_count = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

struct [[nodiscard]] LossSequenceStatusResult final {
    RecoveryError error = RecoveryError::None;
    LossSlotState state = LossSlotState::Empty;
    std::uint16_t nack_attempts = 0;
    std::uint64_t first_missing_us = 0;
    std::uint64_t last_nack_us = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

struct [[nodiscard]] NackCollectionResult final {
    RecoveryError error = RecoveryError::None;
    std::size_t requests_written = 0;
    bool output_exhausted = false;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

struct [[nodiscard]] RetransmissionLookupResult final {
    RecoveryError error = RecoveryError::None;
    std::span<const std::byte> datagram{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == RecoveryError::None;
    }
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_RECOVERY_RESULT_H_

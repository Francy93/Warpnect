#ifndef WARPNECT_SCL_FEC_RESULT_H_
#define WARPNECT_SCL_FEC_RESULT_H_

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

#include "protocol.h"

namespace warpnect::scl {

enum class FecError : std::uint8_t {
    None = 0,
    InvalidConfiguration,
    TooManyShards,
    InvalidShardSize,
    SizeOverflow,
    WorkspaceTooSmall,
    StorageTooSmall,
    OutputBufferTooSmall,
    InvalidShardCount,
    ShardSizeMismatch,
    SingularMatrix,
    InsufficientShards,
    InvalidDataDatagram,
    DatagramTooLarge,
    SequenceMismatch,
    PayloadTypeMismatch,
    DuplicateShard,
    ConflictingShard,
    InvalidParityPayload,
    UnsupportedControlType,
    UnsupportedControlVersion,
    InvalidParityIndex,
    GroupMismatch,
    InvalidRecoveredLength,
    InvalidRecoveredDatagram,
    NoRecoveryNeeded,
    NoMoreParity,
    DatagramBudgetTooSmall,
    DatagramBudgetTooLarge,
};

[[nodiscard]] constexpr std::string_view fec_error_name(FecError error) noexcept {
    switch (error) {
    case FecError::None:
        return "None";
    case FecError::InvalidConfiguration:
        return "InvalidConfiguration";
    case FecError::TooManyShards:
        return "TooManyShards";
    case FecError::InvalidShardSize:
        return "InvalidShardSize";
    case FecError::SizeOverflow:
        return "SizeOverflow";
    case FecError::WorkspaceTooSmall:
        return "WorkspaceTooSmall";
    case FecError::StorageTooSmall:
        return "StorageTooSmall";
    case FecError::OutputBufferTooSmall:
        return "OutputBufferTooSmall";
    case FecError::InvalidShardCount:
        return "InvalidShardCount";
    case FecError::ShardSizeMismatch:
        return "ShardSizeMismatch";
    case FecError::SingularMatrix:
        return "SingularMatrix";
    case FecError::InsufficientShards:
        return "InsufficientShards";
    case FecError::InvalidDataDatagram:
        return "InvalidDataDatagram";
    case FecError::DatagramTooLarge:
        return "DatagramTooLarge";
    case FecError::SequenceMismatch:
        return "SequenceMismatch";
    case FecError::PayloadTypeMismatch:
        return "PayloadTypeMismatch";
    case FecError::DuplicateShard:
        return "DuplicateShard";
    case FecError::ConflictingShard:
        return "ConflictingShard";
    case FecError::InvalidParityPayload:
        return "InvalidParityPayload";
    case FecError::UnsupportedControlType:
        return "UnsupportedControlType";
    case FecError::UnsupportedControlVersion:
        return "UnsupportedControlVersion";
    case FecError::InvalidParityIndex:
        return "InvalidParityIndex";
    case FecError::GroupMismatch:
        return "GroupMismatch";
    case FecError::InvalidRecoveredLength:
        return "InvalidRecoveredLength";
    case FecError::InvalidRecoveredDatagram:
        return "InvalidRecoveredDatagram";
    case FecError::NoRecoveryNeeded:
        return "NoRecoveryNeeded";
    case FecError::NoMoreParity:
        return "NoMoreParity";
    case FecError::DatagramBudgetTooSmall:
        return "DatagramBudgetTooSmall";
    case FecError::DatagramBudgetTooLarge:
        return "DatagramBudgetTooLarge";
    }

    return "UnknownFecError";
}

struct [[nodiscard]] FecStatus final {
    FecError error = FecError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FecError::None;
    }
};

struct [[nodiscard]] FecSizeResult final {
    FecError error = FecError::None;
    std::size_t size = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FecError::None;
    }
};

struct [[nodiscard]] FecAcceptResult final {
    FecError error = FecError::None;
    std::uint8_t shard_index = 0;
    std::size_t present_shards = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FecError::None;
    }
};

struct RecoveredDatagramView final {
    std::uint32_t sequence_number = 0;
    std::span<const std::byte> datagram{};
};

struct [[nodiscard]] RecoveredDatagramResult final {
    FecError error = FecError::None;
    RecoveredDatagramView datagram{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FecError::None;
    }
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_FEC_RESULT_H_

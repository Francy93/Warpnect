#ifndef WARPNECT_SCL_FRAGMENT_RESULT_H_
#define WARPNECT_SCL_FRAGMENT_RESULT_H_

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

#include "packet_result.h"
#include "protocol.h"

namespace warpnect::scl {

enum class FragmentError : std::uint8_t {
    None = 0,
    InvalidConfiguration,
    DatagramBudgetTooSmall,
    DatagramBudgetTooLarge,
    InvalidInputHeader,
    AlreadyFragmented,
    PayloadTooLarge,
    TooManyFragments,
    SizeOverflow,
    InvalidFragment,
    FragmentSizeMismatch,
    FragmentGroupMismatch,
    MetadataStorageTooSmall,
    ReassemblyStorageTooSmall,
    DuplicateFragment,
    ConflictingFragment,
    ReassemblyNotStarted,
    ReassemblyIncomplete,
    ReassemblyAlreadyComplete,
    NoMoreFragments,
};

[[nodiscard]] constexpr std::string_view fragment_error_name(FragmentError error) noexcept {
    switch (error) {
    case FragmentError::None:
        return "None";
    case FragmentError::InvalidConfiguration:
        return "InvalidConfiguration";
    case FragmentError::DatagramBudgetTooSmall:
        return "DatagramBudgetTooSmall";
    case FragmentError::DatagramBudgetTooLarge:
        return "DatagramBudgetTooLarge";
    case FragmentError::InvalidInputHeader:
        return "InvalidInputHeader";
    case FragmentError::AlreadyFragmented:
        return "AlreadyFragmented";
    case FragmentError::PayloadTooLarge:
        return "PayloadTooLarge";
    case FragmentError::TooManyFragments:
        return "TooManyFragments";
    case FragmentError::SizeOverflow:
        return "SizeOverflow";
    case FragmentError::InvalidFragment:
        return "InvalidFragment";
    case FragmentError::FragmentSizeMismatch:
        return "FragmentSizeMismatch";
    case FragmentError::FragmentGroupMismatch:
        return "FragmentGroupMismatch";
    case FragmentError::MetadataStorageTooSmall:
        return "MetadataStorageTooSmall";
    case FragmentError::ReassemblyStorageTooSmall:
        return "ReassemblyStorageTooSmall";
    case FragmentError::DuplicateFragment:
        return "DuplicateFragment";
    case FragmentError::ConflictingFragment:
        return "ConflictingFragment";
    case FragmentError::ReassemblyNotStarted:
        return "ReassemblyNotStarted";
    case FragmentError::ReassemblyIncomplete:
        return "ReassemblyIncomplete";
    case FragmentError::ReassemblyAlreadyComplete:
        return "ReassemblyAlreadyComplete";
    case FragmentError::NoMoreFragments:
        return "NoMoreFragments";
    }

    return "UnknownFragmentError";
}

struct [[nodiscard]] FragmentStatus final {
    FragmentError error = FragmentError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FragmentError::None;
    }
};

struct [[nodiscard]] FragmentSizeResult final {
    FragmentError error = FragmentError::None;
    std::size_t size = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FragmentError::None;
    }
};

struct FragmentView final {
    PacketHeader header{};
    std::span<const std::byte> payload{};
};

struct [[nodiscard]] FragmentResult final {
    FragmentError error = FragmentError::None;
    FragmentView fragment{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FragmentError::None;
    }
};

struct FragmentGroupKey final {
    std::uint16_t protocol_version = kSclProtocolVersion;
    std::uint32_t base_sequence_number = 0;
    std::uint64_t timestamp_us = 0;
    PayloadType payload_type = PayloadType::Unknown;
    std::uint16_t flags = 0;
    std::uint16_t total_slices = 1;

    constexpr bool operator==(const FragmentGroupKey&) const = default;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_FRAGMENT_RESULT_H_

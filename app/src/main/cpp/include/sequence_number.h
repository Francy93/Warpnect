#ifndef WARPNECT_SCL_SEQUENCE_NUMBER_H_
#define WARPNECT_SCL_SEQUENCE_NUMBER_H_

#include <cstdint>

#include "recovery_result.h"

namespace warpnect::scl {

inline constexpr std::uint32_t kSequenceHalfRange = 0x80000000U;

[[nodiscard]] constexpr bool sequence_equal(std::uint32_t left, std::uint32_t right) noexcept {
    return left == right;
}

[[nodiscard]] constexpr SequenceOrderResult
compare_sequence_numbers(std::uint32_t candidate, std::uint32_t reference) noexcept {
    const std::uint32_t distance = candidate - reference;
    if (distance == 0U) {
        return SequenceOrderResult{.order = SequenceOrder::Equal};
    }

    if (distance == kSequenceHalfRange) {
        return SequenceOrderResult{
            .error = RecoveryError::AmbiguousSequenceDistance,
            .order = SequenceOrder::Ambiguous,
        };
    }

    if (distance < kSequenceHalfRange) {
        return SequenceOrderResult{.order = SequenceOrder::Newer};
    }

    return SequenceOrderResult{.order = SequenceOrder::Older};
}

[[nodiscard]] constexpr SequenceBoolResult sequence_newer(std::uint32_t candidate,
                                                          std::uint32_t reference) noexcept {
    const SequenceOrderResult comparison = compare_sequence_numbers(candidate, reference);
    if (!comparison.ok()) {
        return SequenceBoolResult{.error = comparison.error};
    }

    return SequenceBoolResult{.value = comparison.order == SequenceOrder::Newer};
}

[[nodiscard]] constexpr SequenceBoolResult sequence_older(std::uint32_t candidate,
                                                          std::uint32_t reference) noexcept {
    const SequenceOrderResult comparison = compare_sequence_numbers(candidate, reference);
    if (!comparison.ok()) {
        return SequenceBoolResult{.error = comparison.error};
    }

    return SequenceBoolResult{.value = comparison.order == SequenceOrder::Older};
}

[[nodiscard]] constexpr SequenceDistanceResult
forward_sequence_distance(std::uint32_t from, std::uint32_t to) noexcept {
    const SequenceOrderResult comparison = compare_sequence_numbers(to, from);
    if (!comparison.ok()) {
        return SequenceDistanceResult{.error = comparison.error};
    }

    if (comparison.order == SequenceOrder::Older) {
        return SequenceDistanceResult{.error = RecoveryError::SequenceNotForward};
    }

    return SequenceDistanceResult{.distance = to - from};
}

} // namespace warpnect::scl

#endif // WARPNECT_SCL_SEQUENCE_NUMBER_H_

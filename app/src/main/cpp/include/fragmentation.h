#ifndef WARPNECT_SCL_FRAGMENTATION_H_
#define WARPNECT_SCL_FRAGMENTATION_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "datagram_limits.h"
#include "fragment_result.h"
#include "packet_codec.h"

namespace warpnect::scl {

struct FragmentationConfig final {
    std::size_t max_datagram_size = 0;

    constexpr bool operator==(const FragmentationConfig&) const = default;
};

struct [[nodiscard]] FragmentCountResult final {
    FragmentError error = FragmentError::None;
    std::size_t fragment_payload_capacity = 0;
    std::uint16_t total_slices = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FragmentError::None;
    }
};

struct FragmentationPlan final {
    FragmentationConfig config{};
    PacketHeader logical_header{};
    std::span<const std::byte> payload{};
    std::size_t fragment_payload_capacity = 0;
    std::uint16_t total_slices = 0;
};

struct [[nodiscard]] FragmentationPlanResult final {
    FragmentError error = FragmentError::None;
    FragmentationPlan plan{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FragmentError::None;
    }
};

[[nodiscard]] constexpr std::uint32_t fragment_sequence_number(std::uint32_t base_sequence_number,
                                                               std::uint16_t slice_index) noexcept {
    return base_sequence_number + static_cast<std::uint32_t>(slice_index);
}

[[nodiscard]] constexpr std::uint32_t
fragment_base_sequence_number(std::uint32_t sequence_number, std::uint16_t slice_index) noexcept {
    return sequence_number - static_cast<std::uint32_t>(slice_index);
}

[[nodiscard]] FragmentStatus validate_fragmentation_config(FragmentationConfig config) noexcept;

[[nodiscard]] FragmentCountResult calculate_fragment_count(FragmentationConfig config,
                                                           std::size_t payload_size) noexcept;

[[nodiscard]] FragmentationPlanResult plan_fragments(FragmentationConfig config,
                                                     const PacketHeader& logical_header,
                                                     std::span<const std::byte> payload) noexcept;

class FragmentCursor final {
  public:
    constexpr FragmentCursor() noexcept = default;
    explicit constexpr FragmentCursor(const FragmentationPlan& plan) noexcept : plan_(plan) {}

    [[nodiscard]] bool has_next() const noexcept;
    [[nodiscard]] FragmentResult next() noexcept;

    [[nodiscard]] constexpr std::uint16_t next_slice_index() const noexcept {
        return next_slice_index_;
    }

    [[nodiscard]] constexpr std::uint16_t total_slices() const noexcept {
        return plan_.total_slices;
    }

    [[nodiscard]] constexpr std::size_t fragment_payload_capacity() const noexcept {
        return plan_.fragment_payload_capacity;
    }

  private:
    FragmentationPlan plan_{};
    std::uint16_t next_slice_index_ = 0;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_FRAGMENTATION_H_

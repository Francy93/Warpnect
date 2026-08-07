#include "fragmentation.h"

#include <limits>

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr FragmentStatus status(FragmentError error) noexcept {
    return FragmentStatus{.error = error};
}

[[nodiscard]] constexpr FragmentCountResult count_error(FragmentError error) noexcept {
    return FragmentCountResult{.error = error};
}

[[nodiscard]] constexpr FragmentationPlanResult plan_error(FragmentError error) noexcept {
    return FragmentationPlanResult{.error = error};
}

[[nodiscard]] constexpr FragmentError map_packet_error(PacketError error) noexcept {
    return error == PacketError::None ? FragmentError::None : FragmentError::InvalidInputHeader;
}

} // namespace

FragmentStatus validate_fragmentation_config(FragmentationConfig config) noexcept {
    if (config.max_datagram_size < kPacketHeaderWireSize) {
        return status(FragmentError::DatagramBudgetTooSmall);
    }

    if (config.max_datagram_size > kUdpMaxDatagramPayloadSize) {
        return status(FragmentError::DatagramBudgetTooLarge);
    }

    return status(FragmentError::None);
}

FragmentCountResult calculate_fragment_count(FragmentationConfig config,
                                             std::size_t payload_size) noexcept {
    const FragmentStatus config_status = validate_fragmentation_config(config);
    if (!config_status.ok()) {
        return count_error(config_status.error);
    }

    const std::size_t capacity = config.max_datagram_size - kPacketHeaderWireSize;
    if (payload_size == 0) {
        return FragmentCountResult{
            .fragment_payload_capacity = capacity,
            .total_slices = 1,
        };
    }

    if (capacity == 0) {
        return count_error(FragmentError::DatagramBudgetTooSmall);
    }

    const std::size_t quotient = payload_size / capacity;
    const std::size_t remainder = payload_size % capacity;
    const std::size_t total_slices = quotient + (remainder == 0 ? 0U : 1U);

    if (total_slices == 0) {
        return count_error(FragmentError::PayloadTooLarge);
    }

    if (total_slices > std::numeric_limits<std::uint16_t>::max()) {
        return count_error(FragmentError::TooManyFragments);
    }

    return FragmentCountResult{
        .fragment_payload_capacity = capacity,
        .total_slices = static_cast<std::uint16_t>(total_slices),
    };
}

FragmentationPlanResult plan_fragments(FragmentationConfig config,
                                       const PacketHeader& logical_header,
                                       std::span<const std::byte> payload) noexcept {
    const PacketStatus packet_status = validate_packet_header(logical_header);
    const FragmentError mapped_packet_error = map_packet_error(packet_status.error);
    if (mapped_packet_error != FragmentError::None) {
        return plan_error(mapped_packet_error);
    }

    if (logical_header.slice_index != 0 || logical_header.total_slices != 1) {
        return plan_error(FragmentError::AlreadyFragmented);
    }

    const FragmentCountResult count = calculate_fragment_count(config, payload.size());
    if (!count.ok()) {
        return plan_error(count.error);
    }

    return FragmentationPlanResult{
        .plan =
            FragmentationPlan{
                .config = config,
                .logical_header = logical_header,
                .payload = payload,
                .fragment_payload_capacity = count.fragment_payload_capacity,
                .total_slices = count.total_slices,
            },
    };
}

bool FragmentCursor::has_next() const noexcept {
    return next_slice_index_ < plan_.total_slices;
}

FragmentResult FragmentCursor::next() noexcept {
    if (!has_next()) {
        return FragmentResult{.error = FragmentError::NoMoreFragments};
    }

    const std::uint16_t slice_index = next_slice_index_;
    const bool is_final_slice = slice_index == static_cast<std::uint16_t>(plan_.total_slices - 1U);

    std::size_t offset = 0;
    if (plan_.fragment_payload_capacity != 0) {
        const std::size_t slice = slice_index;
        if (slice > (std::numeric_limits<std::size_t>::max() / plan_.fragment_payload_capacity)) {
            return FragmentResult{.error = FragmentError::SizeOverflow};
        }
        offset = slice * plan_.fragment_payload_capacity;
    }

    if (offset > plan_.payload.size()) {
        return FragmentResult{.error = FragmentError::SizeOverflow};
    }

    std::size_t length = 0;
    if (!plan_.payload.empty()) {
        const std::size_t remaining = plan_.payload.size() - offset;
        length = is_final_slice || remaining < plan_.fragment_payload_capacity
                     ? remaining
                     : plan_.fragment_payload_capacity;
    }

    PacketHeader header = plan_.logical_header;
    header.sequence_number =
        fragment_sequence_number(plan_.logical_header.sequence_number, slice_index);
    header.slice_index = slice_index;
    header.total_slices = plan_.total_slices;

    ++next_slice_index_;

    return FragmentResult{
        .fragment =
            FragmentView{
                .header = header,
                .payload = plan_.payload.subspan(offset, length),
            },
    };
}

} // namespace warpnect::scl

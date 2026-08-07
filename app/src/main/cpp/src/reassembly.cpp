#include "reassembly.h"

#include <cstring>
#include <limits>

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr ReassemblyResult
reassembly_result(FragmentError error, bool complete = false,
                  std::uint16_t received_fragments = 0) noexcept {
    return ReassemblyResult{
        .error = error,
        .complete = complete,
        .received_fragments = received_fragments,
    };
}

[[nodiscard]] constexpr FragmentSizeResult size_error(FragmentError error) noexcept {
    return FragmentSizeResult{.error = error};
}

[[nodiscard]] constexpr FragmentError packet_error_to_fragment_error(PacketError error) noexcept {
    return error == PacketError::None ? FragmentError::None : FragmentError::InvalidFragment;
}

[[nodiscard]] constexpr bool checked_multiply(std::size_t left, std::size_t right,
                                              std::size_t& result) noexcept {
    if (left != 0 && right > (std::numeric_limits<std::size_t>::max() / left)) {
        return false;
    }

    result = left * right;
    return true;
}

[[nodiscard]] constexpr bool checked_add(std::size_t left, std::size_t right,
                                         std::size_t& result) noexcept {
    if (right > (std::numeric_limits<std::size_t>::max() - left)) {
        return false;
    }

    result = left + right;
    return true;
}

[[nodiscard]] constexpr bool bitmap_has(std::span<const std::byte> bitmap,
                                        std::uint16_t index) noexcept {
    const std::size_t byte_index = static_cast<std::size_t>(index) / 8U;
    const unsigned int bit = static_cast<unsigned int>(index % 8U);
    if (byte_index >= bitmap.size()) {
        return false;
    }

    const auto value = std::to_integer<unsigned int>(bitmap[byte_index]);
    return (value & (1U << bit)) != 0;
}

void bitmap_set(std::span<std::byte> bitmap, std::uint16_t index) noexcept {
    const std::size_t byte_index = static_cast<std::size_t>(index) / 8U;
    const unsigned int bit = static_cast<unsigned int>(index % 8U);
    const auto value = std::to_integer<unsigned int>(bitmap[byte_index]);
    bitmap[byte_index] = static_cast<std::byte>(value | (1U << bit));
}

void clear_bitmap(std::span<std::byte> bitmap, std::size_t used_bytes) noexcept {
    for (std::size_t i = 0; i < used_bytes; ++i) {
        bitmap[i] = std::byte{0};
    }
}

[[nodiscard]] bool payload_bytes_match(std::span<const std::byte> stored,
                                       std::span<const std::byte> incoming) noexcept {
    if (stored.size() != incoming.size()) {
        return false;
    }

    for (std::size_t i = 0; i < stored.size(); ++i) {
        if (stored[i] != incoming[i]) {
            return false;
        }
    }

    return true;
}

} // namespace

FragmentSizeResult required_reassembly_metadata_size(std::uint16_t total_slices) noexcept {
    if (total_slices == 0) {
        return size_error(FragmentError::InvalidFragment);
    }

    return FragmentSizeResult{.size = (static_cast<std::size_t>(total_slices) + 7U) / 8U};
}

FragmentSizeResult max_reassembled_payload_size(FragmentationConfig config,
                                                std::uint16_t total_slices) noexcept {
    if (total_slices == 0) {
        return size_error(FragmentError::InvalidFragment);
    }

    const FragmentStatus config_status = validate_fragmentation_config(config);
    if (!config_status.ok()) {
        return size_error(config_status.error);
    }

    const std::size_t capacity = config.max_datagram_size - kPacketHeaderWireSize;
    std::size_t size = 0;
    if (!checked_multiply(static_cast<std::size_t>(total_slices), capacity, size)) {
        return size_error(FragmentError::SizeOverflow);
    }

    return FragmentSizeResult{.size = size};
}

FragmentGroupKey fragment_group_key(const PacketHeader& header) noexcept {
    return FragmentGroupKey{
        .protocol_version = header.protocol_version,
        .base_sequence_number =
            fragment_base_sequence_number(header.sequence_number, header.slice_index),
        .timestamp_us = header.timestamp_us,
        .payload_type = header.payload_type,
        .flags = header.flags,
        .total_slices = header.total_slices,
    };
}

ReassemblySlot::ReassemblySlot(FragmentationConfig config, ReassemblyWorkspace workspace) noexcept
    : config_(config), workspace_(workspace) {}

ReassemblyResult ReassemblySlot::accept(const PacketView& fragment) noexcept {
    const PacketStatus packet_status = validate_packet_header(fragment.header);
    const FragmentError mapped_packet_error = packet_error_to_fragment_error(packet_status.error);
    if (mapped_packet_error != FragmentError::None) {
        return reassembly_result(mapped_packet_error, complete_, received_fragments_);
    }

    const FragmentStatus config_status = validate_fragmentation_config(config_);
    if (!config_status.ok()) {
        return reassembly_result(config_status.error, complete_, received_fragments_);
    }

    const std::size_t fragment_capacity = config_.max_datagram_size - kPacketHeaderWireSize;
    const std::uint16_t slice_index = fragment.header.slice_index;
    const std::uint16_t total_slices = fragment.header.total_slices;
    const bool single_slice = total_slices == 1;
    const bool final_slice = slice_index == static_cast<std::uint16_t>(total_slices - 1U);

    if (!single_slice && fragment_capacity == 0) {
        return reassembly_result(FragmentError::DatagramBudgetTooSmall, complete_,
                                 received_fragments_);
    }

    if (fragment.payload.size() > fragment_capacity) {
        return reassembly_result(FragmentError::FragmentSizeMismatch, complete_,
                                 received_fragments_);
    }

    if (!single_slice && !final_slice && fragment.payload.size() != fragment_capacity) {
        return reassembly_result(FragmentError::FragmentSizeMismatch, complete_,
                                 received_fragments_);
    }

    if (!single_slice && final_slice && fragment.payload.empty()) {
        return reassembly_result(FragmentError::FragmentSizeMismatch, complete_,
                                 received_fragments_);
    }

    const FragmentGroupKey incoming_group = fragment_group_key(fragment.header);
    if (started_ && !(incoming_group == group_)) {
        return reassembly_result(FragmentError::FragmentGroupMismatch, complete_,
                                 received_fragments_);
    }

    const FragmentSizeResult required_metadata = required_reassembly_metadata_size(total_slices);
    if (!required_metadata.ok()) {
        return reassembly_result(required_metadata.error, complete_, received_fragments_);
    }

    std::size_t destination_offset = 0;
    if (!checked_multiply(static_cast<std::size_t>(slice_index), fragment_capacity,
                          destination_offset)) {
        return reassembly_result(FragmentError::SizeOverflow, complete_, received_fragments_);
    }

    std::size_t end_offset = 0;
    if (!checked_add(destination_offset, fragment.payload.size(), end_offset)) {
        return reassembly_result(FragmentError::SizeOverflow, complete_, received_fragments_);
    }

    std::size_t required_payload_storage = 0;
    std::size_t final_logical_size = 0;
    bool incoming_final_size_known = false;
    if (started_ && final_size_known_) {
        required_payload_storage = logical_payload_size_;
    } else if (single_slice || final_slice) {
        incoming_final_size_known = true;
        final_logical_size = end_offset;
        required_payload_storage = final_logical_size;
    } else {
        const FragmentSizeResult max_size = max_reassembled_payload_size(config_, total_slices);
        if (!max_size.ok()) {
            return reassembly_result(max_size.error, complete_, received_fragments_);
        }
        required_payload_storage = max_size.size;
    }

    if (required_metadata.size > workspace_.received_bitmap.size()) {
        return reassembly_result(FragmentError::MetadataStorageTooSmall, complete_,
                                 received_fragments_);
    }

    if (required_payload_storage > workspace_.payload_storage.size()) {
        return reassembly_result(FragmentError::ReassemblyStorageTooSmall, complete_,
                                 received_fragments_);
    }

    if (!started_) {
        group_ = incoming_group;
        fragment_payload_capacity_ = fragment_capacity;
        metadata_bytes_ = required_metadata.size;
        received_fragments_ = 0;
        final_size_known_ = false;
        complete_ = false;
        logical_payload_size_ = 0;
        clear_bitmap(workspace_.received_bitmap, metadata_bytes_);
    } else {
        if (fragment_capacity != fragment_payload_capacity_ ||
            required_metadata.size != metadata_bytes_) {
            return reassembly_result(FragmentError::FragmentGroupMismatch, complete_,
                                     received_fragments_);
        }
    }

    if (final_size_known_ && end_offset > logical_payload_size_) {
        return reassembly_result(FragmentError::FragmentSizeMismatch, complete_,
                                 received_fragments_);
    }

    if (bitmap_has(workspace_.received_bitmap.first(metadata_bytes_), slice_index)) {
        const std::size_t stored_size = final_slice && final_size_known_
                                            ? logical_payload_size_ - destination_offset
                                            : fragment.payload.size();
        if (destination_offset > workspace_.payload_storage.size() ||
            stored_size > workspace_.payload_storage.size() - destination_offset) {
            return reassembly_result(FragmentError::ConflictingFragment, complete_,
                                     received_fragments_);
        }

        const std::span<const std::byte> stored =
            workspace_.payload_storage.subspan(destination_offset, stored_size);
        const FragmentError duplicate_result = payload_bytes_match(stored, fragment.payload)
                                                   ? FragmentError::DuplicateFragment
                                                   : FragmentError::ConflictingFragment;
        return reassembly_result(duplicate_result, complete_, received_fragments_);
    }

    if (end_offset > workspace_.payload_storage.size()) {
        return reassembly_result(FragmentError::ReassemblyStorageTooSmall, complete_,
                                 received_fragments_);
    }

    if (incoming_final_size_known) {
        if (final_size_known_ && final_logical_size != logical_payload_size_) {
            return reassembly_result(FragmentError::FragmentSizeMismatch, complete_,
                                     received_fragments_);
        }
        logical_payload_size_ = final_logical_size;
        final_size_known_ = true;
    }

    if (!fragment.payload.empty()) {
        std::memmove(workspace_.payload_storage.data() + destination_offset,
                     fragment.payload.data(), fragment.payload.size());
    }

    bitmap_set(workspace_.received_bitmap.first(metadata_bytes_), slice_index);
    ++received_fragments_;
    started_ = true;
    complete_ = received_fragments_ == total_slices && final_size_known_;

    return reassembly_result(FragmentError::None, complete_, received_fragments_);
}

bool ReassemblySlot::is_complete() const noexcept {
    return complete_;
}

ReassembledPayloadResult ReassemblySlot::result() const noexcept {
    if (!started_) {
        return ReassembledPayloadResult{.error = FragmentError::ReassemblyNotStarted};
    }

    if (!complete_) {
        return ReassembledPayloadResult{.error = FragmentError::ReassemblyIncomplete};
    }

    return ReassembledPayloadResult{
        .payload =
            ReassembledPayloadView{
                .group = group_,
                .payload = workspace_.payload_storage.first(logical_payload_size_),
            },
    };
}

void ReassemblySlot::reset() noexcept {
    if (metadata_bytes_ != 0) {
        clear_bitmap(workspace_.received_bitmap, metadata_bytes_);
    }

    group_ = {};
    fragment_payload_capacity_ = 0;
    metadata_bytes_ = 0;
    logical_payload_size_ = 0;
    received_fragments_ = 0;
    started_ = false;
    final_size_known_ = false;
    complete_ = false;
}

} // namespace warpnect::scl

#ifndef WARPNECT_SCL_REASSEMBLY_H_
#define WARPNECT_SCL_REASSEMBLY_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "fragment_result.h"
#include "fragmentation.h"
#include "packet_result.h"

namespace warpnect::scl {

struct ReassemblyWorkspace final {
    std::span<std::byte> payload_storage{};
    std::span<std::byte> received_bitmap{};
};

struct [[nodiscard]] ReassemblyResult final {
    FragmentError error = FragmentError::None;
    bool complete = false;
    std::uint16_t received_fragments = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FragmentError::None;
    }
};

struct ReassembledPayloadView final {
    FragmentGroupKey group{};
    std::span<const std::byte> payload{};
};

struct [[nodiscard]] ReassembledPayloadResult final {
    FragmentError error = FragmentError::None;
    ReassembledPayloadView payload{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FragmentError::None;
    }
};

[[nodiscard]] FragmentSizeResult
required_reassembly_metadata_size(std::uint16_t total_slices) noexcept;

[[nodiscard]] FragmentSizeResult max_reassembled_payload_size(FragmentationConfig config,
                                                              std::uint16_t total_slices) noexcept;

[[nodiscard]] FragmentGroupKey fragment_group_key(const PacketHeader& header) noexcept;

class ReassemblySlot final {
  public:
    ReassemblySlot(FragmentationConfig config, ReassemblyWorkspace workspace) noexcept;

    [[nodiscard]] ReassemblyResult accept(const PacketView& fragment) noexcept;
    [[nodiscard]] bool is_complete() const noexcept;
    [[nodiscard]] ReassembledPayloadResult result() const noexcept;

    void reset() noexcept;

    [[nodiscard]] constexpr bool is_started() const noexcept {
        return started_;
    }

    [[nodiscard]] constexpr std::uint16_t received_fragment_count() const noexcept {
        return received_fragments_;
    }

    [[nodiscard]] constexpr FragmentGroupKey group() const noexcept {
        return group_;
    }

  private:
    FragmentationConfig config_{};
    ReassemblyWorkspace workspace_{};
    FragmentGroupKey group_{};
    std::size_t fragment_payload_capacity_ = 0;
    std::size_t metadata_bytes_ = 0;
    std::size_t logical_payload_size_ = 0;
    std::uint16_t received_fragments_ = 0;
    bool started_ = false;
    bool final_size_known_ = false;
    bool complete_ = false;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_REASSEMBLY_H_

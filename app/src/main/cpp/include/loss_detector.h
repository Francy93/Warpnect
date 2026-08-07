#ifndef WARPNECT_SCL_LOSS_DETECTOR_H_
#define WARPNECT_SCL_LOSS_DETECTOR_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "recovery_control.h"
#include "recovery_result.h"
#include "sequence_number.h"

namespace warpnect::scl {

struct LossRecoveryConfig final {
    std::uint64_t reorder_delay_us = 0;
    std::uint64_t renack_interval_us = 0;
    std::uint16_t max_nack_attempts = 0;

    constexpr bool operator==(const LossRecoveryConfig&) const = default;
};

struct LossSlot final {
    std::uint32_t sequence_number = 0;
    std::uint64_t first_missing_us = 0;
    std::uint64_t last_nack_us = 0;
    std::uint16_t nack_attempts = 0;
    LossSlotState state = LossSlotState::Empty;
};

[[nodiscard]] RecoveryStatus validate_loss_detector_storage(std::span<LossSlot> slots) noexcept;

class LossDetector final {
  public:
    LossDetector(LossRecoveryConfig config, std::span<LossSlot> slots) noexcept;

    [[nodiscard]] LossObservationResult observe(std::uint32_t sequence_number,
                                                std::uint64_t now_us) noexcept;

    [[nodiscard]] NackCollectionResult collect_due_nacks(std::uint64_t now_us,
                                                         PayloadType target_payload_type,
                                                         std::span<NackRequest> output) noexcept;

    [[nodiscard]] LossSequenceStatusResult
    sequence_status(std::uint32_t sequence_number) const noexcept;

    void reset() noexcept;

    [[nodiscard]] constexpr bool is_started() const noexcept {
        return started_;
    }

    [[nodiscard]] constexpr bool has_missing() const noexcept {
        return missing_count_ != 0;
    }

    [[nodiscard]] constexpr std::size_t missing_count() const noexcept {
        return missing_count_;
    }

    [[nodiscard]] constexpr std::size_t tracked_count() const noexcept {
        return tracked_count_;
    }

    [[nodiscard]] constexpr std::size_t window_capacity() const noexcept {
        return slots_.size();
    }

    [[nodiscard]] constexpr std::uint32_t frontier_sequence() const noexcept {
        return frontier_sequence_;
    }

    [[nodiscard]] constexpr std::uint32_t oldest_sequence() const noexcept {
        return oldest_sequence_;
    }

  private:
    [[nodiscard]] RecoveryStatus validate_configuration() const noexcept;
    [[nodiscard]] LossSlot& slot_for(std::uint32_t sequence_number) noexcept;
    [[nodiscard]] const LossSlot& slot_for(std::uint32_t sequence_number) const noexcept;
    [[nodiscard]] bool can_advance(std::uint32_t distance) const noexcept;
    void evict_for_new_positions(std::uint32_t distance) noexcept;
    void mark_received(std::uint32_t sequence_number) noexcept;
    void mark_missing(std::uint32_t sequence_number, std::uint64_t now_us) noexcept;
    [[nodiscard]] bool sequence_is_tracked(std::uint32_t sequence_number,
                                           RecoveryError& error) const noexcept;
    [[nodiscard]] bool missing_slot_is_due(const LossSlot& slot,
                                           std::uint64_t now_us) const noexcept;
    void mark_nacked(const NackRequest& request, std::uint64_t now_us) noexcept;

    LossRecoveryConfig config_{};
    std::span<LossSlot> slots_{};
    std::uint32_t oldest_sequence_ = 0;
    std::uint32_t frontier_sequence_ = 0;
    std::size_t tracked_count_ = 0;
    std::size_t missing_count_ = 0;
    bool started_ = false;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_LOSS_DETECTOR_H_

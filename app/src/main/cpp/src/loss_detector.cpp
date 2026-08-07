#include "loss_detector.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr RecoveryStatus status(RecoveryError error) noexcept {
    return RecoveryStatus{.error = error};
}

[[nodiscard]] constexpr LossObservationResult
observation(RecoveryError error, LossObservationKind kind, std::uint32_t sequence_number,
            std::size_t missing_created, std::size_t missing_count) noexcept {
    return LossObservationResult{
        .error = error,
        .kind = kind,
        .sequence_number = sequence_number,
        .missing_created = missing_created,
        .missing_count = missing_count,
    };
}

[[nodiscard]] constexpr LossSequenceStatusResult
sequence_status_error(RecoveryError error) noexcept {
    return LossSequenceStatusResult{.error = error};
}

[[nodiscard]] constexpr NackCollectionResult
collection_result(RecoveryError error, std::size_t requests_written, bool exhausted) noexcept {
    return NackCollectionResult{
        .error = error,
        .requests_written = requests_written,
        .output_exhausted = exhausted,
    };
}

[[nodiscard]] constexpr std::uint64_t elapsed_us(std::uint64_t now_us,
                                                 std::uint64_t then_us) noexcept {
    return now_us >= then_us ? now_us - then_us : 0U;
}

} // namespace

RecoveryStatus validate_loss_detector_storage(std::span<LossSlot> slots) noexcept {
    if (slots.empty()) {
        return status(RecoveryError::InvalidWindowCapacity);
    }

    if (slots.size() >= static_cast<std::size_t>(kSequenceHalfRange)) {
        return status(RecoveryError::InvalidWindowCapacity);
    }

    return status(RecoveryError::None);
}

LossDetector::LossDetector(LossRecoveryConfig config, std::span<LossSlot> slots) noexcept
    : config_(config), slots_(slots) {
    reset();
}

LossObservationResult LossDetector::observe(std::uint32_t sequence_number,
                                            std::uint64_t now_us) noexcept {
    const RecoveryStatus validation = validate_configuration();
    if (!validation.ok()) {
        return observation(validation.error, LossObservationKind::None, sequence_number, 0,
                           missing_count_);
    }

    if (!started_) {
        oldest_sequence_ = sequence_number;
        frontier_sequence_ = sequence_number;
        tracked_count_ = 1;
        missing_count_ = 0;
        started_ = true;
        mark_received(sequence_number);
        return observation(RecoveryError::None, LossObservationKind::FirstPacket, sequence_number,
                           0, missing_count_);
    }

    const SequenceOrderResult frontier_comparison =
        compare_sequence_numbers(sequence_number, frontier_sequence_);
    if (!frontier_comparison.ok()) {
        return observation(frontier_comparison.error, LossObservationKind::None, sequence_number, 0,
                           missing_count_);
    }

    if (frontier_comparison.order == SequenceOrder::Equal) {
        return observation(RecoveryError::None, LossObservationKind::Duplicate, sequence_number, 0,
                           missing_count_);
    }

    if (frontier_comparison.order == SequenceOrder::Newer) {
        const std::uint32_t distance = sequence_number - frontier_sequence_;
        if (!can_advance(distance)) {
            return observation(RecoveryError::WindowCapacityExceeded, LossObservationKind::None,
                               sequence_number, 0, missing_count_);
        }

        evict_for_new_positions(distance);

        std::size_t missing_created = 0;
        for (std::uint32_t step = 1; step < distance; ++step) {
            mark_missing(frontier_sequence_ + step, now_us);
            ++tracked_count_;
            ++missing_created;
        }

        mark_received(sequence_number);
        ++tracked_count_;
        frontier_sequence_ = sequence_number;

        const LossObservationKind kind =
            missing_created == 0 ? LossObservationKind::InOrder : LossObservationKind::GapDetected;
        return observation(RecoveryError::None, kind, sequence_number, missing_created,
                           missing_count_);
    }

    RecoveryError tracking_error = RecoveryError::None;
    if (!sequence_is_tracked(sequence_number, tracking_error)) {
        if (tracking_error != RecoveryError::None) {
            return observation(tracking_error, LossObservationKind::None, sequence_number, 0,
                               missing_count_);
        }

        return observation(RecoveryError::None, LossObservationKind::TooOld, sequence_number, 0,
                           missing_count_);
    }

    LossSlot& slot = slot_for(sequence_number);
    if (slot.state == LossSlotState::Missing) {
        mark_received(sequence_number);
        return observation(RecoveryError::None, LossObservationKind::RecoveredMissing,
                           sequence_number, 0, missing_count_);
    }

    if (slot.state == LossSlotState::Received) {
        return observation(RecoveryError::None, LossObservationKind::Duplicate, sequence_number, 0,
                           missing_count_);
    }

    return observation(RecoveryError::None, LossObservationKind::TooOld, sequence_number, 0,
                       missing_count_);
}

NackCollectionResult LossDetector::collect_due_nacks(std::uint64_t now_us,
                                                     PayloadType target_payload_type,
                                                     std::span<NackRequest> output) noexcept {
    const RecoveryStatus validation = validate_configuration();
    if (!validation.ok()) {
        return collection_result(validation.error, 0, false);
    }

    if (!payload_type_is_valid(target_payload_type)) {
        return collection_result(RecoveryError::InvalidTargetPayloadType, 0, false);
    }

    if (!started_ || missing_count_ == 0) {
        return collection_result(RecoveryError::None, 0, false);
    }

    std::size_t written = 0;
    bool has_pending = false;
    NackRequest pending{
        .target_payload_type = target_payload_type,
    };

    std::uint32_t sequence = oldest_sequence_;
    for (std::size_t visited = 0; visited < tracked_count_; ++visited) {
        const LossSlot& slot = slot_for(sequence);
        if (slot.state == LossSlotState::Missing && missing_slot_is_due(slot, now_us)) {
            if (!has_pending) {
                pending.base_sequence_number = sequence;
                pending.missing_bitmap = 1U;
                has_pending = true;
            } else {
                const std::uint32_t distance = sequence - pending.base_sequence_number;
                if (distance < 64U) {
                    pending.missing_bitmap |= std::uint64_t{1} << distance;
                } else {
                    if (written >= output.size()) {
                        return collection_result(RecoveryError::OutputBufferTooSmall, written,
                                                 true);
                    }

                    output[written] = pending;
                    mark_nacked(pending, now_us);
                    ++written;

                    pending.base_sequence_number = sequence;
                    pending.missing_bitmap = 1U;
                }
            }
        }

        ++sequence;
    }

    if (has_pending) {
        if (written >= output.size()) {
            return collection_result(RecoveryError::OutputBufferTooSmall, written, true);
        }

        output[written] = pending;
        mark_nacked(pending, now_us);
        ++written;
    }

    return collection_result(RecoveryError::None, written, false);
}

LossSequenceStatusResult
LossDetector::sequence_status(std::uint32_t sequence_number) const noexcept {
    const RecoveryStatus validation = validate_configuration();
    if (!validation.ok()) {
        return sequence_status_error(validation.error);
    }

    RecoveryError tracking_error = RecoveryError::None;
    if (!sequence_is_tracked(sequence_number, tracking_error)) {
        return sequence_status_error(tracking_error);
    }

    const LossSlot& slot = slot_for(sequence_number);
    return LossSequenceStatusResult{
        .state = slot.state,
        .nack_attempts = slot.nack_attempts,
        .first_missing_us = slot.first_missing_us,
        .last_nack_us = slot.last_nack_us,
    };
}

void LossDetector::reset() noexcept {
    for (LossSlot& slot : slots_) {
        slot = LossSlot{};
    }

    oldest_sequence_ = 0;
    frontier_sequence_ = 0;
    tracked_count_ = 0;
    missing_count_ = 0;
    started_ = false;
}

RecoveryStatus LossDetector::validate_configuration() const noexcept {
    return validate_loss_detector_storage(slots_);
}

LossSlot& LossDetector::slot_for(std::uint32_t sequence_number) noexcept {
    return slots_[static_cast<std::size_t>(sequence_number) % slots_.size()];
}

const LossSlot& LossDetector::slot_for(std::uint32_t sequence_number) const noexcept {
    return slots_[static_cast<std::size_t>(sequence_number) % slots_.size()];
}

bool LossDetector::can_advance(std::uint32_t distance) const noexcept {
    const std::size_t capacity = slots_.size();
    if (distance == 0U || static_cast<std::size_t>(distance) > capacity) {
        return false;
    }

    const std::size_t positions_to_add = static_cast<std::size_t>(distance);
    if (tracked_count_ + positions_to_add <= capacity) {
        return true;
    }

    const std::size_t evictions_needed = tracked_count_ + positions_to_add - capacity;
    std::uint32_t sequence = oldest_sequence_;
    for (std::size_t i = 0; i < evictions_needed; ++i) {
        if (slot_for(sequence).state == LossSlotState::Missing) {
            return false;
        }
        ++sequence;
    }

    return true;
}

void LossDetector::evict_for_new_positions(std::uint32_t distance) noexcept {
    const std::size_t capacity = slots_.size();
    const std::size_t positions_to_add = static_cast<std::size_t>(distance);
    if (tracked_count_ + positions_to_add <= capacity) {
        return;
    }

    const std::size_t evictions_needed = tracked_count_ + positions_to_add - capacity;
    for (std::size_t i = 0; i < evictions_needed; ++i) {
        LossSlot& slot = slot_for(oldest_sequence_);
        if (slot.state == LossSlotState::Missing && missing_count_ != 0) {
            --missing_count_;
        }
        slot = LossSlot{};
        ++oldest_sequence_;
        --tracked_count_;
    }
}

void LossDetector::mark_received(std::uint32_t sequence_number) noexcept {
    LossSlot& slot = slot_for(sequence_number);
    if (slot.state == LossSlotState::Missing && missing_count_ != 0) {
        --missing_count_;
    }

    slot = LossSlot{
        .sequence_number = sequence_number,
        .state = LossSlotState::Received,
    };
}

void LossDetector::mark_missing(std::uint32_t sequence_number, std::uint64_t now_us) noexcept {
    LossSlot& slot = slot_for(sequence_number);
    slot = LossSlot{
        .sequence_number = sequence_number,
        .first_missing_us = now_us,
        .state = LossSlotState::Missing,
    };
    ++missing_count_;
}

bool LossDetector::sequence_is_tracked(std::uint32_t sequence_number,
                                       RecoveryError& error) const noexcept {
    error = RecoveryError::None;
    if (!started_ || tracked_count_ == 0) {
        return false;
    }

    const SequenceOrderResult compared_to_oldest =
        compare_sequence_numbers(sequence_number, oldest_sequence_);
    if (!compared_to_oldest.ok()) {
        error = compared_to_oldest.error;
        return false;
    }

    if (compared_to_oldest.order == SequenceOrder::Older) {
        return false;
    }

    const SequenceOrderResult compared_to_frontier =
        compare_sequence_numbers(sequence_number, frontier_sequence_);
    if (!compared_to_frontier.ok()) {
        error = compared_to_frontier.error;
        return false;
    }

    return compared_to_frontier.order == SequenceOrder::Older ||
           compared_to_frontier.order == SequenceOrder::Equal;
}

bool LossDetector::missing_slot_is_due(const LossSlot& slot, std::uint64_t now_us) const noexcept {
    if (slot.nack_attempts >= config_.max_nack_attempts) {
        return false;
    }

    if (slot.nack_attempts == 0) {
        return elapsed_us(now_us, slot.first_missing_us) >= config_.reorder_delay_us;
    }

    return elapsed_us(now_us, slot.last_nack_us) >= config_.renack_interval_us;
}

void LossDetector::mark_nacked(const NackRequest& request, std::uint64_t now_us) noexcept {
    for (std::uint8_t bit = 0; bit < 64U; ++bit) {
        if ((request.missing_bitmap & (std::uint64_t{1} << bit)) == 0U) {
            continue;
        }

        const std::uint32_t sequence_number =
            request.base_sequence_number + static_cast<std::uint32_t>(bit);
        RecoveryError tracking_error = RecoveryError::None;
        if (!sequence_is_tracked(sequence_number, tracking_error)) {
            continue;
        }

        LossSlot& slot = slot_for(sequence_number);
        if (slot.state != LossSlotState::Missing ||
            slot.nack_attempts >= config_.max_nack_attempts) {
            continue;
        }

        slot.last_nack_us = now_us;
        ++slot.nack_attempts;
    }
}

} // namespace warpnect::scl

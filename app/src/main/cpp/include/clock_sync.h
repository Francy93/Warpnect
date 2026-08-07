#ifndef WARPNECT_SCL_CLOCK_SYNC_H_
#define WARPNECT_SCL_CLOCK_SYNC_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "clock_sync_control.h"
#include "timing_result.h"

namespace warpnect::scl {

enum class ClockSyncState : std::uint8_t {
    Unsynchronized = 0,
    WarmingUp,
    Synchronized,
    Degraded,
    Stale,
};

struct PendingClockExchange final {
    std::uint32_t exchange_id = 0;
    std::uint64_t t0_us = 0;
    bool active = false;
};

struct ClockSyncSample final {
    std::uint64_t t0_us = 0;
    std::uint64_t t1_us = 0;
    std::uint64_t t2_us = 0;
    std::uint64_t t3_us = 0;
    std::uint64_t rtt_us = 0;
    double offset_us = 0.0;
    double local_midpoint_us = 0.0;
    double remote_midpoint_us = 0.0;

    constexpr bool operator==(const ClockSyncSample&) const = default;
};

struct [[nodiscard]] ClockSyncSampleResult final {
    TimingError error = TimingError::None;
    ClockSyncSample sample{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == TimingError::None;
    }
};

struct [[nodiscard]] ClockExchangeResult final {
    TimingError error = TimingError::None;
    ClockSyncSample sample{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == TimingError::None;
    }
};

struct ClockSyncConfig final {
    std::size_t min_samples_for_model = 2;
    std::uint64_t max_accepted_rtt_us = 1'000'000;
    double max_abs_drift_ppm = 1'000.0;
    std::uint64_t stale_after_us = 5'000'000;
};

struct ClockModelSnapshot final {
    ClockSyncState state = ClockSyncState::Unsynchronized;
    std::size_t accepted_samples = 0;
    std::size_t rejected_samples = 0;
    double reference_local_us = 0.0;
    double reference_remote_us = 0.0;
    double rate_ratio = 1.0;
    double drift_ppm = 0.0;
    std::uint64_t latest_rtt_us = 0;
    std::uint64_t best_rtt_us = 0;
    std::uint64_t last_update_local_us = 0;
};

struct [[nodiscard]] ClockModelUpdateResult final {
    TimingError error = TimingError::None;
    ClockModelSnapshot snapshot{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == TimingError::None;
    }
};

struct [[nodiscard]] ClockTimestampResult final {
    TimingError error = TimingError::None;
    std::uint64_t timestamp_us = 0;
    ClockSyncState state = ClockSyncState::Unsynchronized;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == TimingError::None;
    }
};

struct [[nodiscard]] ClockDelayResult final {
    TimingError error = TimingError::None;
    std::uint64_t delay_us = 0;
    ClockSyncState state = ClockSyncState::Unsynchronized;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == TimingError::None;
    }
};

[[nodiscard]] TimingStatus validate_clock_sync_config(const ClockSyncConfig& config,
                                                      std::size_t sample_capacity) noexcept;

[[nodiscard]] ClockSyncSampleResult calculate_clock_sync_sample(const ClockSyncResponse& response,
                                                                std::uint64_t t3_us) noexcept;

class ClockExchangeTracker final {
  public:
    explicit ClockExchangeTracker(std::span<PendingClockExchange> storage) noexcept;

    [[nodiscard]] TimingStatus register_request(std::uint32_t exchange_id,
                                                std::uint64_t t0_us) noexcept;
    [[nodiscard]] ClockExchangeResult complete_response(const ClockSyncResponse& response,
                                                        std::uint64_t t3_us) noexcept;
    void reset() noexcept;

    [[nodiscard]] std::size_t active_count() const noexcept {
        return active_count_;
    }

  private:
    std::span<PendingClockExchange> storage_{};
    std::size_t active_count_ = 0;
};

class ClockSynchronizer final {
  public:
    ClockSynchronizer(const ClockSyncConfig& config,
                      std::span<ClockSyncSample> sample_storage) noexcept;

    [[nodiscard]] ClockModelUpdateResult add_sample(const ClockSyncSample& sample) noexcept;
    [[nodiscard]] ClockModelSnapshot snapshot(std::uint64_t now_us) const noexcept;
    [[nodiscard]] ClockTimestampResult remote_to_local(std::uint64_t remote_timestamp_us,
                                                       std::uint64_t now_us) const noexcept;
    [[nodiscard]] ClockTimestampResult local_to_remote(std::uint64_t local_timestamp_us,
                                                       std::uint64_t now_us) const noexcept;
    [[nodiscard]] ClockDelayResult estimate_one_way_delay(std::uint64_t remote_send_timestamp_us,
                                                          std::uint64_t local_receive_timestamp_us,
                                                          std::uint64_t now_us) const noexcept;

    void reset() noexcept;

    [[nodiscard]] std::size_t sample_count() const noexcept {
        return sample_count_;
    }

  private:
    [[nodiscard]] ClockModelSnapshot fit_model() const noexcept;
    [[nodiscard]] ClockSyncState state_for(const ClockModelSnapshot& snapshot,
                                           std::uint64_t now_us) const noexcept;

    ClockSyncConfig config_{};
    std::span<ClockSyncSample> samples_{};
    std::size_t sample_count_ = 0;
    std::size_t next_sample_ = 0;
    std::size_t rejected_samples_ = 0;
    ClockModelSnapshot model_{};
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_CLOCK_SYNC_H_

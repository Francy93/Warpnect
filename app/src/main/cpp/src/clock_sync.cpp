#include "clock_sync.h"

#include <cmath>
#include <limits>

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr TimingStatus status(TimingError error) noexcept {
    return TimingStatus{.error = error};
}

[[nodiscard]] constexpr ClockSyncSampleResult sample_error(TimingError error) noexcept {
    return ClockSyncSampleResult{.error = error};
}

[[nodiscard]] constexpr ClockExchangeResult exchange_error(TimingError error) noexcept {
    return ClockExchangeResult{.error = error};
}

[[nodiscard]] constexpr ClockModelUpdateResult update_error(TimingError error,
                                                            ClockModelSnapshot snapshot) noexcept {
    return ClockModelUpdateResult{.error = error, .snapshot = snapshot};
}

[[nodiscard]] constexpr ClockTimestampResult timestamp_error(TimingError error,
                                                             ClockSyncState state) noexcept {
    return ClockTimestampResult{.error = error, .state = state};
}

[[nodiscard]] constexpr ClockDelayResult delay_error(TimingError error,
                                                     ClockSyncState state) noexcept {
    return ClockDelayResult{.error = error, .state = state};
}

[[nodiscard]] constexpr bool elapsed_us(std::uint64_t start, std::uint64_t end,
                                        std::uint64_t& elapsed) noexcept {
    if (end < start) {
        return false;
    }

    elapsed = end - start;
    return true;
}

[[nodiscard]] long double midpoint(std::uint64_t first, std::uint64_t second) noexcept {
    return static_cast<long double>(first) +
           ((static_cast<long double>(second) - static_cast<long double>(first)) / 2.0L);
}

[[nodiscard]] bool finite(long double value) noexcept {
    return std::isfinite(static_cast<double>(value));
}

[[nodiscard]] bool finite(double value) noexcept {
    return std::isfinite(value);
}

[[nodiscard]] bool round_to_u64(long double value, std::uint64_t& output) noexcept {
    if (!finite(value) || value < 0.0L ||
        value > static_cast<long double>(std::numeric_limits<std::uint64_t>::max())) {
        return false;
    }

    output = static_cast<std::uint64_t>(value + 0.5L);
    return true;
}

} // namespace

TimingStatus validate_clock_sync_config(const ClockSyncConfig& config,
                                        std::size_t sample_capacity) noexcept {
    if (sample_capacity == 0 || config.min_samples_for_model < 2 ||
        config.min_samples_for_model > sample_capacity || config.max_accepted_rtt_us == 0 ||
        config.stale_after_us == 0 || !finite(config.max_abs_drift_ppm) ||
        config.max_abs_drift_ppm < 0.0) {
        return status(TimingError::InvalidConfiguration);
    }

    return status(TimingError::None);
}

ClockSyncSampleResult calculate_clock_sync_sample(const ClockSyncResponse& response,
                                                  std::uint64_t t3_us) noexcept {
    if (response.t2_us < response.t1_us) {
        return sample_error(TimingError::InvalidTimestampOrder);
    }

    std::uint64_t local_elapsed = 0;
    std::uint64_t remote_processing = 0;
    if (!elapsed_us(response.t0_us, t3_us, local_elapsed) ||
        !elapsed_us(response.t1_us, response.t2_us, remote_processing)) {
        return sample_error(TimingError::InvalidTimestampOrder);
    }

    if (remote_processing > local_elapsed) {
        return sample_error(TimingError::InvalidSample);
    }

    const std::uint64_t rtt = local_elapsed - remote_processing;
    const long double offset =
        ((static_cast<long double>(response.t1_us) - static_cast<long double>(response.t0_us)) +
         (static_cast<long double>(response.t2_us) - static_cast<long double>(t3_us))) /
        2.0L;
    const long double local_mid = midpoint(response.t0_us, t3_us);
    const long double remote_mid = midpoint(response.t1_us, response.t2_us);

    if (!finite(offset) || !finite(local_mid) || !finite(remote_mid)) {
        return sample_error(TimingError::InvalidSample);
    }

    return ClockSyncSampleResult{
        .sample =
            ClockSyncSample{
                .t0_us = response.t0_us,
                .t1_us = response.t1_us,
                .t2_us = response.t2_us,
                .t3_us = t3_us,
                .rtt_us = rtt,
                .offset_us = static_cast<double>(offset),
                .local_midpoint_us = static_cast<double>(local_mid),
                .remote_midpoint_us = static_cast<double>(remote_mid),
            },
    };
}

ClockExchangeTracker::ClockExchangeTracker(std::span<PendingClockExchange> storage) noexcept
    : storage_(storage) {
    reset();
}

TimingStatus ClockExchangeTracker::register_request(std::uint32_t exchange_id,
                                                    std::uint64_t t0_us) noexcept {
    PendingClockExchange* free_slot = nullptr;
    for (PendingClockExchange& exchange : storage_) {
        if (exchange.active && exchange.exchange_id == exchange_id) {
            return status(TimingError::DuplicateExchange);
        }

        if (!exchange.active && free_slot == nullptr) {
            free_slot = &exchange;
        }
    }

    if (free_slot == nullptr) {
        return status(TimingError::TrackerFull);
    }

    *free_slot = PendingClockExchange{
        .exchange_id = exchange_id,
        .t0_us = t0_us,
        .active = true,
    };
    ++active_count_;
    return status(TimingError::None);
}

ClockExchangeResult ClockExchangeTracker::complete_response(const ClockSyncResponse& response,
                                                            std::uint64_t t3_us) noexcept {
    for (PendingClockExchange& exchange : storage_) {
        if (!exchange.active || exchange.exchange_id != response.exchange_id) {
            continue;
        }

        if (exchange.t0_us != response.t0_us) {
            return exchange_error(TimingError::ExchangeTimestampMismatch);
        }

        const auto sample = calculate_clock_sync_sample(response, t3_us);
        if (!sample.ok()) {
            return exchange_error(sample.error);
        }

        exchange.active = false;
        --active_count_;
        return ClockExchangeResult{.sample = sample.sample};
    }

    return exchange_error(TimingError::UnknownExchange);
}

void ClockExchangeTracker::reset() noexcept {
    for (PendingClockExchange& exchange : storage_) {
        exchange = PendingClockExchange{};
    }
    active_count_ = 0;
}

ClockSynchronizer::ClockSynchronizer(const ClockSyncConfig& config,
                                     std::span<ClockSyncSample> sample_storage) noexcept
    : config_(config), samples_(sample_storage) {
    reset();
}

ClockModelUpdateResult ClockSynchronizer::add_sample(const ClockSyncSample& sample) noexcept {
    const auto validation = validate_clock_sync_config(config_, samples_.size());
    if (!validation.ok()) {
        ++rejected_samples_;
        model_.rejected_samples = rejected_samples_;
        return update_error(validation.error, snapshot(sample.t3_us));
    }

    if (sample.rtt_us > config_.max_accepted_rtt_us || !finite(sample.offset_us) ||
        !finite(sample.local_midpoint_us) || !finite(sample.remote_midpoint_us)) {
        ++rejected_samples_;
        model_.rejected_samples = rejected_samples_;
        return update_error(sample.rtt_us > config_.max_accepted_rtt_us
                                ? TimingError::RttTooLarge
                                : TimingError::InvalidSample,
                            snapshot(sample.t3_us));
    }

    samples_[next_sample_] = sample;
    next_sample_ = (next_sample_ + 1U) % samples_.size();
    if (sample_count_ < samples_.size()) {
        ++sample_count_;
    }

    model_ = fit_model();
    model_.accepted_samples = sample_count_;
    model_.rejected_samples = rejected_samples_;
    model_.latest_rtt_us = sample.rtt_us;
    if (model_.best_rtt_us == 0 || sample.rtt_us < model_.best_rtt_us) {
        model_.best_rtt_us = sample.rtt_us;
    }
    model_.last_update_local_us = sample.t3_us;
    model_.state = state_for(model_, sample.t3_us);

    return ClockModelUpdateResult{.snapshot = model_};
}

ClockModelSnapshot ClockSynchronizer::fit_model() const noexcept {
    ClockModelSnapshot snapshot = model_;
    snapshot.accepted_samples = sample_count_;
    snapshot.rejected_samples = rejected_samples_;

    if (sample_count_ == 0) {
        snapshot.state = ClockSyncState::Unsynchronized;
        return snapshot;
    }

    long double mean_local = 0.0L;
    long double mean_remote = 0.0L;
    for (std::size_t i = 0; i < sample_count_; ++i) {
        mean_local += static_cast<long double>(samples_[i].local_midpoint_us);
        mean_remote += static_cast<long double>(samples_[i].remote_midpoint_us);
    }
    mean_local /= static_cast<long double>(sample_count_);
    mean_remote /= static_cast<long double>(sample_count_);

    long double rate = 1.0L;
    if (sample_count_ >= 2) {
        long double numerator = 0.0L;
        long double denominator = 0.0L;
        for (std::size_t i = 0; i < sample_count_; ++i) {
            const long double dx =
                static_cast<long double>(samples_[i].local_midpoint_us) - mean_local;
            const long double dy =
                static_cast<long double>(samples_[i].remote_midpoint_us) - mean_remote;
            numerator += dx * dy;
            denominator += dx * dx;
        }

        if (denominator <= 0.0L) {
            snapshot.state = ClockSyncState::Degraded;
            return snapshot;
        }

        rate = numerator / denominator;
    }

    const long double drift = (rate - 1.0L) * 1'000'000.0L;
    if (!finite(mean_local) || !finite(mean_remote) || !finite(rate) || !finite(drift) ||
        rate <= 0.0L) {
        snapshot.state = ClockSyncState::Degraded;
        return snapshot;
    }

    snapshot.reference_local_us = static_cast<double>(mean_local);
    snapshot.reference_remote_us = static_cast<double>(mean_remote);
    snapshot.rate_ratio = static_cast<double>(rate);
    snapshot.drift_ppm = static_cast<double>(drift);
    snapshot.state = ClockSyncState::WarmingUp;
    return snapshot;
}

ClockSyncState ClockSynchronizer::state_for(const ClockModelSnapshot& snapshot,
                                            std::uint64_t now_us) const noexcept {
    if (sample_count_ == 0) {
        return ClockSyncState::Unsynchronized;
    }

    std::uint64_t age = 0;
    if (elapsed_us(snapshot.last_update_local_us, now_us, age) && age > config_.stale_after_us) {
        return ClockSyncState::Stale;
    }

    if (snapshot.state == ClockSyncState::Degraded) {
        return ClockSyncState::Degraded;
    }

    if (sample_count_ < config_.min_samples_for_model) {
        return ClockSyncState::WarmingUp;
    }

    if (!finite(snapshot.rate_ratio) || snapshot.rate_ratio <= 0.0 || !finite(snapshot.drift_ppm)) {
        return ClockSyncState::Degraded;
    }

    if (std::fabs(snapshot.drift_ppm) > config_.max_abs_drift_ppm) {
        return ClockSyncState::Degraded;
    }

    return ClockSyncState::Synchronized;
}

ClockModelSnapshot ClockSynchronizer::snapshot(std::uint64_t now_us) const noexcept {
    ClockModelSnapshot snapshot = model_;
    snapshot.accepted_samples = sample_count_;
    snapshot.rejected_samples = rejected_samples_;
    snapshot.state = state_for(snapshot, now_us);
    return snapshot;
}

ClockTimestampResult ClockSynchronizer::remote_to_local(std::uint64_t remote_timestamp_us,
                                                        std::uint64_t now_us) const noexcept {
    const ClockModelSnapshot current = snapshot(now_us);
    if (current.state != ClockSyncState::Synchronized) {
        return timestamp_error(TimingError::ClockModelUnavailable, current.state);
    }

    const long double rate = static_cast<long double>(current.rate_ratio);
    if (!finite(rate) || rate <= 0.0L) {
        return timestamp_error(TimingError::NonFiniteModel, current.state);
    }

    const long double converted = static_cast<long double>(current.reference_local_us) +
                                  ((static_cast<long double>(remote_timestamp_us) -
                                    static_cast<long double>(current.reference_remote_us)) /
                                   rate);
    std::uint64_t output = 0;
    if (!round_to_u64(converted, output)) {
        return timestamp_error(TimingError::ConversionOutOfRange, current.state);
    }

    return ClockTimestampResult{
        .timestamp_us = output,
        .state = current.state,
    };
}

ClockTimestampResult ClockSynchronizer::local_to_remote(std::uint64_t local_timestamp_us,
                                                        std::uint64_t now_us) const noexcept {
    const ClockModelSnapshot current = snapshot(now_us);
    if (current.state != ClockSyncState::Synchronized) {
        return timestamp_error(TimingError::ClockModelUnavailable, current.state);
    }

    const long double rate = static_cast<long double>(current.rate_ratio);
    if (!finite(rate) || rate <= 0.0L) {
        return timestamp_error(TimingError::NonFiniteModel, current.state);
    }

    const long double converted = static_cast<long double>(current.reference_remote_us) +
                                  (rate * (static_cast<long double>(local_timestamp_us) -
                                           static_cast<long double>(current.reference_local_us)));
    std::uint64_t output = 0;
    if (!round_to_u64(converted, output)) {
        return timestamp_error(TimingError::ConversionOutOfRange, current.state);
    }

    return ClockTimestampResult{
        .timestamp_us = output,
        .state = current.state,
    };
}

ClockDelayResult ClockSynchronizer::estimate_one_way_delay(std::uint64_t remote_send_timestamp_us,
                                                           std::uint64_t local_receive_timestamp_us,
                                                           std::uint64_t now_us) const noexcept {
    const auto converted_send = remote_to_local(remote_send_timestamp_us, now_us);
    if (!converted_send.ok()) {
        return delay_error(TimingError::ClockModelUnavailable, converted_send.state);
    }

    if (local_receive_timestamp_us < converted_send.timestamp_us) {
        return delay_error(TimingError::InvalidSample, converted_send.state);
    }

    return ClockDelayResult{
        .delay_us = local_receive_timestamp_us - converted_send.timestamp_us,
        .state = converted_send.state,
    };
}

void ClockSynchronizer::reset() noexcept {
    sample_count_ = 0;
    next_sample_ = 0;
    rejected_samples_ = 0;
    model_ = ClockModelSnapshot{};
}

} // namespace warpnect::scl

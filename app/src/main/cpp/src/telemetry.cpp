#include "telemetry.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace warpnect::scl {
namespace {

[[nodiscard]] std::uint64_t saturated_add(std::uint64_t value, std::uint64_t delta,
                                          bool& saturated) noexcept {
    if (delta > std::numeric_limits<std::uint64_t>::max() - value) {
        saturated = true;
        return std::numeric_limits<std::uint64_t>::max();
    }

    return value + delta;
}

[[nodiscard]] std::uint64_t absolute_difference(std::uint64_t left, std::uint64_t right) noexcept {
    return left >= right ? left - right : right - left;
}

} // namespace

std::uint64_t media_pipeline_time_us(const FrameTelemetry& telemetry) noexcept {
    return telemetry.capture_time_us + telemetry.encode_time_us + telemetry.network_time_us +
           telemetry.decode_time_us + telemetry.render_time_us;
}

RollingSampleWindow::RollingSampleWindow(std::span<std::uint64_t> storage) noexcept
    : storage_(storage) {}

void RollingSampleWindow::add(std::uint64_t sample) noexcept {
    if (storage_.empty()) {
        return;
    }

    if (sample_count_ == storage_.size()) {
        sum_ -= static_cast<long double>(storage_[next_sample_]);
    } else {
        ++sample_count_;
    }

    storage_[next_sample_] = sample;
    next_sample_ = (next_sample_ + 1U) % storage_.size();
    sum_ += static_cast<long double>(sample);

    if (has_previous_) {
        const auto delta = static_cast<double>(absolute_difference(sample, previous_));
        jitter_ += (delta - jitter_) / 16.0;
    }

    latest_ = sample;
    previous_ = sample;
    has_previous_ = true;
}

void RollingSampleWindow::reset() noexcept {
    sample_count_ = 0;
    next_sample_ = 0;
    latest_ = 0;
    previous_ = 0;
    has_previous_ = false;
    sum_ = 0.0L;
    jitter_ = 0.0;
}

RollingStatsSnapshot RollingSampleWindow::snapshot() const noexcept {
    if (sample_count_ == 0) {
        return RollingStatsSnapshot{};
    }

    std::uint64_t minimum = storage_[0];
    std::uint64_t maximum = storage_[0];
    for (std::size_t i = 1; i < sample_count_; ++i) {
        minimum = std::min(minimum, storage_[i]);
        maximum = std::max(maximum, storage_[i]);
    }

    return RollingStatsSnapshot{
        .sample_count = sample_count_,
        .latest = latest_,
        .minimum = minimum,
        .maximum = maximum,
        .mean = static_cast<double>(sum_ / static_cast<long double>(sample_count_)),
        .jitter = jitter_,
    };
}

NetworkTelemetry::NetworkTelemetry(NetworkTelemetryStorage storage) noexcept
    : rtt_window_(storage.rtt_samples), one_way_window_(storage.one_way_delay_samples) {}

void NetworkTelemetry::add_counter(std::uint64_t& counter, std::uint64_t delta) noexcept {
    counter = saturated_add(counter, delta, counters_.counter_saturated);
}

void NetworkTelemetry::record_datagram_sent(std::uint64_t bytes) noexcept {
    add_counter(counters_.datagrams_sent, 1);
    add_counter(counters_.bytes_sent, bytes);
}

void NetworkTelemetry::record_datagram_received(std::uint64_t bytes) noexcept {
    add_counter(counters_.datagrams_received, 1);
    add_counter(counters_.bytes_received, bytes);
}

void NetworkTelemetry::record_send_would_block() noexcept {
    add_counter(counters_.send_would_block, 1);
}

void NetworkTelemetry::record_receive_would_block() noexcept {
    add_counter(counters_.receive_would_block, 1);
}

void NetworkTelemetry::record_datagram_truncated() noexcept {
    add_counter(counters_.datagrams_truncated, 1);
}

void NetworkTelemetry::record_gap_detected(std::uint64_t missing_count) noexcept {
    add_counter(counters_.sequence_gaps_detected, missing_count);
}

void NetworkTelemetry::record_late_packet() noexcept {
    add_counter(counters_.late_packets, 1);
}

void NetworkTelemetry::record_duplicate_packet() noexcept {
    add_counter(counters_.duplicate_packets, 1);
}

void NetworkTelemetry::record_nack_generated(std::uint64_t sequence_count) noexcept {
    add_counter(counters_.nack_messages_generated, 1);
    add_counter(counters_.nack_sequences_requested, sequence_count);
}

void NetworkTelemetry::record_nack_received() noexcept {
    add_counter(counters_.nack_messages_received, 1);
}

void NetworkTelemetry::record_retransmission_sent() noexcept {
    add_counter(counters_.retransmissions_sent, 1);
}

void NetworkTelemetry::record_retransmission_received_or_observed() noexcept {
    add_counter(counters_.retransmissions_received_or_observed, 1);
}

void NetworkTelemetry::record_fec_block_encoded(std::uint64_t parity_shards_generated) noexcept {
    add_counter(counters_.fec_blocks_encoded, 1);
    add_counter(counters_.fec_parity_shards_generated, parity_shards_generated);
}

void NetworkTelemetry::record_fec_recovery_attempt(bool success,
                                                   std::uint64_t data_shards_recovered) noexcept {
    add_counter(counters_.fec_recovery_attempts, 1);
    if (success) {
        add_counter(counters_.fec_recovery_successes, 1);
        add_counter(counters_.fec_data_shards_recovered, data_shards_recovered);
    } else {
        add_counter(counters_.fec_recovery_failures, 1);
    }
}

void NetworkTelemetry::record_clock_request_sent() noexcept {
    add_counter(counters_.clock_requests_sent, 1);
}

void NetworkTelemetry::record_clock_response_received() noexcept {
    add_counter(counters_.clock_responses_received, 1);
}

void NetworkTelemetry::record_clock_sample_accepted(std::uint64_t rtt_us) noexcept {
    add_counter(counters_.clock_samples_accepted, 1);
    rtt_window_.add(rtt_us);
}

void NetworkTelemetry::record_clock_sample_rejected() noexcept {
    add_counter(counters_.clock_samples_rejected, 1);
}

void NetworkTelemetry::record_one_way_delay(std::uint64_t delay_us) noexcept {
    one_way_window_.add(delay_us);
}

NetworkTelemetrySnapshot
NetworkTelemetry::snapshot(const ClockModelSnapshot& clock_model) const noexcept {
    return NetworkTelemetrySnapshot{
        .counters = counters_,
        .rtt = rtt_window_.snapshot(),
        .one_way_delay = one_way_window_.snapshot(),
        .clock_model = clock_model,
    };
}

void NetworkTelemetry::reset() noexcept {
    counters_ = NetworkTelemetryCounters{};
    rtt_window_.reset();
    one_way_window_.reset();
}

} // namespace warpnect::scl

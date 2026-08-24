#ifndef WARPNECT_SCL_TELEMETRY_H_
#define WARPNECT_SCL_TELEMETRY_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "clock_sync.h"

namespace warpnect::scl {

class RuntimeNetworkTelemetry;

struct StageTimingUs final {
    std::uint64_t started_us;
    std::uint64_t completed_us;
};

struct FrameTelemetry final {
    std::uint64_t capture_time_us;
    std::uint64_t encode_time_us;
    std::uint64_t network_time_us;
    std::uint64_t decode_time_us;
    std::uint64_t render_time_us;
    std::uint64_t input_round_trip_time_us;
};

[[nodiscard]] std::uint64_t media_pipeline_time_us(const FrameTelemetry& telemetry) noexcept;

struct RollingStatsSnapshot final {
    std::size_t sample_count = 0;
    std::uint64_t latest = 0;
    std::uint64_t minimum = 0;
    std::uint64_t maximum = 0;
    double mean = 0.0;
    double jitter = 0.0;
};

class RollingSampleWindow final {
  public:
    explicit RollingSampleWindow(std::span<std::uint64_t> storage) noexcept;

    void add(std::uint64_t sample) noexcept;
    void reset() noexcept;

    [[nodiscard]] RollingStatsSnapshot snapshot() const noexcept;

    [[nodiscard]] std::size_t sample_count() const noexcept {
        return sample_count_;
    }

  private:
    std::span<std::uint64_t> storage_{};
    std::size_t sample_count_ = 0;
    std::size_t next_sample_ = 0;
    std::uint64_t latest_ = 0;
    std::uint64_t previous_ = 0;
    bool has_previous_ = false;
    long double sum_ = 0.0;
    double jitter_ = 0.0;
};

struct NetworkTelemetryCounters final {
    std::uint64_t datagrams_sent = 0;
    std::uint64_t datagrams_received = 0;
    std::uint64_t bytes_sent = 0;
    std::uint64_t bytes_received = 0;
    std::uint64_t send_would_block = 0;
    std::uint64_t receive_would_block = 0;
    std::uint64_t datagrams_truncated = 0;
    std::uint64_t sequence_gaps_detected = 0;
    std::uint64_t late_packets = 0;
    std::uint64_t duplicate_packets = 0;
    std::uint64_t nack_messages_generated = 0;
    std::uint64_t nack_sequences_requested = 0;
    std::uint64_t nack_messages_received = 0;
    std::uint64_t retransmissions_sent = 0;
    std::uint64_t retransmissions_received_or_observed = 0;
    std::uint64_t fec_blocks_encoded = 0;
    std::uint64_t fec_parity_shards_generated = 0;
    std::uint64_t fec_recovery_attempts = 0;
    std::uint64_t fec_recovery_successes = 0;
    std::uint64_t fec_recovery_failures = 0;
    std::uint64_t fec_data_shards_recovered = 0;
    std::uint64_t clock_requests_sent = 0;
    std::uint64_t clock_responses_received = 0;
    std::uint64_t clock_samples_accepted = 0;
    std::uint64_t clock_samples_rejected = 0;
    bool counter_saturated = false;
};

struct NetworkTelemetryStorage final {
    std::span<std::uint64_t> rtt_samples{};
    std::span<std::uint64_t> one_way_delay_samples{};
    RuntimeNetworkTelemetry* runtime_network_telemetry = nullptr;
};

struct NetworkTelemetrySnapshot final {
    NetworkTelemetryCounters counters{};
    RollingStatsSnapshot rtt{};
    RollingStatsSnapshot one_way_delay{};
    ClockModelSnapshot clock_model{};
};

class NetworkTelemetry final {
  public:
    explicit NetworkTelemetry(NetworkTelemetryStorage storage) noexcept;

    void record_datagram_sent(std::uint64_t bytes) noexcept;
    void record_datagram_received(std::uint64_t bytes) noexcept;
    void record_send_would_block() noexcept;
    void record_receive_would_block() noexcept;
    void record_datagram_truncated() noexcept;
    void record_gap_detected(std::uint64_t missing_count) noexcept;
    void record_late_packet() noexcept;
    void record_duplicate_packet() noexcept;
    void record_nack_generated(std::uint64_t sequence_count) noexcept;
    void record_nack_received() noexcept;
    void record_retransmission_sent() noexcept;
    void record_retransmission_received_or_observed() noexcept;
    void record_fec_block_encoded(std::uint64_t parity_shards_generated) noexcept;
    void record_fec_recovery_attempt(bool success, std::uint64_t data_shards_recovered) noexcept;
    void record_clock_request_sent() noexcept;
    void record_clock_response_received() noexcept;
    void record_clock_sample_accepted(std::uint64_t rtt_us) noexcept;
    void record_clock_sample_rejected() noexcept;
    void record_one_way_delay(std::uint64_t delay_us) noexcept;

    [[nodiscard]] NetworkTelemetrySnapshot
    snapshot(const ClockModelSnapshot& clock_model) const noexcept;

    void reset() noexcept;

  private:
    void add_counter(std::uint64_t& counter, std::uint64_t delta) noexcept;

    NetworkTelemetryCounters counters_{};
    RuntimeNetworkTelemetry* runtime_network_telemetry_ = nullptr;
    RollingSampleWindow rtt_window_;
    RollingSampleWindow one_way_window_;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_TELEMETRY_H_

#ifndef WARPNECT_SCL_RUNTIME_NETWORK_TELEMETRY_H_
#define WARPNECT_SCL_RUNTIME_NETWORK_TELEMETRY_H_

#include <cstdint>
#include <memory>

#include "runtime_telemetry.h"

namespace warpnect::scl {

/**
 * RFC-006C's pre-bound native Channel telemetry bundle. Source lookup happens once while a
 * stopped transport is adopted; packet-path calls below are relaxed atomic updates only.
 */
class RuntimeNetworkTelemetry final {
  public:
    explicit RuntimeNetworkTelemetry(
        std::shared_ptr<runtime_telemetry::RuntimeTelemetrySource> source) noexcept
        : source_(std::move(source)) {
        if (source_ == nullptr) return;
        udp_datagram_sent_ = source_->counter(0x0201);
        udp_byte_sent_ = source_->counter(0x0202);
        udp_datagram_received_ = source_->counter(0x0203);
        udp_byte_received_ = source_->counter(0x0204);
        udp_send_would_block_ = source_->counter(0x0205);
        udp_send_error_ = source_->counter(0x0206);
        udp_receive_error_ = source_->counter(0x0207);
        path_unavailable_drop_ = source_->counter(0x0208);
        socket_rebind_ = source_->counter(0x0209);
        fec_data_shard_emitted_ = source_->counter(0x0221);
        fec_parity_shard_emitted_ = source_->counter(0x0222);
        fec_recovery_attempt_ = source_->counter(0x0223);
        fec_shard_recovered_ = source_->counter(0x0224);
        fec_recovery_completed_ = source_->counter(0x0225);
        fec_recovery_failed_ = source_->counter(0x0226);
        nack_generated_ = source_->counter(0x0231);
        nack_received_ = source_->counter(0x0232);
        retransmission_sent_ = source_->counter(0x0233);
        retransmission_cache_miss_ = source_->counter(0x0234);
        reassembly_fragment_accepted_ = source_->counter(0x0241);
        reassembly_completed_ = source_->counter(0x0242);
        reassembly_timeout_ = source_->counter(0x0243);
        reassembly_evicted_ = source_->counter(0x0244);
        protection_record_produced_ = source_->counter(0x0701);
        protection_record_accepted_ = source_->counter(0x0702);
        protection_protect_error_ = source_->counter(0x0703);
        protection_authentication_failed_ = source_->counter(0x0704);
        protection_replay_dropped_ = source_->counter(0x0705);
        protection_unknown_context_ = source_->counter(0x0706);
        protection_endpoint_mismatch_ = source_->counter(0x0707);
        protection_epoch_rejected_ = source_->counter(0x0708);
        protection_malformed_ = source_->counter(0x0709);
    }

    void udp_sent(std::uint64_t bytes) const noexcept { increment(udp_datagram_sent_); add(udp_byte_sent_, bytes); }
    void udp_received(std::uint64_t bytes) const noexcept { increment(udp_datagram_received_); add(udp_byte_received_, bytes); }
    void udp_would_block() const noexcept { increment(udp_send_would_block_); }
    void udp_send_error() const noexcept { increment(udp_send_error_); }
    void udp_receive_error() const noexcept { increment(udp_receive_error_); }
    void path_unavailable_drop() const noexcept { increment(path_unavailable_drop_); }
    void socket_rebind() const noexcept { increment(socket_rebind_); }
    void fec_data_shard_emitted() const noexcept { increment(fec_data_shard_emitted_); }
    void fec_parity_shard_emitted() const noexcept { increment(fec_parity_shard_emitted_); }
    void fec_recovery(bool completed, std::uint64_t recovered) const noexcept {
        increment(fec_recovery_attempt_);
        if (completed) { increment(fec_recovery_completed_); add(fec_shard_recovered_, recovered); }
        else increment(fec_recovery_failed_);
    }
    void nack_generated() const noexcept { increment(nack_generated_); }
    void nack_received() const noexcept { increment(nack_received_); }
    void retransmission_sent() const noexcept { increment(retransmission_sent_); }
    void retransmission_cache_miss() const noexcept { increment(retransmission_cache_miss_); }
    void reassembly_fragment_accepted() const noexcept { increment(reassembly_fragment_accepted_); }
    void reassembly_completed() const noexcept { increment(reassembly_completed_); }
    void reassembly_timeout() const noexcept { increment(reassembly_timeout_); }
    void reassembly_evicted() const noexcept { increment(reassembly_evicted_); }
    void protection_record_produced() const noexcept { increment(protection_record_produced_); }
    void protection_record_accepted() const noexcept { increment(protection_record_accepted_); }
    void protection_protect_error() const noexcept { increment(protection_protect_error_); }
    void protection_authentication_failed() const noexcept { increment(protection_authentication_failed_); }
    void protection_replay_dropped() const noexcept { increment(protection_replay_dropped_); }
    void protection_unknown_context() const noexcept { increment(protection_unknown_context_); }
    void protection_endpoint_mismatch() const noexcept { increment(protection_endpoint_mismatch_); }
    void protection_epoch_rejected() const noexcept { increment(protection_epoch_rejected_); }
    void protection_malformed() const noexcept { increment(protection_malformed_); }

  private:
    using Counter = runtime_telemetry::RuntimeTelemetryCounterU64;
    static void increment(Counter* counter) noexcept { if (counter != nullptr) counter->increment(); }
    static void add(Counter* counter, std::uint64_t value) noexcept { if (counter != nullptr) counter->add(value); }

    std::shared_ptr<runtime_telemetry::RuntimeTelemetrySource> source_{};
    Counter *udp_datagram_sent_ = nullptr, *udp_byte_sent_ = nullptr, *udp_datagram_received_ = nullptr,
            *udp_byte_received_ = nullptr, *udp_send_would_block_ = nullptr, *udp_send_error_ = nullptr,
            *udp_receive_error_ = nullptr, *path_unavailable_drop_ = nullptr, *socket_rebind_ = nullptr,
            *fec_data_shard_emitted_ = nullptr, *fec_parity_shard_emitted_ = nullptr,
            *fec_recovery_attempt_ = nullptr, *fec_shard_recovered_ = nullptr,
            *fec_recovery_completed_ = nullptr, *fec_recovery_failed_ = nullptr,
            *nack_generated_ = nullptr, *nack_received_ = nullptr, *retransmission_sent_ = nullptr,
            *retransmission_cache_miss_ = nullptr, *reassembly_fragment_accepted_ = nullptr,
            *reassembly_completed_ = nullptr, *reassembly_timeout_ = nullptr, *reassembly_evicted_ = nullptr,
            *protection_record_produced_ = nullptr, *protection_record_accepted_ = nullptr,
            *protection_protect_error_ = nullptr, *protection_authentication_failed_ = nullptr,
            *protection_replay_dropped_ = nullptr, *protection_unknown_context_ = nullptr,
            *protection_endpoint_mismatch_ = nullptr, *protection_epoch_rejected_ = nullptr,
            *protection_malformed_ = nullptr;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_RUNTIME_NETWORK_TELEMETRY_H_

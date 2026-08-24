#ifndef WARPNECT_SCL_RUNTIME_CLOCK_SYNC_TELEMETRY_H_
#define WARPNECT_SCL_RUNTIME_CLOCK_SYNC_TELEMETRY_H_

#include <cmath>
#include <cstdint>
#include <memory>

#include "clock_sync.h"
#include "runtime_telemetry.h"

namespace warpnect::scl {

/** Pre-bound RFC-006D ClockSync observations updated on the existing receiver control path. */
class RuntimeClockSyncTelemetry final {
  public:
    explicit RuntimeClockSyncTelemetry(
        std::shared_ptr<runtime_telemetry::RuntimeTelemetrySource> source) noexcept
        : source_(std::move(source)) {
        if (source_ == nullptr) return;
        sample_accepted_ = source_->counter(0x0601);
        sample_rejected_ = source_->counter(0x0602);
        qualified_ = source_->gauge(0x0603);
        offset_ = source_->gauge(0x0604);
        round_trip_ = source_->histogram(0x0606);
    }

    void accepted(const ClockModelSnapshot& snapshot) const noexcept {
        increment(sample_accepted_);
        if (qualified_ != nullptr) {
            qualified_->set(snapshot.state == ClockSyncState::Synchronized ? 1 : 0);
        }
        const double offset = snapshot.reference_local_us - snapshot.reference_remote_us;
        if (offset_ != nullptr) {
            if (std::isfinite(offset) && offset >= static_cast<double>(INT64_MIN) &&
                offset <= static_cast<double>(INT64_MAX)) {
                offset_->set(static_cast<std::int64_t>(offset));
            } else {
                offset_->clear();
            }
        }
        if (round_trip_ != nullptr) round_trip_->record(snapshot.latest_rtt_us);
    }

    void rejected() const noexcept { increment(sample_rejected_); }

  private:
    using Counter = runtime_telemetry::RuntimeTelemetryCounterU64;

    static void increment(Counter* counter) noexcept {
        if (counter != nullptr) counter->increment();
    }

    std::shared_ptr<runtime_telemetry::RuntimeTelemetrySource> source_{};
    Counter* sample_accepted_ = nullptr;
    Counter* sample_rejected_ = nullptr;
    runtime_telemetry::RuntimeTelemetryGaugeI64* qualified_ = nullptr;
    runtime_telemetry::RuntimeTelemetryGaugeI64* offset_ = nullptr;
    runtime_telemetry::RuntimeTelemetryHistogramU64* round_trip_ = nullptr;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_RUNTIME_CLOCK_SYNC_TELEMETRY_H_

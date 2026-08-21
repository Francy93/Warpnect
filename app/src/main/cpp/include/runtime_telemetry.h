#ifndef WARPNECT_SCL_RUNTIME_TELEMETRY_H_
#define WARPNECT_SCL_RUNTIME_TELEMETRY_H_

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <span>
#include <vector>

namespace warpnect::scl::runtime_telemetry {

inline constexpr std::uint16_t kRuntimeTelemetryModelVersion = 1;
inline constexpr std::size_t kMaxTelemetrySources = 512;
inline constexpr std::size_t kMaxMetricsPerSource = 32;
inline constexpr std::size_t kMaxHistogramsPerSource = 8;
inline constexpr std::size_t kMaxHistogramBoundaries = 16;
inline constexpr std::size_t kMaxSnapshotRecords = 16'384;
inline constexpr std::size_t kMaxSnapshotBytes = 256U * 1024U;
inline constexpr std::size_t kSnapshotHeaderBytes = 32;
inline constexpr std::size_t kSnapshotRecordHeaderBytes = 16;

enum class RuntimeTelemetryMetricKind : std::uint8_t {
    CounterU64 = 1,
    GaugeI64 = 2,
    HistogramU64 = 3,
};

enum class RuntimeTelemetrySnapshotStatus : std::uint32_t {
    Success = 0,
    BufferTooSmall = 1,
    RecordLimitExceeded = 2,
    Closed = 3,
};

class RuntimeTelemetryCounterU64 final {
  public:
    void increment() noexcept;
    void add(std::uint64_t delta) noexcept;

    [[nodiscard]] std::uint64_t value() const noexcept;
    [[nodiscard]] bool overflowed() const noexcept;

  private:
    std::atomic<std::uint64_t> value_{0};
    std::atomic<bool> overflowed_{false};
};

class RuntimeTelemetryGaugeI64 final {
  public:
    void set(std::int64_t value) noexcept;
    void clear() noexcept;

    [[nodiscard]] bool valid() const noexcept;
    [[nodiscard]] std::int64_t value() const noexcept;

  private:
    std::atomic<std::int64_t> value_{0};
    std::atomic<bool> valid_{false};
};

struct RuntimeTelemetryHistogramSnapshot final {
    std::uint64_t count = 0;
    std::uint64_t sum = 0;
    std::uint64_t min = 0;
    std::uint64_t max = 0;
    bool has_value = false;
    std::vector<std::uint64_t> bucket_counts{};
};

class RuntimeTelemetryHistogramU64 final {
  public:
    explicit RuntimeTelemetryHistogramU64(std::vector<std::uint64_t> boundaries);

    void record(std::uint64_t value) noexcept;

    [[nodiscard]] const std::vector<std::uint64_t>& boundaries() const noexcept;
    [[nodiscard]] RuntimeTelemetryHistogramSnapshot snapshot() const;

  private:
    void update_min(std::uint64_t value) noexcept;
    void update_max(std::uint64_t value) noexcept;

    std::vector<std::uint64_t> boundaries_{};
    std::unique_ptr<std::atomic<std::uint64_t>[]> bucket_counts_{};
    std::size_t bucket_count_ = 0;
    RuntimeTelemetryCounterU64 count_{};
    RuntimeTelemetryCounterU64 sum_{};
    std::atomic<std::uint64_t> min_{UINT64_MAX};
    std::atomic<std::uint64_t> max_{0};
    std::atomic<bool> bucket_overflowed_{false};
};

struct RuntimeTelemetryMetricDefinition final {
    std::uint16_t metric_id = 0;
    RuntimeTelemetryMetricKind kind = RuntimeTelemetryMetricKind::CounterU64;
    std::vector<std::uint64_t> histogram_boundaries{};
};

class RuntimeTelemetrySource final {
  public:
    RuntimeTelemetrySource(std::uint32_t source_id,
                           std::vector<RuntimeTelemetryMetricDefinition> definitions);
    ~RuntimeTelemetrySource();

    [[nodiscard]] std::uint32_t source_id() const noexcept;
    [[nodiscard]] RuntimeTelemetryCounterU64* counter(std::uint16_t metric_id) noexcept;
    [[nodiscard]] RuntimeTelemetryGaugeI64* gauge(std::uint16_t metric_id) noexcept;
    [[nodiscard]] RuntimeTelemetryHistogramU64* histogram(std::uint16_t metric_id) noexcept;
    [[nodiscard]] std::size_t record_count() const noexcept;
    [[nodiscard]] std::size_t encoded_bytes() const noexcept;

  private:
    struct Instrument final {
        RuntimeTelemetryMetricDefinition definition{};
        std::unique_ptr<RuntimeTelemetryCounterU64> counter{};
        std::unique_ptr<RuntimeTelemetryGaugeI64> gauge{};
        std::unique_ptr<RuntimeTelemetryHistogramU64> histogram{};
    };
    friend class RuntimeTelemetryRegistry;

    std::uint32_t source_id_ = 0;
    std::vector<Instrument> instruments_{};
};

struct RuntimeTelemetrySourceRegistration final {
    std::shared_ptr<RuntimeTelemetrySource> source{};
    RuntimeTelemetrySnapshotStatus status = RuntimeTelemetrySnapshotStatus::Success;
};

struct RuntimeTelemetrySnapshotResult final {
    RuntimeTelemetrySnapshotStatus status = RuntimeTelemetrySnapshotStatus::Success;
    std::size_t required_bytes = 0;
    std::size_t bytes_written = 0;
    std::uint64_t sequence = 0;
    std::uint64_t source_monotonic_ns = 0;
};

/** Registry operations are cold-path; counter/gauge/histogram updates never acquire its mutex. */
class RuntimeTelemetryRegistry final {
  public:
    [[nodiscard]] RuntimeTelemetrySourceRegistration register_source(
        std::vector<RuntimeTelemetryMetricDefinition> definitions);
    void unregister_source(std::uint32_t source_id) noexcept;
    [[nodiscard]] RuntimeTelemetrySnapshotResult snapshot_into(std::span<std::byte> output);

  private:
    std::mutex mutex_{};
    std::vector<std::shared_ptr<RuntimeTelemetrySource>> sources_{};
    std::atomic<std::uint32_t> next_source_id_{1};
    std::atomic<std::uint64_t> next_snapshot_sequence_{0};
};

/** Process-local native registry consumed by the additive JNI snapshot bridge. */
[[nodiscard]] RuntimeTelemetryRegistry& runtime_telemetry_registry() noexcept;

} // namespace warpnect::scl::runtime_telemetry

#endif // WARPNECT_SCL_RUNTIME_TELEMETRY_H_

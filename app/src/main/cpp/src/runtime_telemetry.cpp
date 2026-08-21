#include "runtime_telemetry.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <limits>
#include <mutex>
#include <utility>

namespace warpnect::scl::runtime_telemetry {
namespace {

[[nodiscard]] constexpr std::size_t payload_bytes(const RuntimeTelemetryMetricKind kind,
                                                   const std::size_t bucket_count) noexcept {
    switch (kind) {
        case RuntimeTelemetryMetricKind::CounterU64:
            return 8;
        case RuntimeTelemetryMetricKind::GaugeI64:
            return 16;
        case RuntimeTelemetryMetricKind::HistogramU64:
            return 40 + (bucket_count * sizeof(std::uint64_t));
    }
    return 0;
}

void write_u16_le(std::span<std::byte> output, const std::size_t offset,
                  const std::uint16_t value) noexcept {
    output[offset] = static_cast<std::byte>(value & 0xffU);
    output[offset + 1] = static_cast<std::byte>((value >> 8U) & 0xffU);
}

void write_u32_le(std::span<std::byte> output, const std::size_t offset,
                  const std::uint32_t value) noexcept {
    for (std::size_t index = 0; index < sizeof(value); ++index) {
        output[offset + index] = static_cast<std::byte>((value >> (index * 8U)) & 0xffU);
    }
}

void write_u64_le(std::span<std::byte> output, const std::size_t offset,
                  const std::uint64_t value) noexcept {
    for (std::size_t index = 0; index < sizeof(value); ++index) {
        output[offset + index] = static_cast<std::byte>((value >> (index * 8U)) & 0xffU);
    }
}

[[nodiscard]] std::uint64_t monotonic_now_ns() noexcept {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    const auto count = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
    return count < 0 ? 0U : static_cast<std::uint64_t>(count);
}

[[nodiscard]] bool valid_definitions(const std::vector<RuntimeTelemetryMetricDefinition>& definitions) {
    if (definitions.empty() || definitions.size() > kMaxMetricsPerSource) {
        return false;
    }
    std::size_t histograms = 0;
    for (std::size_t index = 0; index < definitions.size(); ++index) {
        const auto& definition = definitions[index];
        if (definition.metric_id == 0) {
            return false;
        }
        for (std::size_t previous = 0; previous < index; ++previous) {
            if (definitions[previous].metric_id == definition.metric_id) {
                return false;
            }
        }
        if (definition.kind == RuntimeTelemetryMetricKind::HistogramU64) {
            ++histograms;
            if (histograms > kMaxHistogramsPerSource ||
                definition.histogram_boundaries.size() > kMaxHistogramBoundaries ||
                !std::is_sorted(definition.histogram_boundaries.begin(),
                                definition.histogram_boundaries.end()) ||
                std::adjacent_find(definition.histogram_boundaries.begin(),
                                   definition.histogram_boundaries.end(),
                                   [](const std::uint64_t left, const std::uint64_t right) {
                                       return left >= right;
                                   }) != definition.histogram_boundaries.end()) {
                return false;
            }
        } else if (!definition.histogram_boundaries.empty()) {
            return false;
        }
    }
    return true;
}

} // namespace

void RuntimeTelemetryCounterU64::increment() noexcept {
    add(1U);
}

void RuntimeTelemetryCounterU64::add(const std::uint64_t delta) noexcept {
    if (delta == 0) {
        return;
    }
    std::uint64_t current = value_.load(std::memory_order_relaxed);
    while (true) {
        const bool saturated = std::numeric_limits<std::uint64_t>::max() - current < delta;
        const std::uint64_t next = saturated ? std::numeric_limits<std::uint64_t>::max() : current + delta;
        if (value_.compare_exchange_weak(current, next, std::memory_order_relaxed,
                                         std::memory_order_relaxed)) {
            if (saturated) {
                overflowed_.store(true, std::memory_order_relaxed);
            }
            return;
        }
    }
}

std::uint64_t RuntimeTelemetryCounterU64::value() const noexcept {
    return value_.load(std::memory_order_relaxed);
}

bool RuntimeTelemetryCounterU64::overflowed() const noexcept {
    return overflowed_.load(std::memory_order_relaxed);
}

void RuntimeTelemetryGaugeI64::set(const std::int64_t value) noexcept {
    value_.store(value, std::memory_order_relaxed);
    valid_.store(true, std::memory_order_release);
}

void RuntimeTelemetryGaugeI64::clear() noexcept {
    valid_.store(false, std::memory_order_release);
}

bool RuntimeTelemetryGaugeI64::valid() const noexcept {
    return valid_.load(std::memory_order_acquire);
}

std::int64_t RuntimeTelemetryGaugeI64::value() const noexcept {
    return value_.load(std::memory_order_relaxed);
}

RuntimeTelemetryHistogramU64::RuntimeTelemetryHistogramU64(std::vector<std::uint64_t> boundaries)
    : boundaries_(std::move(boundaries)),
      bucket_counts_(std::make_unique<std::atomic<std::uint64_t>[]>(boundaries_.size() + 1)),
      bucket_count_(boundaries_.size() + 1) {
    for (std::size_t index = 0; index < bucket_count_; ++index) {
        bucket_counts_[index].store(0, std::memory_order_relaxed);
    }
}

void RuntimeTelemetryHistogramU64::record(const std::uint64_t value) noexcept {
    count_.increment();
    sum_.add(value);
    const auto iterator = std::lower_bound(boundaries_.begin(), boundaries_.end(), value);
    const auto bucket_index = static_cast<std::size_t>(iterator - boundaries_.begin());
    std::uint64_t current_bucket = bucket_counts_[bucket_index].load(std::memory_order_relaxed);
    while (current_bucket != std::numeric_limits<std::uint64_t>::max() &&
           !bucket_counts_[bucket_index].compare_exchange_weak(
               current_bucket, current_bucket + 1, std::memory_order_relaxed,
               std::memory_order_relaxed)) {
    }
    if (current_bucket == std::numeric_limits<std::uint64_t>::max()) {
        bucket_overflowed_.store(true, std::memory_order_relaxed);
    }
    update_min(value);
    update_max(value);
}

const std::vector<std::uint64_t>& RuntimeTelemetryHistogramU64::boundaries() const noexcept {
    return boundaries_;
}

RuntimeTelemetryHistogramSnapshot RuntimeTelemetryHistogramU64::snapshot() const {
    RuntimeTelemetryHistogramSnapshot result{};
    result.count = count_.value();
    result.sum = sum_.value();
    result.has_value = result.count != 0;
    result.min = result.has_value ? min_.load(std::memory_order_relaxed) : 0;
    result.max = result.has_value ? max_.load(std::memory_order_relaxed) : 0;
    result.bucket_counts.resize(bucket_count_);
    for (std::size_t index = 0; index < bucket_count_; ++index) {
        result.bucket_counts[index] = bucket_counts_[index].load(std::memory_order_relaxed);
    }
    return result;
}

void RuntimeTelemetryHistogramU64::update_min(const std::uint64_t value) noexcept {
    std::uint64_t current = min_.load(std::memory_order_relaxed);
    while (value < current && !min_.compare_exchange_weak(current, value, std::memory_order_relaxed,
                                                            std::memory_order_relaxed)) {
    }
}

void RuntimeTelemetryHistogramU64::update_max(const std::uint64_t value) noexcept {
    std::uint64_t current = max_.load(std::memory_order_relaxed);
    while (value > current && !max_.compare_exchange_weak(current, value, std::memory_order_relaxed,
                                                            std::memory_order_relaxed)) {
    }
}

RuntimeTelemetrySource::~RuntimeTelemetrySource() = default;

RuntimeTelemetrySource::RuntimeTelemetrySource(
    const std::uint32_t source_id, std::vector<RuntimeTelemetryMetricDefinition> definitions)
    : source_id_(source_id) {
    instruments_.reserve(definitions.size());
    for (auto& definition : definitions) {
        Instrument instrument{};
        instrument.definition = std::move(definition);
        switch (instrument.definition.kind) {
            case RuntimeTelemetryMetricKind::CounterU64:
                instrument.counter = std::make_unique<RuntimeTelemetryCounterU64>();
                break;
            case RuntimeTelemetryMetricKind::GaugeI64:
                instrument.gauge = std::make_unique<RuntimeTelemetryGaugeI64>();
                break;
            case RuntimeTelemetryMetricKind::HistogramU64:
                instrument.histogram = std::make_unique<RuntimeTelemetryHistogramU64>(
                    instrument.definition.histogram_boundaries);
                break;
        }
        instruments_.push_back(std::move(instrument));
    }
}

std::uint32_t RuntimeTelemetrySource::source_id() const noexcept {
    return source_id_;
}

RuntimeTelemetryCounterU64* RuntimeTelemetrySource::counter(const std::uint16_t metric_id) noexcept {
    for (auto& instrument : instruments_) {
        if (instrument.definition.metric_id == metric_id) {
            return instrument.counter.get();
        }
    }
    return nullptr;
}

RuntimeTelemetryGaugeI64* RuntimeTelemetrySource::gauge(const std::uint16_t metric_id) noexcept {
    for (auto& instrument : instruments_) {
        if (instrument.definition.metric_id == metric_id) {
            return instrument.gauge.get();
        }
    }
    return nullptr;
}

RuntimeTelemetryHistogramU64* RuntimeTelemetrySource::histogram(const std::uint16_t metric_id) noexcept {
    for (auto& instrument : instruments_) {
        if (instrument.definition.metric_id == metric_id) {
            return instrument.histogram.get();
        }
    }
    return nullptr;
}

std::size_t RuntimeTelemetrySource::record_count() const noexcept {
    return instruments_.size();
}

std::size_t RuntimeTelemetrySource::encoded_bytes() const noexcept {
    std::size_t result = 0;
    for (const auto& instrument : instruments_) {
        const std::size_t buckets = instrument.histogram == nullptr
                                        ? 0
                                        : instrument.histogram->boundaries().size() + 1;
        result += kSnapshotRecordHeaderBytes + payload_bytes(instrument.definition.kind, buckets);
    }
    return result;
}

RuntimeTelemetrySourceRegistration RuntimeTelemetryRegistry::register_source(
    std::vector<RuntimeTelemetryMetricDefinition> definitions) {
    if (!valid_definitions(definitions)) {
        return RuntimeTelemetrySourceRegistration{.status = RuntimeTelemetrySnapshotStatus::RecordLimitExceeded};
    }
    std::lock_guard lock(mutex_);
    if (sources_.size() >= kMaxTelemetrySources) {
        return RuntimeTelemetrySourceRegistration{.status = RuntimeTelemetrySnapshotStatus::RecordLimitExceeded};
    }
    std::uint32_t source_id = next_source_id_.load(std::memory_order_relaxed);
    while (source_id != 0) {
        const std::uint32_t next = source_id == std::numeric_limits<std::uint32_t>::max()
                                       ? 0
                                       : source_id + 1;
        if (next_source_id_.compare_exchange_weak(source_id, next, std::memory_order_relaxed,
                                                   std::memory_order_relaxed)) {
            break;
        }
    }
    if (source_id == 0) {
        return RuntimeTelemetrySourceRegistration{.status = RuntimeTelemetrySnapshotStatus::Closed};
    }
    auto source = std::make_shared<RuntimeTelemetrySource>(source_id, std::move(definitions));
    sources_.push_back(source);
    return RuntimeTelemetrySourceRegistration{.source = std::move(source)};
}

void RuntimeTelemetryRegistry::unregister_source(const std::uint32_t source_id) noexcept {
    std::lock_guard lock(mutex_);
    sources_.erase(std::remove_if(sources_.begin(), sources_.end(),
                                  [source_id](const auto& source) {
                                      return source->source_id() == source_id;
                                  }),
                   sources_.end());
}

RuntimeTelemetrySnapshotResult RuntimeTelemetryRegistry::snapshot_into(std::span<std::byte> output) {
    std::vector<std::shared_ptr<RuntimeTelemetrySource>> sources;
    {
        std::lock_guard lock(mutex_);
        sources = sources_;
    }
    std::size_t record_count = 0;
    std::size_t required_bytes = kSnapshotHeaderBytes;
    for (const auto& source : sources) {
        record_count += source->record_count();
        required_bytes += source->encoded_bytes();
    }
    const std::uint64_t sequence = next_snapshot_sequence_.fetch_add(1, std::memory_order_relaxed) + 1;
    const std::uint64_t timestamp = monotonic_now_ns();
    if (record_count > kMaxSnapshotRecords) {
        return RuntimeTelemetrySnapshotResult{
            .status = RuntimeTelemetrySnapshotStatus::RecordLimitExceeded,
            .required_bytes = required_bytes,
            .sequence = sequence,
            .source_monotonic_ns = timestamp,
        };
    }
    if (required_bytes > kMaxSnapshotBytes || output.size() < required_bytes) {
        return RuntimeTelemetrySnapshotResult{
            .status = RuntimeTelemetrySnapshotStatus::BufferTooSmall,
            .required_bytes = required_bytes,
            .sequence = sequence,
            .source_monotonic_ns = timestamp,
        };
    }

    std::fill(output.begin(), output.begin() + static_cast<std::ptrdiff_t>(required_bytes), std::byte{0});
    output[0] = std::byte{'W'};
    output[1] = std::byte{'N'};
    output[2] = std::byte{'T'};
    output[3] = std::byte{'M'};
    write_u16_le(output, 4, kRuntimeTelemetryModelVersion);
    write_u16_le(output, 6, kSnapshotHeaderBytes);
    write_u64_le(output, 8, sequence);
    write_u64_le(output, 16, timestamp);
    write_u32_le(output, 24, static_cast<std::uint32_t>(record_count));
    write_u32_le(output, 28, static_cast<std::uint32_t>(required_bytes));

    std::size_t offset = kSnapshotHeaderBytes;
    for (const auto& source : sources) {
        for (const auto& instrument : source->instruments_) {
            const std::size_t buckets = instrument.histogram == nullptr
                                            ? 0
                                            : instrument.histogram->boundaries().size() + 1;
            const std::size_t body_bytes = payload_bytes(instrument.definition.kind, buckets);
            write_u32_le(output, offset, source->source_id());
            write_u16_le(output, offset + 4, instrument.definition.metric_id);
            output[offset + 6] = static_cast<std::byte>(instrument.definition.kind);
            output[offset + 7] = std::byte{0};
            write_u16_le(output, offset + 8, static_cast<std::uint16_t>(body_bytes));
            offset += kSnapshotRecordHeaderBytes;
            switch (instrument.definition.kind) {
                case RuntimeTelemetryMetricKind::CounterU64:
                    write_u64_le(output, offset, instrument.counter->value());
                    break;
                case RuntimeTelemetryMetricKind::GaugeI64:
                    output[offset] = instrument.gauge->valid() ? std::byte{1} : std::byte{0};
                    write_u64_le(output, offset + 8,
                                 static_cast<std::uint64_t>(instrument.gauge->value()));
                    break;
                case RuntimeTelemetryMetricKind::HistogramU64: {
                    const auto snapshot = instrument.histogram->snapshot();
                    write_u64_le(output, offset, snapshot.count);
                    write_u64_le(output, offset + 8, snapshot.sum);
                    write_u64_le(output, offset + 16, snapshot.min);
                    write_u64_le(output, offset + 24, snapshot.max);
                    output[offset + 32] = static_cast<std::byte>(instrument.histogram->boundaries().size());
                    for (std::size_t index = 0; index < snapshot.bucket_counts.size(); ++index) {
                        write_u64_le(output, offset + 40 + (index * sizeof(std::uint64_t)),
                                     snapshot.bucket_counts[index]);
                    }
                    break;
                }
            }
            offset += body_bytes;
        }
    }
    return RuntimeTelemetrySnapshotResult{
        .required_bytes = required_bytes,
        .bytes_written = required_bytes,
        .sequence = sequence,
        .source_monotonic_ns = timestamp,
    };
}

RuntimeTelemetryRegistry& runtime_telemetry_registry() noexcept {
    static RuntimeTelemetryRegistry registry{};
    return registry;
}

} // namespace warpnect::scl::runtime_telemetry

#include "runtime_telemetry.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <limits>
#include <thread>
#include <vector>

namespace {

using warpnect::scl::runtime_telemetry::RuntimeTelemetryCounterU64;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryGaugeI64;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryHistogramU64;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryMetricDefinition;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryMetricKind;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryRegistry;
using warpnect::scl::runtime_telemetry::RuntimeTelemetrySnapshotStatus;

int failures = 0;

void expect(const bool condition, const char* const message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

[[nodiscard]] std::uint16_t read_u16(const std::span<const std::byte> bytes,
                                     const std::size_t offset) {
    return static_cast<std::uint16_t>(std::to_integer<std::uint8_t>(bytes[offset])) |
           (static_cast<std::uint16_t>(std::to_integer<std::uint8_t>(bytes[offset + 1])) << 8U);
}

[[nodiscard]] std::uint32_t read_u32(const std::span<const std::byte> bytes,
                                     const std::size_t offset) {
    std::uint32_t result = 0;
    for (std::size_t index = 0; index < sizeof(result); ++index) {
        result |= static_cast<std::uint32_t>(std::to_integer<std::uint8_t>(bytes[offset + index])) <<
                  (index * 8U);
    }
    return result;
}

[[nodiscard]] std::uint64_t read_u64(const std::span<const std::byte> bytes,
                                     const std::size_t offset) {
    std::uint64_t result = 0;
    for (std::size_t index = 0; index < sizeof(result); ++index) {
        result |= static_cast<std::uint64_t>(std::to_integer<std::uint8_t>(bytes[offset + index])) <<
                  (index * 8U);
    }
    return result;
}

void test_primitives() {
    RuntimeTelemetryCounterU64 counter{};
    counter.increment();
    counter.add(4);
    expect(counter.value() == 5, "counter increments and adds");
    counter.add(std::numeric_limits<std::uint64_t>::max());
    expect(counter.value() == std::numeric_limits<std::uint64_t>::max(), "counter saturates");
    expect(counter.overflowed(), "counter reports saturation");

    RuntimeTelemetryGaugeI64 gauge{};
    expect(!gauge.valid(), "gauge starts unavailable");
    gauge.set(-1);
    expect(gauge.valid() && gauge.value() == -1, "negative gauge values remain valid");
    gauge.clear();
    expect(!gauge.valid(), "gauge clear removes validity without a sentinel");

    RuntimeTelemetryHistogramU64 histogram({10, 20});
    histogram.record(9);
    histogram.record(10);
    histogram.record(11);
    histogram.record(20);
    histogram.record(21);
    const auto snapshot = histogram.snapshot();
    expect(snapshot.count == 5 && snapshot.sum == 71, "histogram summary is cumulative");
    expect(snapshot.min == 9 && snapshot.max == 21, "histogram min/max are retained");
    expect(snapshot.bucket_counts.size() == 3, "histogram includes infinity bucket");
    expect(snapshot.bucket_counts[0] == 2 && snapshot.bucket_counts[1] == 2 &&
               snapshot.bucket_counts[2] == 1,
           "histogram boundaries use inclusive finite buckets");
}

void test_histogram_concurrency() {
    RuntimeTelemetryHistogramU64 histogram({100});
    constexpr std::size_t kThreads = 4;
    constexpr std::size_t kRecordsPerThread = 2'000;
    std::array<std::thread, kThreads> threads{};
    for (auto& thread : threads) {
        thread = std::thread([&histogram]() {
            for (std::size_t index = 0; index < kRecordsPerThread; ++index) {
                histogram.record(50);
            }
        });
    }
    for (auto& thread : threads) {
        thread.join();
    }
    const auto snapshot = histogram.snapshot();
    const std::uint64_t expected = kThreads * kRecordsPerThread;
    expect(snapshot.count == expected && snapshot.sum == expected * 50,
           "concurrent histogram count and sum are correct");
    expect(snapshot.bucket_counts[0] + snapshot.bucket_counts[1] == expected,
           "concurrent histogram bucket counts sum to count");
}

void test_registry_snapshot_and_buffer_bound() {
    RuntimeTelemetryRegistry registry{};
    const auto registration = registry.register_source({
        RuntimeTelemetryMetricDefinition{.metric_id = 1, .kind = RuntimeTelemetryMetricKind::CounterU64},
        RuntimeTelemetryMetricDefinition{.metric_id = 2, .kind = RuntimeTelemetryMetricKind::GaugeI64},
        RuntimeTelemetryMetricDefinition{
            .metric_id = 7,
            .kind = RuntimeTelemetryMetricKind::HistogramU64,
            .histogram_boundaries = {100, 200},
        },
    });
    expect(registration.source != nullptr, "registry registers a bounded source");
    registration.source->counter(1)->add(42);
    registration.source->gauge(2)->set(-9);
    registration.source->histogram(7)->record(101);

    std::array<std::byte, 16> too_small{};
    too_small.fill(std::byte{0x5a});
    const auto small = registry.snapshot_into(too_small);
    expect(small.status == RuntimeTelemetrySnapshotStatus::BufferTooSmall,
           "too-small snapshot reports required capacity");
    expect(too_small[0] == std::byte{0x5a}, "too-small snapshot does not write a partial bridge");

    std::array<std::byte, 1024> output{};
    const auto result = registry.snapshot_into(output);
    expect(result.status == RuntimeTelemetrySnapshotStatus::Success, "registry serializes WNTM snapshot");
    expect(result.bytes_written == result.required_bytes && result.bytes_written > 32,
           "snapshot reports exact byte count");
    const std::span<const std::byte> bytes(output.data(), result.bytes_written);
    expect(std::memcmp(bytes.data(), "WNTM", 4) == 0, "WNTM bridge magic is exact");
    expect(read_u16(bytes, 4) == 1 && read_u16(bytes, 6) == 32, "WNTM bridge header is V1/32 bytes");
    expect(read_u32(bytes, 24) == 3 && read_u32(bytes, 28) == result.bytes_written,
           "WNTM header counts records and bytes");
    expect(read_u32(bytes, 32) == registration.source->source_id(), "record carries source id");
    expect(read_u16(bytes, 36) == 1 && std::to_integer<std::uint8_t>(bytes[38]) == 1,
           "counter record has explicit metric id and kind");
    expect(read_u64(bytes, 48) == 42, "counter payload is little-endian u64");

    registry.unregister_source(registration.source->source_id());
    const auto empty = registry.snapshot_into(output);
    expect(empty.status == RuntimeTelemetrySnapshotStatus::Success && empty.bytes_written == 32,
           "unregistered source is absent from future snapshots");
}

} // namespace

int main() {
    test_primitives();
    test_histogram_concurrency();
    test_registry_snapshot_and_buffer_bound();
    if (failures != 0) {
        std::cerr << failures << " runtime telemetry test failure(s)\n";
        return 1;
    }
    std::cout << "Runtime telemetry tests passed\n";
    return 0;
}

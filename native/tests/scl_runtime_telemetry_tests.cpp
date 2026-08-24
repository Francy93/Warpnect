#include "runtime_telemetry.h"
#include "runtime_clock_sync_telemetry.h"
#include "runtime_network_telemetry.h"

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
using warpnect::scl::RuntimeNetworkTelemetry;
using warpnect::scl::RuntimeClockSyncTelemetry;
using warpnect::scl::ClockModelSnapshot;
using warpnect::scl::ClockSyncState;

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

void test_registry_accepts_caller_owned_source_id() {
    RuntimeTelemetryRegistry registry{};
    const auto registration = registry.register_source_with_id(
        91, {RuntimeTelemetryMetricDefinition{.metric_id = 0x0420,
                                               .kind = RuntimeTelemetryMetricKind::CounterU64}});
    expect(registration.source != nullptr && registration.source->source_id() == 91,
           "native callback source preserves the Kotlin-assigned source id");
    registration.source->counter(0x0420)->increment();
    std::array<std::byte, 128> output{};
    const auto result = registry.snapshot_into(output);
    expect(result.status == RuntimeTelemetrySnapshotStatus::Success,
           "caller-owned native source is included in the batched snapshot");
    expect(read_u32(output, 32) == 91 && read_u16(output, 36) == 0x0420 &&
               read_u64(output, 48) == 1,
           "caller-owned source keeps its metric identity and counter value");
}

void test_network_telemetry_bundle_uses_one_prebound_source() {
    RuntimeTelemetryRegistry registry{};
    const auto registration = registry.register_source_with_id(
        92,
        {
            {0x0201, RuntimeTelemetryMetricKind::CounterU64},
            {0x0202, RuntimeTelemetryMetricKind::CounterU64},
            {0x0203, RuntimeTelemetryMetricKind::CounterU64},
            {0x0204, RuntimeTelemetryMetricKind::CounterU64},
            {0x0205, RuntimeTelemetryMetricKind::CounterU64},
            {0x0206, RuntimeTelemetryMetricKind::CounterU64},
            {0x0207, RuntimeTelemetryMetricKind::CounterU64},
            {0x0208, RuntimeTelemetryMetricKind::CounterU64},
            {0x0209, RuntimeTelemetryMetricKind::CounterU64},
            {0x0221, RuntimeTelemetryMetricKind::CounterU64},
            {0x0222, RuntimeTelemetryMetricKind::CounterU64},
            {0x0223, RuntimeTelemetryMetricKind::CounterU64},
            {0x0224, RuntimeTelemetryMetricKind::CounterU64},
            {0x0225, RuntimeTelemetryMetricKind::CounterU64},
            {0x0226, RuntimeTelemetryMetricKind::CounterU64},
            {0x0231, RuntimeTelemetryMetricKind::CounterU64},
            {0x0232, RuntimeTelemetryMetricKind::CounterU64},
            {0x0233, RuntimeTelemetryMetricKind::CounterU64},
            {0x0234, RuntimeTelemetryMetricKind::CounterU64},
            {0x0241, RuntimeTelemetryMetricKind::CounterU64},
            {0x0242, RuntimeTelemetryMetricKind::CounterU64},
            {0x0243, RuntimeTelemetryMetricKind::CounterU64},
            {0x0244, RuntimeTelemetryMetricKind::CounterU64},
            {0x0701, RuntimeTelemetryMetricKind::CounterU64},
            {0x0702, RuntimeTelemetryMetricKind::CounterU64},
            {0x0703, RuntimeTelemetryMetricKind::CounterU64},
            {0x0704, RuntimeTelemetryMetricKind::CounterU64},
            {0x0705, RuntimeTelemetryMetricKind::CounterU64},
            {0x0706, RuntimeTelemetryMetricKind::CounterU64},
            {0x0707, RuntimeTelemetryMetricKind::CounterU64},
            {0x0708, RuntimeTelemetryMetricKind::CounterU64},
            {0x0709, RuntimeTelemetryMetricKind::CounterU64},
        });
    expect(registration.source != nullptr, "network telemetry source registers once");
    RuntimeNetworkTelemetry telemetry(registration.source);
    telemetry.protection_record_produced();
    telemetry.udp_would_block();
    telemetry.socket_rebind();
    telemetry.retransmission_sent();
    telemetry.protection_record_accepted();
    telemetry.protection_replay_dropped();

    expect(registration.source->counter(0x0701)->value() == 1,
           "fresh protection is distinct from the send result");
    expect(registration.source->counter(0x0205)->value() == 1,
           "WouldBlock is recorded without a successful UDP send");
    expect(registration.source->counter(0x0209)->value() == 1,
           "live rebind preserves the existing telemetry source");
    expect(registration.source->counter(0x0233)->value() == 1,
           "exact retransmission has its own counter");
    expect(registration.source->counter(0x0702)->value() == 1 &&
               registration.source->counter(0x0705)->value() == 1,
           "accepted and replay-rejected records retain independent outcomes");
}

void test_clock_sync_telemetry_uses_one_prebound_source() {
    RuntimeTelemetryRegistry registry{};
    const auto registration = registry.register_source_with_id(
        93,
        {
            {0x0601, RuntimeTelemetryMetricKind::CounterU64},
            {0x0602, RuntimeTelemetryMetricKind::CounterU64},
            {0x0603, RuntimeTelemetryMetricKind::GaugeI64},
            {0x0604, RuntimeTelemetryMetricKind::GaugeI64},
            {0x0606, RuntimeTelemetryMetricKind::HistogramU64, {100, 250, 500}},
        });
    expect(registration.source != nullptr, "ClockSync telemetry source registers once");
    RuntimeClockSyncTelemetry telemetry(registration.source);
    telemetry.accepted(ClockModelSnapshot{
        .state = ClockSyncState::Synchronized,
        .reference_local_us = 3'000.0,
        .reference_remote_us = 2'750.0,
        .latest_rtt_us = 125,
    });
    telemetry.rejected();

    expect(registration.source->counter(0x0601)->value() == 1 &&
               registration.source->counter(0x0602)->value() == 1,
           "ClockSync accepted and rejected samples remain distinct");
    expect(registration.source->gauge(0x0603)->valid() &&
               registration.source->gauge(0x0603)->value() == 1,
           "qualified ClockSync state is a valid Boolean gauge");
    expect(registration.source->gauge(0x0604)->valid() &&
               registration.source->gauge(0x0604)->value() == 250,
           "ClockSync offset comes from the current model reference points");
    expect(registration.source->histogram(0x0606)->snapshot().count == 1,
           "ClockSync round trip records one bounded sample");
}

} // namespace

int main() {
    test_primitives();
    test_histogram_concurrency();
    test_registry_snapshot_and_buffer_bound();
    test_registry_accepts_caller_owned_source_id();
    test_network_telemetry_bundle_uses_one_prebound_source();
    test_clock_sync_telemetry_uses_one_prebound_source();
    if (failures != 0) {
        std::cerr << failures << " runtime telemetry test failure(s)\n";
        return 1;
    }
    std::cout << "Runtime telemetry tests passed\n";
    return 0;
}

#include "runtime_diagnostic_event.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <span>
#include <thread>
#include <vector>

namespace {

using warpnect::scl::diagnostics::NativeDiagnosticEventRecord;
using warpnect::scl::diagnostics::NativeDiagnosticSnapshotStatus;
using warpnect::scl::diagnostics::RuntimeDiagnosticEventBuffer;

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
    std::uint32_t value = 0;
    for (std::size_t index = 0; index < sizeof(value); ++index) {
        value |= static_cast<std::uint32_t>(std::to_integer<std::uint8_t>(bytes[offset + index])) <<
                 (index * 8U);
    }
    return value;
}

[[nodiscard]] std::uint64_t read_u64(const std::span<const std::byte> bytes,
                                     const std::size_t offset) {
    std::uint64_t value = 0;
    for (std::size_t index = 0; index < sizeof(value); ++index) {
        value |= static_cast<std::uint64_t>(std::to_integer<std::uint8_t>(bytes[offset + index])) <<
                 (index * 8U);
    }
    return value;
}

[[nodiscard]] NativeDiagnosticEventRecord session_state_event() {
    NativeDiagnosticEventRecord record{};
    record.timestamp_ns = 123'456;
    record.clock_domain = 4; // NativeSteady
    record.severity = 2;     // Info
    record.scope_kind = 2;   // Session
    record.field_count = 2;
    record.event_type_id = 0x0101;
    record.source_id = 7;
    record.session_generation = 1;
    record.session_id_high = 0x0102030405060708ULL;
    record.session_id_low = 0x1112131415161718ULL;
    record.payload[0] = 1;
    record.payload[1] = 2;
    return record;
}

void test_wn_de_golden_layout() {
    RuntimeDiagnosticEventBuffer buffer{};
    expect(buffer.emit(session_state_event()), "native ring accepts a valid fixed event");
    std::array<std::byte, 512> output{};
    const auto result = buffer.snapshot_into(output, 0, 256);
    expect(result.status == NativeDiagnosticSnapshotStatus::Success, "snapshot succeeds");
    expect(result.bytes_written == 128, "one WNDE record has the exact 32+96 byte size");
    const std::span<const std::byte> bytes(output.data(), result.bytes_written);
    expect(std::memcmp(bytes.data(), "WNDE", 4) == 0, "WNDE magic is exact");
    expect(read_u16(bytes, 4) == 1 && read_u16(bytes, 6) == 32, "WNDE header is V1 and 32 bytes");
    expect(read_u32(bytes, 24) == 1 && read_u32(bytes, 28) == 128, "WNDE header count and size are exact");
    expect(read_u64(bytes, 32) == 1 && read_u64(bytes, 40) == 123'456, "record sequence and timestamp are little endian");
    expect(read_u16(bytes, 52) == 0x0101 && read_u32(bytes, 56) == 7, "record event id and source id are exact");
    expect(read_u64(bytes, 96) == 1 && read_u64(bytes, 104) == 2, "fixed payload slots are little endian");
}

void test_overwrite_gap_and_buffer_bound() {
    RuntimeDiagnosticEventBuffer buffer{};
    for (std::size_t index = 0; index <= 1'025; ++index) {
        expect(buffer.emit(session_state_event()), "uncontended ring writer remains nonblocking");
    }
    std::array<std::byte, 16> too_small{};
    too_small.fill(std::byte{0x5a});
    const auto small = buffer.snapshot_into(too_small, 0, 256);
    expect(small.status == NativeDiagnosticSnapshotStatus::BufferTooSmall, "small output reports required capacity");
    expect(too_small[0] == std::byte{0x5a}, "small output receives no partial WNDE header");

    std::array<std::byte, 128 * 1024> output{};
    const auto result = buffer.snapshot_into(output, 1, 512);
    expect(result.status == NativeDiagnosticSnapshotStatus::Success && result.gap,
           "cursor before retained window reports a history gap");
    expect(result.overwritten == 2 && result.oldest_available_sequence == 3,
           "overwritten records advance the oldest retained sequence");
    expect(result.truncated && result.next_cursor == 514,
           "bounded reads return a valid incremental cursor");
}

void test_concurrent_writers_have_unique_retained_sequences() {
    RuntimeDiagnosticEventBuffer buffer{};
    constexpr std::size_t kThreadCount = 4;
    constexpr std::size_t kEventsPerThread = 400;
    std::array<std::thread, kThreadCount> writers{};
    for (auto& writer : writers) {
        writer = std::thread([&buffer]() {
            for (std::size_t index = 0; index < kEventsPerThread; ++index) {
                static_cast<void>(buffer.emit(session_state_event()));
            }
        });
    }
    for (auto& writer : writers) writer.join();

    std::array<std::byte, 128 * 1024> output{};
    const auto result = buffer.snapshot_into(output, 0, 512);
    expect(result.status == NativeDiagnosticSnapshotStatus::Success && result.bytes_written >= 128,
           "concurrent writers leave complete retained records");
    const std::span<const std::byte> bytes(output.data(), result.bytes_written);
    const auto count = read_u32(bytes, 24);
    std::uint64_t previous = 0;
    for (std::uint32_t index = 0; index < count; ++index) {
        const auto sequence = read_u64(bytes, 32 + index * 96);
        expect(sequence > previous, "retained native events have no duplicate or torn sequence");
        previous = sequence;
    }
}

} // namespace

int main() {
    test_wn_de_golden_layout();
    test_overwrite_gap_and_buffer_bound();
    test_concurrent_writers_have_unique_retained_sequences();
    if (failures != 0) {
        std::cerr << failures << " diagnostic event test failure(s)\n";
        return 1;
    }
    std::cout << "Diagnostic event tests passed\n";
    return 0;
}

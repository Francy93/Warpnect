#include "runtime_diagnostic_event.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <limits>

namespace warpnect::scl::diagnostics {
namespace {

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

void write_record(std::span<std::byte> output, const std::size_t offset,
                  const NativeDiagnosticEventRecord& record) noexcept {
    write_u64_le(output, offset, record.event_sequence);
    write_u64_le(output, offset + 8, record.timestamp_ns);
    output[offset + 16] = static_cast<std::byte>(record.clock_domain);
    output[offset + 17] = static_cast<std::byte>(record.severity);
    output[offset + 18] = static_cast<std::byte>(record.scope_kind);
    output[offset + 19] = static_cast<std::byte>(record.field_count);
    write_u16_le(output, offset + 20, record.event_type_id);
    write_u16_le(output, offset + 22, record.flags);
    write_u32_le(output, offset + 24, record.source_id);
    write_u32_le(output, offset + 28, record.session_generation);
    write_u64_le(output, offset + 32, record.session_id_high);
    write_u64_le(output, offset + 40, record.session_id_low);
    write_u32_le(output, offset + 48, record.path_id);
    write_u32_le(output, offset + 52, record.channel_id);
    output[offset + 56] = static_cast<std::byte>(record.path_kind);
    output[offset + 57] = static_cast<std::byte>(record.channel_kind);
    output[offset + 58] = static_cast<std::byte>(record.channel_direction);
    output[offset + 59] = static_cast<std::byte>(record.component_kind);
    write_u32_le(output, offset + 60, 0);
    for (std::size_t index = 0; index < record.payload.size(); ++index) {
        write_u64_le(output, offset + 64 + (index * sizeof(std::uint64_t)), record.payload[index]);
    }
}

} // namespace

bool RuntimeDiagnosticEventBuffer::emit(NativeDiagnosticEventRecord record) noexcept {
    if (record.event_type_id == 0 || record.clock_domain == 0 || record.clock_domain > 4 ||
        record.severity == 0 || record.severity > 5 || record.scope_kind == 0 || record.scope_kind > 5 ||
        record.field_count > record.payload.size() || record.flags != 0 || record.reserved != 0) {
        return false;
    }
    // Events are rare control/failure observations. If a cold snapshot owns the ring, drop this
    // observation rather than ever blocking the producer that reported it.
    std::unique_lock lock(mutex_, std::try_to_lock);
    if (!lock.owns_lock()) {
        return false;
    }
    if (sealed_ || next_event_sequence_ == 0) {
        sealed_ = true;
        return false;
    }
    record.event_sequence = next_event_sequence_;
    records_[static_cast<std::size_t>(next_event_sequence_ % kNativeDiagnosticEventCapacity)] = record;
    if (next_event_sequence_ > kNativeDiagnosticEventCapacity) {
        ++overwritten_;
    }
    if (next_event_sequence_ == std::numeric_limits<std::uint64_t>::max()) {
        sealed_ = true;
    } else {
        ++next_event_sequence_;
    }
    return true;
}

NativeDiagnosticSnapshotResult RuntimeDiagnosticEventBuffer::snapshot_into(
    std::span<std::byte> output, const std::uint64_t cursor, const std::size_t limit) noexcept {
    std::lock_guard lock(mutex_);
    NativeDiagnosticSnapshotResult result{};
    result.batch_sequence = ++next_batch_sequence_;
    result.source_monotonic_ns = monotonic_now_ns();
    if (limit == 0 || limit > 512) {
        result.status = NativeDiagnosticSnapshotStatus::Closed;
        return result;
    }
    const std::uint64_t newest = next_event_sequence_ == 0 ? std::numeric_limits<std::uint64_t>::max()
                                                            : next_event_sequence_ - 1;
    if (newest == 0) {
        result.required_bytes = kNativeDiagnosticHeaderBytes;
        if (output.size() < result.required_bytes) {
            result.status = NativeDiagnosticSnapshotStatus::BufferTooSmall;
            return result;
        }
        std::memcpy(output.data(), "WNDE", 4);
        write_u16_le(output, 4, kNativeDiagnosticBridgeVersion);
        write_u16_le(output, 6, kNativeDiagnosticHeaderBytes);
        write_u64_le(output, 8, result.batch_sequence);
        write_u64_le(output, 16, result.source_monotonic_ns);
        write_u32_le(output, 24, 0);
        write_u32_le(output, 28, static_cast<std::uint32_t>(result.required_bytes));
        result.bytes_written = result.required_bytes;
        return result;
    }
    const std::uint64_t oldest = newest >= kNativeDiagnosticEventCapacity
                                     ? newest - kNativeDiagnosticEventCapacity + 1
                                     : 1;
    result.oldest_available_sequence = oldest;
    result.newest_available_sequence = newest;
    result.overwritten = overwritten_;
    result.gap = cursor != 0 && cursor < oldest - 1;
    const bool has_new_records = cursor < newest;
    const std::uint64_t cursor_next = cursor == std::numeric_limits<std::uint64_t>::max() ? cursor : cursor + 1;
    std::uint64_t sequence = has_new_records ? std::max(oldest, cursor_next) : newest;
    std::array<NativeDiagnosticEventRecord, 512> selected{};
    std::size_t count = 0;
    while (has_new_records && sequence <= newest && count < limit) {
        const auto& record = records_[static_cast<std::size_t>(sequence % kNativeDiagnosticEventCapacity)];
        if (record.event_sequence == sequence) {
            selected[count++] = record;
        }
        ++sequence;
    }
    result.truncated = has_new_records && sequence <= newest;
    result.next_cursor = count == 0 ? newest : selected[count - 1].event_sequence;
    result.required_bytes = kNativeDiagnosticHeaderBytes + (count * kNativeDiagnosticRecordBytes);
    if (result.required_bytes > kNativeDiagnosticMaxBytes || output.size() < result.required_bytes) {
        result.status = NativeDiagnosticSnapshotStatus::BufferTooSmall;
        return result;
    }
    std::memcpy(output.data(), "WNDE", 4);
    write_u16_le(output, 4, kNativeDiagnosticBridgeVersion);
    write_u16_le(output, 6, kNativeDiagnosticHeaderBytes);
    write_u64_le(output, 8, result.batch_sequence);
    write_u64_le(output, 16, result.source_monotonic_ns);
    write_u32_le(output, 24, static_cast<std::uint32_t>(count));
    write_u32_le(output, 28, static_cast<std::uint32_t>(result.required_bytes));
    for (std::size_t index = 0; index < count; ++index) {
        write_record(output, kNativeDiagnosticHeaderBytes + (index * kNativeDiagnosticRecordBytes), selected[index]);
    }
    result.bytes_written = result.required_bytes;
    return result;
}

RuntimeDiagnosticEventBuffer& runtime_diagnostic_event_buffer() noexcept {
    static RuntimeDiagnosticEventBuffer buffer{};
    return buffer;
}

} // namespace warpnect::scl::diagnostics

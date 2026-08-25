#ifndef WARPNECT_SCL_RUNTIME_DIAGNOSTIC_EVENT_H_
#define WARPNECT_SCL_RUNTIME_DIAGNOSTIC_EVENT_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <span>

namespace warpnect::scl::diagnostics {

inline constexpr std::uint16_t kDiagnosticEventModelVersion = 1;
inline constexpr std::uint16_t kNativeDiagnosticBridgeVersion = 1;
inline constexpr std::size_t kNativeDiagnosticEventCapacity = 1'024;
inline constexpr std::size_t kNativeDiagnosticHeaderBytes = 32;
inline constexpr std::size_t kNativeDiagnosticRecordBytes = 96;
inline constexpr std::size_t kNativeDiagnosticMaxBytes = 128U * 1024U;

enum class NativeDiagnosticSnapshotStatus : std::uint32_t {
    Success = 0,
    BufferTooSmall = 1,
    Closed = 2,
};

struct NativeDiagnosticEventRecord final {
    std::uint64_t event_sequence = 0;
    std::uint64_t timestamp_ns = 0;
    std::uint8_t clock_domain = 0;
    std::uint8_t severity = 0;
    std::uint8_t scope_kind = 0;
    std::uint8_t field_count = 0;
    std::uint16_t event_type_id = 0;
    std::uint16_t flags = 0;
    std::uint32_t source_id = 0;
    std::uint32_t session_generation = 0;
    std::uint64_t session_id_high = 0;
    std::uint64_t session_id_low = 0;
    std::uint32_t path_id = 0;
    std::uint32_t channel_id = 0;
    std::uint8_t path_kind = 0;
    std::uint8_t channel_kind = 0;
    std::uint8_t channel_direction = 0;
    std::uint8_t component_kind = 0;
    std::uint32_t reserved = 0;
    std::array<std::uint64_t, 4> payload{};
};

static_assert(sizeof(NativeDiagnosticEventRecord) == kNativeDiagnosticRecordBytes);

struct NativeDiagnosticSnapshotResult final {
    NativeDiagnosticSnapshotStatus status = NativeDiagnosticSnapshotStatus::Success;
    std::size_t required_bytes = 0;
    std::size_t bytes_written = 0;
    std::uint64_t batch_sequence = 0;
    std::uint64_t source_monotonic_ns = 0;
    std::uint64_t oldest_available_sequence = 0;
    std::uint64_t newest_available_sequence = 0;
    std::uint64_t next_cursor = 0;
    std::uint64_t overwritten = 0;
    bool gap = false;
    bool truncated = false;
};

/** Rare transition/failure writer. Packet and real-time paths deliberately do not call it. */
class RuntimeDiagnosticEventBuffer final {
  public:
    [[nodiscard]] bool emit(NativeDiagnosticEventRecord record) noexcept;
    [[nodiscard]] NativeDiagnosticSnapshotResult snapshot_into(std::span<std::byte> output,
                                                                std::uint64_t cursor,
                                                                std::size_t limit) noexcept;

  private:
    std::mutex mutex_{};
    std::array<NativeDiagnosticEventRecord, kNativeDiagnosticEventCapacity> records_{};
    std::uint64_t next_event_sequence_ = 1;
    std::uint64_t next_batch_sequence_ = 0;
    std::uint64_t overwritten_ = 0;
    bool sealed_ = false;
};

[[nodiscard]] RuntimeDiagnosticEventBuffer& runtime_diagnostic_event_buffer() noexcept;

} // namespace warpnect::scl::diagnostics

#endif // WARPNECT_SCL_RUNTIME_DIAGNOSTIC_EVENT_H_

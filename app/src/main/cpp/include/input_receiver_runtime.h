#ifndef WARPNECT_SCL_INPUT_RECEIVER_RUNTIME_H_
#define WARPNECT_SCL_INPUT_RECEIVER_RUNTIME_H_

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <span>

#include "input_protocol.h"
#include "datagram_protection.h"
#include "udp_socket.h"

namespace warpnect::scl {

inline constexpr std::size_t kInputReceiverMaxDatagramWireSize = 417;
inline constexpr std::size_t kInputReceiverMaxProtectedDatagramWireSize =
    kInputReceiverMaxDatagramWireSize + 44;
inline constexpr std::size_t kInputReceiverReceiveScratchSize =
    kInputReceiverMaxProtectedDatagramWireSize + 1;

// This is a native-order JNI record, not an Input Payload V1 wire layout.
inline constexpr std::size_t kInputReceiverBridgeCapacity = 1024;
inline constexpr std::size_t kInputReceiverBridgeMessageTypeOffset = 0;
inline constexpr std::size_t kInputReceiverBridgeDeviceKindOffset = 4;
inline constexpr std::size_t kInputReceiverBridgeDeviceSlotOffset = 8;
inline constexpr std::size_t kInputReceiverBridgeSequenceOffset = 16;
inline constexpr std::size_t kInputReceiverBridgeSourceTimeOffset = 24;
inline constexpr std::size_t kInputReceiverBridgeBodyOffset = 32;
inline constexpr std::size_t kInputReceiverBridgeTouchContactsOffset = 64;
inline constexpr std::size_t kInputReceiverBridgeTouchContactStride = 28;
inline constexpr std::size_t kInputReceiverBridgeRequiredBytes =
    kInputReceiverBridgeTouchContactsOffset +
    kInputMaxTouchContacts * kInputReceiverBridgeTouchContactStride;

static_assert(kInputReceiverBridgeRequiredBytes <= kInputReceiverBridgeCapacity);

enum class InputReceiverEventType : std::uint8_t {
    None = 0,
    EventReady = 1,
    Interrupted = 2,
    Timeout = 3,
    UnexpectedEndpointDropped = 4,
    MalformedDatagramDropped = 5,
    UnsupportedInputDropped = 6,
    OversizeDatagramDropped = 7,
    SocketFailure = 8,
    Closed = 9,
};

enum class InputReceiverError : std::uint8_t {
    None = 0,
    InvalidConfiguration = 1,
    InvalidEndpoint = 2,
    UdpOpenFailed = 3,
    UdpBindFailed = 4,
    DatagramReceiveFailed = 5,
    BridgeBufferTooSmall = 6,
    Closed = 7,
};

struct InputReceiverConfig final {
    UdpEndpoint local_endpoint{};
    UdpEndpoint expected_remote_endpoint{};
    std::size_t max_wire_datagram_size = kInputReceiverMaxDatagramWireSize;
    DatagramProtector* protector = nullptr;
};

struct InputReceiverEvent final {
    InputReceiverEventType type = InputReceiverEventType::None;
    InputReceiverError error = InputReceiverError::None;
    std::uint32_t sequence_number = 0;
    std::uint64_t source_event_time_us = 0;
    InputMessageHeader input_header{};
    InputKeyEvent key{};
    InputTouchFrame touch_frame{};
    InputPointerAbsolute pointer_absolute{};
    InputPointerRelative pointer_relative{};
    InputScroll scroll{};
    InputGamepadState gamepad_state{};
    InputResetState reset_state{};
};

struct InputReceiverSnapshot final {
    bool opened = false;
    bool closed = false;
    std::uint16_t local_endpoint_port = 0;
    std::uint64_t datagrams_received = 0;
    std::uint64_t events_delivered = 0;
    std::uint64_t unexpected_endpoint_drops = 0;
    std::uint64_t malformed_datagram_drops = 0;
    std::uint64_t unsupported_input_drops = 0;
    std::uint64_t oversize_datagram_drops = 0;
    std::uint64_t socket_failures = 0;
    std::uint64_t sequence_first = 0;
    std::uint64_t sequence_contiguous = 0;
    std::uint64_t sequence_gap_events = 0;
    std::uint64_t sequence_gap_count = 0;
    std::uint64_t sequence_same = 0;
    std::uint64_t sequence_out_of_order = 0;
    std::uint32_t latest_sequence = 0;
    std::uint64_t latest_source_event_time_us = 0;
    bool has_latest_sequence = false;
    bool has_latest_source_event_time = false;
    InputReceiverError last_error = InputReceiverError::None;
};

class InputReceiverRuntime final {
  public:
    explicit InputReceiverRuntime(InputReceiverConfig config) noexcept;
    ~InputReceiverRuntime() noexcept;

    InputReceiverRuntime(const InputReceiverRuntime&) = delete;
    InputReceiverRuntime& operator=(const InputReceiverRuntime&) = delete;

    [[nodiscard]] InputReceiverError open() noexcept;
    void adopt_prebound_socket(UdpSocket socket) noexcept;
    [[nodiscard]] InputReceiverEvent pump(std::uint64_t timeout_us) noexcept;
    [[nodiscard]] InputReceiverEvent accept_datagram(std::span<const std::byte> datagram,
                                                     const UdpEndpoint& source) noexcept;
    [[nodiscard]] InputReceiverError write_bridge(std::span<std::byte> output) const noexcept;
    [[nodiscard]] UdpEndpointResult local_endpoint() const noexcept;
    [[nodiscard]] InputReceiverSnapshot snapshot() const noexcept;
    void interrupt() noexcept;
    void wake() noexcept;
    void close() noexcept;

  private:
    [[nodiscard]] InputReceiverEvent receive_one(std::uint64_t timeout_us) noexcept;
    [[nodiscard]] InputReceiverEvent decode_parsed_input(std::span<const std::byte> payload,
                                                         const InputMessageHeader& header,
                                                         std::uint32_t sequence_number,
                                                         std::uint64_t source_event_time_us) noexcept;
    [[nodiscard]] InputReceiverEvent event(InputReceiverEventType type,
                                           InputReceiverError error = InputReceiverError::None) noexcept;
    void record_sequence(std::uint32_t sequence_number) noexcept;
    void remember(InputReceiverError error) noexcept;

    InputReceiverConfig config_{};
    UdpSocket socket_{};
    std::array<std::byte, kInputReceiverReceiveScratchSize> receive_scratch_{};
    std::array<std::byte, kInputReceiverMaxDatagramWireSize> unprotected_scratch_{};
    std::atomic_bool interrupted_{false};
    InputReceiverSnapshot snapshot_{};
    InputReceiverEvent latest_event_{};
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_INPUT_RECEIVER_RUNTIME_H_

#ifndef WARPNECT_SCL_INPUT_TRANSPORT_H_
#define WARPNECT_SCL_INPUT_TRANSPORT_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "input_packetizer.h"
#include "datagram_protection.h"
#include "packet_codec.h"
#include "udp_socket.h"

namespace warpnect::scl {

struct InputTransportSenderConfig final {
    UdpEndpoint remote_endpoint{};
    std::uint16_t local_port = 0;
    std::size_t max_wire_datagram_size = kInputMaxDatagramWireSize;
    std::uint32_t initial_input_sequence = 0;
    DatagramProtector* protector = nullptr;
};

struct InputTransportSenderWorkspace final {
    std::span<std::byte> datagram_scratch{};
    std::span<std::byte> protected_datagram_scratch{};
};

struct InputTransportSnapshot final {
    std::uint16_t local_endpoint_port = 0;
    IpVersion local_endpoint_ip_version = IpVersion::V4;
    std::uint32_t next_input_sequence = 0;
    std::uint64_t events_submitted = 0;
    std::uint64_t datagrams_attempted = 0;
    std::uint64_t datagrams_sent = 0;
    std::uint64_t bytes_sent = 0;
    std::uint64_t fresh_state_submitted = 0;
    std::uint64_t fresh_state_sent = 0;
    std::uint64_t fresh_state_dropped = 0;
    std::uint64_t critical_transitions_submitted = 0;
    std::uint64_t critical_transitions_sent = 0;
    std::uint64_t critical_transitions_dropped = 0;
    std::uint64_t resets_submitted = 0;
    std::uint64_t resets_sent = 0;
    std::uint64_t reset_send_failures = 0;
    std::uint64_t key_events = 0;
    std::uint64_t touch_frames = 0;
    std::uint64_t pointer_absolute_events = 0;
    std::uint64_t pointer_relative_events = 0;
    std::uint64_t scroll_events = 0;
    std::uint64_t gamepad_states = 0;
    std::uint64_t reset_events = 0;
    std::uint64_t would_block_count = 0;
    std::uint64_t send_failure_count = 0;
    std::uint64_t last_event_timestamp_us = 0;
    std::uint32_t last_attempted_sequence = 0;
    std::uint32_t last_sent_sequence = 0;
    InputTransportError last_error = InputTransportError::None;
    bool has_last_event_timestamp = false;
    bool has_last_attempted_sequence = false;
    bool has_last_sent_sequence = false;
    bool has_local_endpoint = false;
    bool opened = false;
    bool closed = false;
};

class InputTransportSender final : private InputDatagramSink {
  public:
    InputTransportSender(InputTransportSenderConfig config,
                         InputTransportSenderWorkspace workspace) noexcept;
    InputTransportSender(InputTransportSenderConfig config,
                         InputTransportSenderWorkspace workspace,
                         InputDatagramSink& test_datagram_sink) noexcept;
    ~InputTransportSender() noexcept;

    InputTransportSender(const InputTransportSender&) = delete;
    InputTransportSender& operator=(const InputTransportSender&) = delete;

    [[nodiscard]] InputTransportStatus open() noexcept;
    void adopt_prebound_socket(UdpSocket socket) noexcept;
    [[nodiscard]] InputTransportStatus rebind_prebound_socket(UdpSocket socket,
                                                               UdpEndpoint remote_endpoint) noexcept;
    [[nodiscard]] InputTransportStatus submit_key(std::uint64_t event_time_us,
                                                  const InputKeyEvent& event) noexcept;
    [[nodiscard]] InputTransportStatus submit_touch_frame(std::uint64_t event_time_us,
                                                          const InputTouchFrame& frame) noexcept;
    [[nodiscard]] InputTransportStatus submit_pointer_absolute(
        std::uint64_t event_time_us,
        const InputPointerAbsolute& event) noexcept;
    [[nodiscard]] InputTransportStatus submit_pointer_relative(
        std::uint64_t event_time_us,
        const InputPointerRelative& event) noexcept;
    [[nodiscard]] InputTransportStatus submit_scroll(std::uint64_t event_time_us,
                                                     const InputScroll& event) noexcept;
    [[nodiscard]] InputTransportStatus submit_gamepad_state(
        std::uint64_t event_time_us,
        const InputGamepadState& state) noexcept;
    [[nodiscard]] InputTransportStatus submit_reset_state(std::uint64_t event_time_us,
                                                          const InputResetState& reset) noexcept;
    [[nodiscard]] InputTransportSnapshot snapshot() const noexcept;
    void close() noexcept;

  private:
    [[nodiscard]] InputTransportStatus
    send_input_datagram(std::span<const std::byte> datagram) noexcept override;
    [[nodiscard]] InputTransportStatus ensure_ready() noexcept;
    [[nodiscard]] InputTransportStatus finalize_submission(InputMessageType message_type,
                                                           InputDeliveryClass delivery_class,
                                                           std::uint64_t event_time_us,
                                                           InputPacketizedResult result) noexcept;
    void record_message_type(InputMessageType message_type) noexcept;
    void record_delivery_submission(InputDeliveryClass delivery_class) noexcept;
    void record_delivery_result(InputDeliveryClass delivery_class, bool sent) noexcept;
    void remember(InputTransportError error) noexcept;

    InputTransportSenderConfig config_{};
    InputTransportSenderWorkspace workspace_{};
    UdpSocket socket_{};
    InputDatagramSink* test_datagram_sink_ = nullptr;
    InputPacketizer packetizer_;
    InputTransportSnapshot snapshot_{};
};

struct [[nodiscard]] InputDatagramParseResult final {
    InputTransportError error = InputTransportError::None;
    PacketHeader header{};
    InputMessageHeader input_header{};
    std::span<const std::byte> payload{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputTransportError::None;
    }
};

class InputDatagramParser final {
  public:
    [[nodiscard]] static InputDatagramParseResult
    parse(std::span<const std::byte> datagram) noexcept;
};

[[nodiscard]] InputTransportSizeResult
input_datagram_budget(std::size_t max_wire_datagram_size) noexcept;

} // namespace warpnect::scl

#endif // WARPNECT_SCL_INPUT_TRANSPORT_H_
